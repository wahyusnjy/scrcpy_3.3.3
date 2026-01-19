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
        Ln.i("WebSocket video streamer started");
    }

    private void readAndBroadcast(FileDescriptor readFd) throws IOException {
        FileInputStream inputStream = new FileInputStream(readFd);
        byte[] buffer = new byte[64 * 1024]; // 64KB buffer

        while (running) {
            int bytesRead = inputStream.read(buffer);
            if (bytesRead < 0) {
                // End of stream
                break;
            }

            if (bytesRead > 0) {
                // Create ByteBuffer and send via WebSocket
                ByteBuffer byteBuffer = ByteBuffer.allocate(bytesRead);
                byteBuffer.put(buffer, 0, bytesRead);
                byteBuffer.flip();
                connection.broadcastVideoFrame(byteBuffer);
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
}
