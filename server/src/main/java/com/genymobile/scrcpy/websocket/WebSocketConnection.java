package com.genymobile.scrcpy.websocket;

import org.java_websocket.WebSocket;

import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.control.ControlMessage;
import com.genymobile.scrcpy.control.Controller;
import com.genymobile.scrcpy.control.UhidManager;
import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.device.Point;
import com.genymobile.scrcpy.device.Position;
import com.genymobile.scrcpy.util.Ln;

import android.os.Build;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Manages a WebSocket connection stream for a specific display
 * Fixed for scrcpy v3.3.3 API compatibility with UHID support
 */
public class WebSocketConnection {
    private final Options options;
    private final int displayId;
    private final WSServer wsServer;
    private final Set<WebSocket> clients = new HashSet<>();
    private Controller controller;
    private WebSocketVideoStreamer videoStreamer;

    // Codec metadata cache for multi-client support
    // Stores ONLY codec parameter sets (SPS/PPS/VPS) - small frames < 500 bytes
    // Does NOT store video frames, preventing stale video on page refresh
    private final java.util.List<byte[]> codecMetadataCache = new java.util.ArrayList<>();
    private volatile boolean codecMetadataCached = false;

    // Grace-period cleanup: when all clients disconnect (e.g. page refresh), wait
    // CLEANUP_GRACE_MS before actually stopping the encoder. If a client reconnects
    // during the grace period (typical refresh), it finds the encoder already running
    // and gets video immediately without encoder warm-up delay.
    private static final long CLEANUP_GRACE_MS = 4000; // 4 seconds
    private java.util.concurrent.ScheduledFuture<?> pendingCleanup;
    private static final java.util.concurrent.ScheduledExecutorService cleanupScheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor();

    // UHID support
    private UhidManager uhidManager;
    private static final int UHID_KEYBOARD_ID = 1;
    private static final int UHID_MOUSE_ID = 2;

    // Control message types
    public static final int TYPE_UHID_KEYBOARD = 100; // UHID keyboard (requires root)
    public static final int TYPE_UHID_MOUSE = 101;    // UHID mouse (requires root)
    public static final int TYPE_INJECT_KEYCODE = 102; // Android Input keyboard (no root)
    public static final int TYPE_INJECT_TOUCH = 103;   // Touch/Tap events (no root)

    public WebSocketConnection(Options options, int displayId, WSServer wsServer) {
        this.options = options;
        this.displayId = displayId;
        this.wsServer = wsServer;
    }

