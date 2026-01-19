package com.genymobile.scrcpy.websocket;

import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.wrappers.SurfaceControl;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.IBinder;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * MJPEG (Motion JPEG) Video Streamer for WebSocket
 * Captures screenshots and encodes them as JPEG for browser compatibility
 */
public class MJPEGStreamer {
    private final WebSocketConnection connection;
    private final Options options;
    private Thread captureThread;
    private volatile boolean running = false;
    private IBinder display;

    // MJPEG settings
    private static final int FPS = 30; // Target FPS
    private static final int JPEG_QUALITY = 80; // 0-100
    private static final long FRAME_DELAY_MS = 1000 / FPS;

    public MJPEGStreamer(WebSocketConnection connection, Options options) {
        this.connection = connection;
        this.options = options;
    }

    public void start() throws IOException {
        Ln.i("Starting MJPEG streamer (FPS: " + FPS + ", Quality: " + JPEG_QUALITY + ")");
        running = true;

        captureThread = new Thread(() -> {
            try {
                streamMJPEG();
            } catch (Exception e) {
                if (running) {
                    Ln.e("MJPEG streaming error", e);
                }
            }
        }, "MJPEG-Capture");

        captureThread.start();
        Ln.i("MJPEG streamer started");
    }

    private void streamMJPEG() throws Exception {
        // Get display size
        int displayId = options.getDisplayId();
        if (displayId == Device.DISPLAY_ID_NONE) {
            displayId = 0; // Default display
        }

        // Create virtual display for screenshot
        display = SurfaceControl.createDisplay("scrcpy-mjpeg", false);
        Ln.d("MJPEG display created");

        int width = 320; // Default resolution
        int height = 720;

        long lastFrameTime = 0;
        int frameCount = 0;

        while (running) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastFrameTime;

            // Frame rate limiting
            if (elapsed < FRAME_DELAY_MS) {
                try {
                    Thread.sleep(FRAME_DELAY_MS - elapsed);
                } catch (InterruptedException e) {
                    break;
                }
                continue;
            }

            lastFrameTime = now;

            try {
                // Capture screenshot
                Bitmap screenshot = captureScreen(displayId, width, height);
                if (screenshot != null) {
                    // Encode to JPEG
                    byte[] jpegData = encodeJPEG(screenshot);
                    screenshot.recycle();

                    if (jpegData != null && jpegData.length > 0) {
                        // Send via WebSocket
                        ByteBuffer buffer = ByteBuffer.wrap(jpegData);
                        connection.broadcastVideoFrame(buffer);

                        frameCount++;
                        if (frameCount % 30 == 0) {
                            Ln.d("MJPEG frames sent: " + frameCount + ", size: " + (jpegData.length / 1024) + "KB");
                        }
                    }
                }
            } catch (Exception e) {
                Ln.e("Failed to capture/encode frame", e);
                // Continue trying
            }
        }

        // Cleanup
        if (display != null) {
            SurfaceControl.destroyDisplay(display);
            display = null;
        }
    }

    private Bitmap captureScreen(int displayId, int width, int height) {
        try {
            // TODO: Implement screenshot functionality
            // The SurfaceControl.screenshot() method is not available in the wrapper yet
            // Need to add the screenshot method to SurfaceControl wrapper class

            Ln.w("Screenshot API not implemented yet - MJPEG streaming requires additional implementation");
            return null;

            // Original code (commented out until screenshot API is implemented):
            // Rect displayRect = new Rect(0, 0, width, height);
            // Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            // SurfaceControl.screenshot(displayRect, width, height, displayId, bitmap);
            // return bitmap;
        } catch (Exception e) {
            Ln.e("Screenshot failed", e);
            return null;
        }
    }

    private byte[] encodeJPEG(Bitmap bitmap) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            boolean success = bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);

            if (success) {
                return out.toByteArray();
            } else {
                Ln.e("JPEG compression failed");
                return null;
            }
        } catch (Exception e) {
            Ln.e("JPEG encoding error", e);
            return null;
        }
    }

    public void stop() {
        Ln.i("Stopping MJPEG streamer");
        running = false;

        if (captureThread != null) {
            try {
                captureThread.join(2000);
            } catch (InterruptedException e) {
                Ln.w("Interrupted while waiting for MJPEG thread");
            }
        }

        Ln.i("MJPEG streamer stopped");
    }
}
