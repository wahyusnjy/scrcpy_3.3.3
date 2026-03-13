package com.genymobile.scrcpy.websocket;

import com.genymobile.scrcpy.AsyncProcessor;
import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.device.Streamer;
import com.genymobile.scrcpy.util.IO;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.video.ScreenCapture;
import com.genymobile.scrcpy.video.SurfaceEncoder;
import com.genymobile.scrcpy.video.SurfaceCapture;
import com.genymobile.scrcpy.video.VideoSource;
import com.genymobile.scrcpy.video.CameraCapture;
import com.genymobile.scrcpy.video.NewDisplayCapture;
import com.genymobile.scrcpy.device.NewDisplay;

import android.os.ParcelFileDescriptor;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Video streamer for WebSocket connections
 * Compatible with scrcpy v3.3.3 API
 */
public class WebSocketVideoStreamer {
    private final WebSocketConnection connection;
    private final Options options;
    private SurfaceEncoder surfaceEncoder;
    private Thread encoderThread;
    private Thread readerThread;
    private volatile boolean running = false;
    private ParcelFileDescriptor[] pipe;

    public WebSocketVideoStreamer(WebSocketConnection connection, Options options) {
        this.connection = connection;
        this.options = options;
    }

    public void start() throws IOException {
        Ln.i("Starting WebSocket video streamer");
        running = true;

        // Create a pipe: encoder writes to pipe[1], we read from pipe[0] and send via
        // WebSocket
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            Ln.e("Failed to create pipe for WebSocket video streaming", e);
            throw e;
        }

        FileDescriptor writeFd = pipe[1].getFileDescriptor();
        FileDescriptor readFd = pipe[0].getFileDescriptor();

        // Create surface capture based on video source
        SurfaceCapture surfaceCapture;
        if (options.getVideoSource() == VideoSource.DISPLAY) {
            NewDisplay newDisplay = options.getNewDisplay();
            if (newDisplay != null) {
                surfaceCapture = new NewDisplayCapture(null, options);
            } else {
                assert options.getDisplayId() != Device.DISPLAY_ID_NONE;
                surfaceCapture = new ScreenCapture(null, options);
            }
        } else {
            surfaceCapture = new CameraCapture(options);
        }

        // Create streamer that writes to pipe
        Streamer streamer = new Streamer(writeFd, options.getVideoCodec(),
                options.getSendCodecMeta(), options.getSendFrameMeta());

        // Create surface encoder
        surfaceEncoder = new SurfaceEncoder(surfaceCapture, streamer, options);

        // Start reader thread that reads from pipe and sends via WebSocket
        readerThread = new Thread(() -> {
            try {
                readAndBroadcast(readFd);
            } catch (IOException e) {
                if (running) {
                    Ln.e("Error reading video data from pipe", e);
                }
            }
        }, "WS-VideoReader");
        readerThread.start();

        // Start encoding using AsyncProcessor interface
        encoderThread = new Thread(() -> {
            try {
                surfaceEncoder.start(fatalError -> {
                    if (fatalError) {
                        Ln.e("Fatal error in video encoding");
                    }
                    running = false;
                });
            } catch (Exception e) {
                if (running) {
                    Ln.e("Video streaming error", e);
                }
            }
        }, "WS-VideoEncoder");

        encoderThread.start();

        // Periodic keyframe scheduler: request I-frame every 1s.
        // Scrcpy's default iFrameInterval is 10s, and the client's SET_VIDEO_SETTINGS
        // request is not handled in WebSocket mode. We force keyframe every 1s ourselves
        // so the buffer never falls more than 1s behind and new clients decode quickly.
        new Thread(() -> {
            // Initial warmup: give encoder 800ms to start before first keyframe
            try { Thread.sleep(800); } catch (InterruptedException ignored) {}
            while (running) {
                if (surfaceEncoder != null) {
                    try {
                        surfaceEncoder.requestKeyFrame();
                    } catch (Exception ignored) {}
                }
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { break; }
            }
        }, "WS-KeyframeScheduler").start();

