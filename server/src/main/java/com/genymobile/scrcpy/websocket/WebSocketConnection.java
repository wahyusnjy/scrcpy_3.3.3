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

    // UHID support
    private UhidManager uhidManager;
    private static final int UHID_KEYBOARD_ID = 1;
    private static final int UHID_MOUSE_ID = 2;

    // Control message types
    public static final int TYPE_UHID_KEYBOARD = 100; // UHID keyboard (requires root)
    public static final int TYPE_UHID_MOUSE = 101; // UHID mouse (requires root)
    public static final int TYPE_INJECT_KEYCODE = 102; // Android Input keyboard (no root)
    public static final int TYPE_INJECT_TOUCH = 103; // Touch/Tap events (no root)

    public WebSocketConnection(Options options, int displayId, WSServer wsServer) {
        this.options = options;
        this.displayId = displayId;
        this.wsServer = wsServer;
    }

    public synchronized void join(WebSocket webSocket) {
        Ln.i("Client joining stream for display " + displayId);
        clients.add(webSocket);

        // Multi-client logging
        Ln.i("👥 Total clients connected: " + clients.size());
        for (WebSocket client : clients) {
            Ln.d("   - Client: " + client.getRemoteSocketAddress());
        }

        Ln.d("Options.getVideo(): " + options.getVideo());
        Ln.d("Current videoStreamer: " + (videoStreamer == null ? "null" : "exists"));

        // Send device info to this client
        sendInitialInfo(webSocket);

        // Note: Controller initialization removed for WebSocket mode
        // In WebSocket, we handle control messages differently

        // Initialize video streamer if not already started
        if (videoStreamer == null && options.getVideo()) {
            Ln.i("Starting video streamer for display " + displayId);
            try {
                Ln.d("Creating WebSocketVideoStreamer instance...");
                videoStreamer = new WebSocketVideoStreamer(this, options);
                Ln.d("Calling videoStreamer.start()...");
                videoStreamer.start();
                Ln.i("✅ Video streamer started successfully!");
            } catch (Exception e) {
                Ln.e("❌ Failed to start video streamer", e);
                e.printStackTrace();
            }
        } else if (videoStreamer != null) {
            Ln.i("Video streamer already running for display " + displayId);
        } else {
            Ln.w("Video is disabled in options!");
        }

        // Initialize UHID if enabled and not already initialized
        if (uhidManager == null && (options.getKeyboardUhid() || options.getMouseUhid())) {
            initializeUhid();
        }
    }

    public synchronized void leave(WebSocket webSocket) {
        Ln.i("Client leaving stream for display " + displayId);
        clients.remove(webSocket);

        // If no more clients, cleanup
        if (clients.isEmpty()) {
            cleanup();
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

                        Ln.i("📱 Touch: action=" + msg.getAction() +
                                ", virtualPos=(" + point.getX() + "," + point.getY() + ")" +
                                ", virtualSize=" + virtualSize +
                                ", physicalSize=" + physicalSize +
                                ", scale=(" + String.format("%.2f", scaleX) + "," + String.format("%.2f", scaleY) + ")"
                                +
                                ", scaledPos=(" + scaledX + "," + scaledY + ")");

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

    public synchronized void broadcastVideoFrame(ByteBuffer frameData) {
        for (WebSocket client : clients) {
            if (client.isOpen()) {
                try {
                    client.send(frameData.array());
                } catch (Exception e) {
                    Ln.e("Failed to send video frame to client " +
                            client.getRemoteSocketAddress(), e);
                }
            }
        }
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
                Ln.i("📨 Received UHID keyboard message (Type 100), length=" + data.length);
                if (uhidManager != null && options.getKeyboardUhid() && data.length >= 9) {
                    try {
                        // Extract keyboard report (8 bytes after type byte)
                        byte[] report = new byte[8];
                        System.arraycopy(data, 1, report, 0, 8);

                        // Log the HID report details
                        Ln.i(String.format("  Modifiers=0x%02X, Keycode=0x%02X",
                                report[0] & 0xFF, report[2] & 0xFF));

                        uhidManager.writeInput(UHID_KEYBOARD_ID, report);
                        Ln.i("✅ UHID keyboard input sent successfully");
                    } catch (IOException e) {
                        Ln.e("❌ Failed to send UHID keyboard input", e);
                    }
                } else {
                    if (uhidManager == null) {
                        Ln.w("⚠️  UHID manager is NULL - UHID not initialized!");
                    }
                    if (!options.getKeyboardUhid()) {
                        Ln.w("⚠️  keyboardUhid option is FALSE!");
                    }
                    if (data.length < 9) {
                        Ln.w("⚠️  Message too short: " + data.length + " bytes (need 9)");
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

                    // Log for debugging
                    Ln.i("📱 Touch event: action=" + action + ", x=" + x + ", y=" + y +
                            ", pressure=" + pressure + ", pointerId=" + pointerId);

                    // Post to main thread (required for Android input system)
                    WSServer.getMainHandler().post(() -> {
                        try {
                            boolean success = Device.injectMotionEvent(action, pointerId, x, y,
                                    pressure, actionButton, buttons, displayId);
                            if (success) {
                                Ln.d("✅ Touch injected: (" + x + ", " + y + ")");
                            } else {
                                Ln.w("⚠️  Failed to inject touch at (" + x + ", " + y + ")");
                            }
                        } catch (Exception e) {
                            Ln.e("❌ Exception injecting touch", e);
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
}