    public synchronized void join(WebSocket webSocket) {
        Ln.i("Client joining: display=" + displayId + " clients=" + (clients.size() + 1));

        // Cancel any pending grace-period cleanup — client reconnected in time
        if (pendingCleanup != null && !pendingCleanup.isDone()) {
            pendingCleanup.cancel(false);
            pendingCleanup = null;
            Ln.i("Grace period cleanup cancelled — client reconnected");
        }

        boolean isFirstClient = clients.isEmpty();
        clients.add(webSocket);

        // Send device info to this client
        sendInitialInfo(webSocket);

        // Initialize video streamer if not already started
        if (videoStreamer == null && options.getVideo()) {
            Ln.i("Starting video streamer for display " + displayId);
            try {
                videoStreamer = new WebSocketVideoStreamer(this, options);
                videoStreamer.start();
                Ln.i("✅ Video streamer started");
            } catch (Exception e) {
                Ln.e("❌ Failed to start video streamer", e);
            }
        } else if (videoStreamer != null) {
            // Encoder already running (e.g. reconnect during grace period, or 2nd client)
            if (codecMetadataCache.size() > 0) {
                sendCodecMetadataToClient(webSocket);
            } else {
                Ln.i("⚠️  Cache empty – waiting for next keyframe");
            }
            // Request keyframe baru — coba beberapa kali karena MediaCodec beberapa device
            // tidak menjamin langsung merespons requestKeyFrame() pertama.
            // Dengan 5x percobaan/2 detik, client hampir pasti dapat I-frame <2s.
            final WebSocketVideoStreamer streamer = videoStreamer;
            new Thread(() -> {
                for (int attempt = 0; attempt < 5; attempt++) {
                    try {
                        // attempt 0 = 0ms delay (segera), attempt 1..4 = +400ms setiap kali
                        if (attempt > 0) Thread.sleep(400);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                    if (streamer != null) {
                        Ln.i("🔑 Keyframe request attempt " + (attempt + 1) + "/5 for new client");
                        streamer.requestKeyframe();
                    }
                }
            }, "WS-KeyframeRetry").start();
        } else {
            Ln.w("Video is disabled in options!");
        }

        // Initialize UHID if enabled and not already initialized
        if (uhidManager == null && (options.getKeyboardUhid() || options.getMouseUhid())) {
            initializeUhid();
        }
    }

    public synchronized void leave(WebSocket webSocket) {
        clients.remove(webSocket);
        Ln.i("Client left, remaining: " + clients.size());

        if (clients.isEmpty()) {
            // Don't stop immediately — start a grace period so a quick page refresh
            // can reconnect and find the encoder still warm (no cold-start delay).
            Ln.i("All clients disconnected — scheduling cleanup in " + CLEANUP_GRACE_MS + "ms");
            pendingCleanup = cleanupScheduler.schedule(() -> {
                synchronized (WebSocketConnection.this) {
                    if (clients.isEmpty()) {
                        Ln.i("Grace period expired with no reconnect — cleaning up");
                        cleanup();
                    }
                }
            }, CLEANUP_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    private void cleanup() {
        Ln.i("Cleaning up connection for display " + displayId);

        if (videoStreamer != null) {
            videoStreamer.stop();
            videoStreamer = null;
        }

        if (controller != null) {
            controller.stop();
            controller = null;
        }

        // Reset codec metadata cache so the next first client triggers a fresh build
        codecMetadataCache.clear();
        codecMetadataCached = false;
        Ln.i("🗑️  Codec metadata cache cleared (all clients disconnected)");

        WSServer.releaseConnectionForDisplay(displayId);
    }

    public void handleControlMessage(ControlMessage msg) {
        if (msg == null) {
            return;
        }

        int type = msg.getType();
        Ln.d("Processing control message type: " + type);

        // Handle control messages directly without Controller
        // This is because we don't use traditional ControlChannel in WebSocket mode
        switch (type) {
            case ControlMessage.TYPE_INJECT_TOUCH_EVENT:
                // Touch/Tap events

                // Post to main thread for Android input system
                WSServer.getMainHandler().post(() -> {
                    try {
                        Position pos = msg.getPosition();
                        Point point = pos.getPoint();
                        com.genymobile.scrcpy.device.Size virtualSize = pos.getScreenSize();

                        // Fix: ws-scrcpy sends pointerId=-1, but Android needs >= 0
                        long pointerId = msg.getPointerId();
                        if (pointerId < 0) {
                            pointerId = 0; // Default to first pointer for single touch
                        }

                        // CRITICAL FIX: Force inject to PHYSICAL display (0)
                        // Virtual displays on API < 29 don't support input events!
                        int targetDisplayId = 0;

                        // COORDINATE SCALING FIX:
                        // Client sends coordinates for virtual display (e.g., 320x720)
                        // But we inject to physical display (e.g., 1080x2340)
                        // Must scale coordinates!
                        com.genymobile.scrcpy.device.DisplayInfo physicalDisplayInfo = com.genymobile.scrcpy.wrappers.ServiceManager
                                .getDisplayManager().getDisplayInfo(0);
                        com.genymobile.scrcpy.device.Size physicalSize = physicalDisplayInfo.getSize();

                        float scaleX = (float) physicalSize.getWidth() / virtualSize.getWidth();
                        float scaleY = (float) physicalSize.getHeight() / virtualSize.getHeight();

                        int scaledX = Math.round(point.getX() * scaleX);
                        int scaledY = Math.round(point.getY() * scaleY);

                        boolean success = Device.injectMotionEvent(
                                msg.getAction(),
                                pointerId,
                                scaledX, // Use scaled coordinates!
                                scaledY, // Use scaled coordinates!
                                msg.getPressure(),
                                msg.getActionButton(),
                                msg.getButtons(),
                                targetDisplayId);
                        if (success) {
                            Ln.d("✅ Touch injected at scaled (" + scaledX + ", " + scaledY + ")");
                        } else {
                            Ln.w("⚠️  Failed to inject touch");
                        }
                    } catch (Exception e) {
                        Ln.e("❌ Error injecting touch", e);
                        e.printStackTrace();
                    }
                });
                break;

            case ControlMessage.TYPE_INJECT_KEYCODE:
                // Keyboard events
                Ln.d("⌨️  Keycode: action=" + msg.getAction() + ", keycode=" + msg.getKeycode());
                WSServer.getMainHandler().post(() -> {
                    try {
                        // Force to physical display for API < 29 compatibility
                        boolean success = Device.injectKeyEvent(
                                msg.getAction(),
                                msg.getKeycode(),
                                msg.getRepeat(),
                                msg.getMetaState(),
                                0, // Force to physical display
                                Device.INJECT_MODE_ASYNC);
                        if (success) {
                            Ln.d("✅ Keycode injected: " + msg.getKeycode());
                        } else {
                            Ln.w("⚠️  Failed to inject keycode");
                        }
                    } catch (Exception e) {
                        Ln.e("❌ Error injecting keycode", e);
                    }
                });
                break;

            case ControlMessage.TYPE_INJECT_SCROLL_EVENT:
                // Scroll events
                Ln.d("🖱️  Scroll: h=" + msg.getHScroll() + ", v=" + msg.getVScroll());
                // TODO: Implement scroll injection if needed
                break;

            case ControlMessage.TYPE_INJECT_TEXT:
                // Text injection - send each character as keystroke
                String text = msg.getText();
                Ln.i("📝 Injecting text: \"" + text + "\" (" + text.length() + " chars)");
                WSServer.getMainHandler().post(() -> {
                    try {
                        int successCount = injectTextAsKeystrokes(text);
                        Ln.i("✅ Text injection complete: " + successCount + "/" + text.length() + " chars");
                    } catch (Exception e) {
                        Ln.e("❌ Error injecting text", e);
                    }
                });
                break;

            case ControlMessage.TYPE_BACK_OR_SCREEN_ON:
                WSServer.getMainHandler().post(() -> {
                    try {
                        Device.pressReleaseKeycode(
                                android.view.KeyEvent.KEYCODE_BACK,
                                0, // Force to physical display
                                Device.INJECT_MODE_ASYNC);
                    } catch (Exception e) {
                        Ln.e("❌ Error injecting back", e);
                    }
                });
                break;

            default:
                Ln.d("Unhandled control message type: " + type);
        }
    }

    /**
     * Broadcast video frame to all connected clients
     * Also caches codec metadata (SPS/PPS) for new clients
     */
    public synchronized void broadcastVideoFrame(ByteBuffer frameData) {
        // Safely extract bytes regardless of buffer state
        final int remaining = frameData.remaining();
        if (remaining <= 0)
            return;
        final byte[] frameBytes = new byte[remaining];
        // Use duplicate so we don't alter the caller's buffer position
        frameData.duplicate().get(frameBytes);

        // 🎯 SMART CACHING - Simpan metadata codec (SPS/PPS) agar client baru
        // yang join belakangan bisa langsung decode tanpa layar hitam
        if (!codecMetadataCached) {
            if (shouldCacheAsCodecMetadata(frameBytes)) {
                cacheCodecMetadata(frameBytes);
            }
        }

        // Broadcast to all clients
        for (WebSocket client : clients) {
            if (client.isOpen()) {
                try {
                    client.send(frameBytes);
                } catch (Exception e) {
                    Ln.e("Failed to send video frame to client " +
                            client.getRemoteSocketAddress(), e);
                }
            }
        }
    }

    /**
     * Check if frame should be cached as codec metadata.
     *
     * Uses NAL type detection (reliable) instead of size threshold (fragile).
     * H.264: SPS=7, PPS=8
     * H.265: VPS=32, SPS=33, PPS=34
     * Only these NAL types are cached; all others (I/P/B frames) are not.
     */
    private boolean shouldCacheAsCodecMetadata(byte[] frameBytes) {
        if (codecMetadataCache.size() >= 10) {
            codecMetadataCached = true;
            return false;
        }

        // Need at least start code (3-4 bytes) + NAL header (1 byte)
        if (frameBytes.length < 5)
            return false;

        // Find NAL header: skip past start code (00 00 00 01 or 00 00 01)
        int nalOffset = -1;
        if (frameBytes[0] == 0 && frameBytes[1] == 0 && frameBytes[2] == 0 && frameBytes[3] == 1) {
            nalOffset = 4; // 4-byte start code
        } else if (frameBytes[0] == 0 && frameBytes[1] == 0 && frameBytes[2] == 1) {
            nalOffset = 3; // 3-byte start code
        }

        if (nalOffset < 0 || nalOffset >= frameBytes.length)
            return false;

        int nalHeader = frameBytes[nalOffset] & 0xFF;

        // H.264: nal_unit_type occupies bits [4:0]
        int h264Type = nalHeader & 0x1F;
        // H.265: nal_unit_type occupies bits [14:9] of the 2-byte NAL header —
        // simplified: bits [6:1] of first byte
        int h265Type = (nalHeader >> 1) & 0x3F;

        boolean isH264Param = (h264Type == 7 || h264Type == 8); // SPS, PPS
        boolean isH265Param = (h265Type == 32 || h265Type == 33 || h265Type == 34); // VPS, SPS, PPS

        if (isH264Param || isH265Param) {
            Ln.d("📐 Codec param NAL detected (h264Type=" + h264Type + ", h265Type=" + h265Type +
                    ", size=" + frameBytes.length + " bytes) – caching");
            return true;
        }

        // Not a codec parameter NAL — seal cache if we already have something
        if (codecMetadataCache.size() > 0) {
            codecMetadataCached = true;
        }
        return false;
    }

    /**
     * Cache codec metadata frame for replay to new clients
     * Only called for small frames (< 500 bytes) - SPS/PPS/VPS only, NOT video
     * frames
     */
    private void cacheCodecMetadata(byte[] frameBytes) {
        byte[] cached = new byte[frameBytes.length];
        System.arraycopy(frameBytes, 0, cached, 0, frameBytes.length);
        codecMetadataCache.add(cached);
        Ln.d("Cached codec metadata frame #" + codecMetadataCache.size() + " (" + frameBytes.length + " bytes)");
    }

    /**
     * Send cached codec metadata to a specific client (for late joiners)
     */
    private void sendCodecMetadataToClient(WebSocket client) {
        if (!client.isOpen()) {
            return;
        }

        Ln.i("🔄 Replaying " + codecMetadataCache.size() + " cached frames to new client: " +
                client.getRemoteSocketAddress());

        for (int i = 0; i < codecMetadataCache.size(); i++) {
            try {
                byte[] frame = codecMetadataCache.get(i);
                client.send(frame);
                Ln.d("   ✅ Sent cached frame #" + (i + 1) + " (" + frame.length + " bytes)");
            } catch (Exception e) {
                Ln.e("Failed to send cached frame to client", e);
                break;
            }
        }

        Ln.i("✅ Codec metadata replay complete! Client should see video now.");
    }

    private static String getInitialInfoString() {
        // Create initial device info message as JSON string
        StringBuilder info = new StringBuilder();
        info.append("{\n");
        info.append("  \"type\": \"deviceInfo\",\n");
        info.append("  \"device\": {\n");
        info.append("    \"manufacturer\": \"").append(Build.MANUFACTURER).append("\",\n");
        info.append("    \"model\": \"").append(Build.MODEL).append("\",\n");
        info.append("    \"androidVersion\": \"").append(Build.VERSION.RELEASE).append("\",\n");
        info.append("    \"sdk\": ").append(Build.VERSION.SDK_INT).append("\n");
        info.append("  }\n");
        info.append("}");
        return info.toString();
    }

    private void sendInitialInfo(WebSocket webSocket) {
        if (webSocket != null && webSocket.isOpen()) {
            try {
                String deviceInfo = getInitialInfoString();
                // Send as TEXT WebSocket frame, not binary!
                webSocket.send(deviceInfo);
                Ln.i("Sent device info to client: " + webSocket.getRemoteSocketAddress());
            } catch (Exception e) {
                Ln.e("Failed to send device info", e);
            }
        }
    }

    public int getDisplayId() {
        return displayId;
    }

    public Options getOptions() {
        return options;
    }

    public Controller getController() {
        return controller;
    }

    /**
     * Initialize UHID devices for keyboard and mouse emulation
     */
    private void initializeUhid() {
        Ln.i("Initializing UHID devices...");

        // Prepare Looper for this thread if it doesn't have one
        // This is necessary because FakeContext/Workarounds initialization
        // creates an ActivityThread which requires a Handler
        if (android.os.Looper.myLooper() == null) {
            Ln.d("Preparing Looper for WebSocketWorker thread...");
            android.os.Looper.prepare();
        }

        // Initialize Workarounds AFTER Looper is prepared
        // This ensures ActivityThread creation happens with proper Looper context
        try {
            Ln.d("Initializing Workarounds...");
            com.genymobile.scrcpy.Workarounds.apply();
            Ln.d("Workarounds initialized successfully");
        } catch (Throwable e) {
            Ln.w("Workarounds initialization failed, continuing anyway: " + e.getMessage());
        }

        try {
            // For WebSocket mode, we don't need DeviceMessageSender (no HID output back to
            // client)
            // IMPORTANT: Pass null for displayUniqueId to avoid Android 15 InputPort
            // restrictions
            // This makes UHID device work like pre-Android 15 (trusted by default)
            uhidManager = new UhidManager(null, null);

            if (options.getKeyboardUhid()) {
                // Standard USB HID keyboard descriptor (NO Report ID - like scrcpy)
                byte[] keyboardDesc = new byte[] {
                        (byte) 0x05, (byte) 0x01, // Usage Page (Generic Desktop)
                        (byte) 0x09, (byte) 0x06, // Usage (Keyboard)
                        (byte) 0xA1, (byte) 0x01, // Collection (Application)
                        (byte) 0x05, (byte) 0x07, // Usage Page (Key Codes)
                        (byte) 0x19, (byte) 0xE0, // Usage Minimum (Left Ctrl)
                        (byte) 0x29, (byte) 0xE7, // Usage Maximum (Right GUI)
                        (byte) 0x15, (byte) 0x00, // Logical Minimum (0)
                        (byte) 0x25, (byte) 0x01, // Logical Maximum (1)
                        (byte) 0x75, (byte) 0x01, // Report Size (1)
                        (byte) 0x95, (byte) 0x08, // Report Count (8)
                        (byte) 0x81, (byte) 0x02, // Input (Data, Variable, Absolute)
                        (byte) 0x95, (byte) 0x01, // Report Count (1)
                        (byte) 0x75, (byte) 0x08, // Report Size (8)
                        (byte) 0x81, (byte) 0x01, // Input (Constant)
                        (byte) 0x95, (byte) 0x05, // Report Count (5)
                        (byte) 0x75, (byte) 0x01, // Report Size (1)
                        (byte) 0x05, (byte) 0x08, // Usage Page (LEDs)
                        (byte) 0x19, (byte) 0x01, // Usage Minimum (1)
                        (byte) 0x29, (byte) 0x05, // Usage Maximum (5)
                        (byte) 0x91, (byte) 0x02, // Output (Data, Variable, Absolute)
                        (byte) 0x95, (byte) 0x01, // Report Count (1)
                        (byte) 0x75, (byte) 0x03, // Report Size (3)
                        (byte) 0x91, (byte) 0x01, // Output (Constant)
                        (byte) 0x95, (byte) 0x06, // Report Count (6)
                        (byte) 0x75, (byte) 0x08, // Report Size (8)
                        (byte) 0x15, (byte) 0x00, // Logical Minimum (0)
                        (byte) 0x25, (byte) 0x65, // Logical Maximum (101)
                        (byte) 0x05, (byte) 0x07, // Usage Page (Key Codes)
                        (byte) 0x19, (byte) 0x00, // Usage Minimum (0)
                        (byte) 0x29, (byte) 0x65, // Usage Maximum (101)
                        (byte) 0x81, (byte) 0x00, // Input (Data, Array)
                        (byte) 0xC0 // End Collection
                };

                uhidManager.open(UHID_KEYBOARD_ID, 0x18d1, 0x5aff,
                        "scrcpy-keyboard", keyboardDesc);
                Ln.i("✅ UHID keyboard initialized");
            }

            if (options.getMouseUhid()) {
                // Standard USB HID mouse descriptor
                byte[] mouseDesc = new byte[] {
                        (byte) 0x05, (byte) 0x01, // Usage Page (Generic Desktop)
                        (byte) 0x09, (byte) 0x02, // Usage (Mouse)
                        (byte) 0xA1, (byte) 0x01, // Collection (Application)
                        (byte) 0x09, (byte) 0x01, // Usage (Pointer)
                        (byte) 0xA1, (byte) 0x00, // Collection (Physical)
                        (byte) 0x85, (byte) 0x01, // Report ID (1)
                        (byte) 0x05, (byte) 0x09, // Usage Page (Buttons)
                        (byte) 0x19, (byte) 0x01, // Usage Minimum (1)
                        (byte) 0x29, (byte) 0x03, // Usage Maximum (3)
                        (byte) 0x15, (byte) 0x00, // Logical Minimum (0)
                        (byte) 0x25, (byte) 0x01, // Logical Maximum (1)
                        (byte) 0x75, (byte) 0x01, // Report Size (1)
                        (byte) 0x95, (byte) 0x03, // Report Count (3)
                        (byte) 0x81, (byte) 0x02, // Input (Data, Variable, Absolute)
                        (byte) 0x75, (byte) 0x05, // Report Size (5)
                        (byte) 0x95, (byte) 0x01, // Report Count (1)
                        (byte) 0x81, (byte) 0x03, // Input (Constant, Variable, Absolute)
                        (byte) 0x05, (byte) 0x01, // Usage Page (Generic Desktop)
                        (byte) 0x09, (byte) 0x30, // Usage (X)
                        (byte) 0x09, (byte) 0x31, // Usage (Y)
                        (byte) 0x15, (byte) 0x81, // Logical Minimum (-127)
                        (byte) 0x25, (byte) 0x7F, // Logical Maximum (127)
                        (byte) 0x75, (byte) 0x08, // Report Size (8)
                        (byte) 0x95, (byte) 0x02, // Report Count (2)
                        (byte) 0x81, (byte) 0x06, // Input (Data, Variable, Relative)
                        (byte) 0xC0, // End Collection
                        (byte) 0xC0 // End Collection
                };

                uhidManager.open(UHID_MOUSE_ID, 0x18d1, 0x5aff,
                        "scrcpy-mouse", mouseDesc);
                Ln.i("✅ UHID mouse initialized");
            }

        } catch (IOException e) {
            Ln.e("Failed to initialize UHID", e);
            uhidManager = null;
        }
    }

    /**
     * Handle control message from WebSocket client
     * 
     * @param data Binary message data
     */
    public void handleControlMessage(byte[] data) {
        if (data.length < 1) {
            return;
        }

        int type = data[0] & 0xFF;

        switch (type) {
            case TYPE_UHID_KEYBOARD:
                if (uhidManager != null && options.getKeyboardUhid() && data.length >= 9) {
                    try {
                        byte[] report = new byte[8];
                        System.arraycopy(data, 1, report, 0, 8);
                        uhidManager.writeInput(UHID_KEYBOARD_ID, report);
                    } catch (IOException e) {
                        Ln.e("Failed to send UHID keyboard input", e);
                    }
                }
                break;

            case TYPE_UHID_MOUSE:
                if (uhidManager != null && options.getMouseUhid() && data.length >= 5) {
                    try {
                        // Extract mouse report (4 bytes after type byte)
                        byte[] report = new byte[4];
                        System.arraycopy(data, 1, report, 0, 4);
                        uhidManager.writeInput(UHID_MOUSE_ID, report);
                        Ln.d("UHID mouse input sent");
                    } catch (IOException e) {
                        Ln.e("Failed to send UHID mouse input", e);
                    }
                }
                break;

            case TYPE_INJECT_KEYCODE:
                // Android Input keyboard (works without root!)
                // Format: [type:1][action:1][keycode:4][metaState:4]
                if (data.length >= 10) {
                    final int action = data[1] & 0xFF;
                    final int keycode = ((data[2] & 0xFF) << 24) |
                            ((data[3] & 0xFF) << 16) |
                            ((data[4] & 0xFF) << 8) |
                            (data[5] & 0xFF);
                    final int metaState = ((data[6] & 0xFF) << 24) |
                            ((data[7] & 0xFF) << 16) |
                            ((data[8] & 0xFF) << 8) |
                            (data[9] & 0xFF);

                    // Post to main thread (required for Android input system)
                    WSServer.getMainHandler().post(() -> {
                        try {
                            boolean success = Device.injectKeyEvent(action, keycode, 0, metaState, 0, 0); // Force to
                                                                                                          // display 0
                            if (success) {
                                Ln.d("✅ Injected keycode: " + keycode + ", action: " + action);
                            } else {
                                Ln.w("Failed to inject keycode: " + keycode);
                            }
                        } catch (Exception e) {
                            Ln.e("Failed to inject keycode", e);
                        }
                    });
                }
                break;

            case TYPE_INJECT_TOUCH:
                // Touch/Tap events (works without root!)
                // Format:
                // [type:1][action:4][pointerId:8][x:4][y:4][pressure:4][actionButton:4][buttons:4]
                // Total: 1 + 4 + 8 + 4 + 4 + 4 + 4 + 4 = 33 bytes
                if (data.length >= 33) {
                    final int action = ((data[1] & 0xFF) << 24) |
                            ((data[2] & 0xFF) << 16) |
                            ((data[3] & 0xFF) << 8) |
                            (data[4] & 0xFF);

                    final long pointerId = ((long) (data[5] & 0xFF) << 56) |
                            ((long) (data[6] & 0xFF) << 48) |
                            ((long) (data[7] & 0xFF) << 40) |
                            ((long) (data[8] & 0xFF) << 32) |
                            ((long) (data[9] & 0xFF) << 24) |
                            ((long) (data[10] & 0xFF) << 16) |
                            ((long) (data[11] & 0xFF) << 8) |
                            (long) (data[12] & 0xFF);

                    final int x = ((data[13] & 0xFF) << 24) |
                            ((data[14] & 0xFF) << 16) |
                            ((data[15] & 0xFF) << 8) |
                            (data[16] & 0xFF);

                    final int y = ((data[17] & 0xFF) << 24) |
                            ((data[18] & 0xFF) << 16) |
                            ((data[19] & 0xFF) << 8) |
                            (data[20] & 0xFF);

                    final int pressureInt = ((data[21] & 0xFF) << 24) |
                            ((data[22] & 0xFF) << 16) |
                            ((data[23] & 0xFF) << 8) |
                            (data[24] & 0xFF);
                    final float pressure = Float.intBitsToFloat(pressureInt);

                    final int actionButton = ((data[25] & 0xFF) << 24) |
                            ((data[26] & 0xFF) << 16) |
                            ((data[27] & 0xFF) << 8) |
                            (data[28] & 0xFF);

                    final int buttons = ((data[29] & 0xFF) << 24) |
                            ((data[30] & 0xFF) << 16) |
                            ((data[31] & 0xFF) << 8) |
                            (data[32] & 0xFF);

                    // Post to main thread (required for Android input system)
                    WSServer.getMainHandler().post(() -> {
                        try {
                            Device.injectMotionEvent(action, pointerId, x, y,
                                    pressure, actionButton, buttons, displayId);
                        } catch (Exception e) {
                            Ln.e("Exception injecting touch", e);
                        }
                    });
                } else {
                    Ln.w("⚠️  Touch message too short: " + data.length + " bytes (need 33)");
                }
                break;

            default:
                Ln.w("Unknown control message type: " + type);
        }
    }

    /**
     * Inject text by converting each character to keystrokes
     * Uses Android KeyCharacterMap to map characters to key events
     * 
     * @param text The text to inject
     * @return Number of successfully injected characters
     */
    private int injectTextAsKeystrokes(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int successCount = 0;
        android.view.KeyCharacterMap keyCharMap = android.view.KeyCharacterMap.load(
                android.view.KeyCharacterMap.VIRTUAL_KEYBOARD);

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            // Get key events for this character
            android.view.KeyEvent[] events = keyCharMap.getEvents(new char[] { c });

            if (events != null && events.length > 0) {
                // Inject all events for this character
                boolean allSuccess = true;
                for (android.view.KeyEvent event : events) {
                    boolean success = Device.injectEvent(
                            event,
                            0, // Force to physical display
                            Device.INJECT_MODE_ASYNC);
                    if (!success) {
                        allSuccess = false;
                        Ln.w("⚠️  Failed to inject KeyEvent for char: '" + c + "' (U+" +
                                String.format("%04x", (int) c) + ")");
                        break;
                    }
                }
                if (allSuccess) {
                    successCount++;
                    Ln.d("✅ Injected char: '" + c + "'");
                }
            } else {
                // Fallback: Try to inject character directly via text input
                // This works for some characters that don't have direct key mappings
                Ln.w("⚠️  No KeyEvent mapping for char: '" + c + "' (U+" +
                        String.format("%04x", (int) c) + ") - skipping");
            }
        }

        return successCount;
    }
}