        Ln.i("WebSocket video streamer started");
    }

    private void readAndBroadcast(FileDescriptor readFd) throws IOException {
        FileInputStream inputStream = new FileInputStream(readFd);

        // NAL-aware streaming: accumulate bytes until we find a complete NAL unit
        // boundary.
        // Each WebSocket message = exactly 1 NAL unit → browser decoder works reliably.
        // Buffer starts at 512KB and grows dynamically for large I-frames.
        java.io.ByteArrayOutputStream nalBuf = new java.io.ByteArrayOutputStream(512 * 1024);
        byte[] readBuf = new byte[128 * 1024]; // 128KB read chunks

        // H.264/H.265 NAL unit start code: 0x00 0x00 0x00 0x01
        // We accumulate bytes until we see the NEXT start code, then flush the previous
        // NAL.
        // The 3-byte start code variant (0x00 0x00 0x01) is also handled.

        // Ring buffer to detect start code across read() boundaries
        // Keeps last 3 bytes to detect split start codes
        int prev1 = -1, prev2 = -1, prev3 = -1; // sliding window

        while (running) {
            int bytesRead = inputStream.read(readBuf);
            if (bytesRead < 0) {
                // End of stream — flush whatever is buffered
                if (nalBuf.size() > 0) {
                    ByteBuffer bb = ByteBuffer.wrap(nalBuf.toByteArray());
                    connection.broadcastVideoFrame(bb);
                }
                break;
            }

            for (int i = 0; i < bytesRead; i++) {
                int b = readBuf[i] & 0xFF;

                // Detect 4-byte start code: 00 00 00 01
                boolean is4ByteStart = (prev3 == 0x00 && prev2 == 0x00 && prev1 == 0x00 && b == 0x01);
                // Detect 3-byte start code: 00 00 01 (only if NOT part of a 4-byte start code)
                boolean is3ByteStart = (!is4ByteStart && prev3 != 0x00 && prev2 == 0x00 && prev1 == 0x00 && b == 0x01);

                if (is4ByteStart || is3ByteStart) {
                    // Found start of a NEW NAL unit.
                    //
                    // NOTE: The sliding window writes prev3/prev2/prev1 to nalBuf as
                    // ordinary bytes, but does NOT write the current byte `b` (= 0x01).
                    //
                    // So when is4ByteStart: nalBuf ends with [... , 0x00, 0x00, 0x00]
                    //   → strip the last 3 bytes (the three 0x00 of the start code)
                    // When is3ByteStart: nalBuf ends with [... , 0x00, 0x00]
                    //   → strip the last 2 bytes
                    //
                    // WRONG (old): stripBytes = is4ByteStart ? 4 : 3
                    // CORRECT:     stripBytes = is4ByteStart ? 3 : 2
                    int stripBytes = is4ByteStart ? 3 : 2;
                    int bufSize = nalBuf.size();
                    int nalLen = bufSize - stripBytes;

                    if (nalLen > 0) {
                        // Flush the current NAL (exclude the trailing bytes of the new start code)
                        byte[] data = nalBuf.toByteArray();
                        ByteBuffer bb = ByteBuffer.wrap(data, 0, nalLen);
                        connection.broadcastVideoFrame(bb);
                    }
                    // Start fresh: write the complete start code for the new NAL
                    nalBuf.reset();
                    if (is4ByteStart) {
                        nalBuf.write(0x00);
                        nalBuf.write(0x00);
                        nalBuf.write(0x00);
                    } else {
                        nalBuf.write(0x00);
                        nalBuf.write(0x00);
                    }
                    nalBuf.write(0x01); // write the current byte (0x01) which was not yet in nalBuf
                } else {
                    nalBuf.write(b);
                }

                // Slide the window
                prev3 = prev2;
                prev2 = prev1;
                prev1 = b;
            }
        }
    }

    public void stop() {
        Ln.i("Stopping WebSocket video streamer");
        running = false;

        if (surfaceEncoder != null) {
            surfaceEncoder.stop();
        }

        if (encoderThread != null) {
            try {
                encoderThread.join(1000);
            } catch (InterruptedException e) {
                Ln.w("Interrupted while waiting for encoder thread");
            }
        }

        if (readerThread != null) {
            try {
                readerThread.join(1000);
            } catch (InterruptedException e) {
                Ln.w("Interrupted while waiting for reader thread");
            }
        }

        // Close pipe
        if (pipe != null) {
            try {
                pipe[0].close();
            } catch (IOException e) {
                Ln.w("Error closing pipe read end", e);
            }
            try {
                pipe[1].close();
            } catch (IOException e) {
                Ln.w("Error closing pipe write end", e);
            }
        }

        Ln.i("WebSocket video streamer stopped");
    }

    /**
     * Request immediate keyframe from encoder
     * Used when new client joins to minimize wait time
     */
    public void requestKeyframe() {
        if (surfaceEncoder != null) {
            try {
                Ln.i("🔑 Requesting immediate keyframe for new client");
                surfaceEncoder.requestKeyFrame();
            } catch (Exception e) {
                Ln.w("Failed to request keyframe: " + e.getMessage());
            }
        }
    }
}
