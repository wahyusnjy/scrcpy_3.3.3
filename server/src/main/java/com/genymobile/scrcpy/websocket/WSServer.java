package com.genymobile.scrcpy.websocket;

import org.java_websocket.WebSocket;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.control.ControlMessage;
import com.genymobile.scrcpy.control.ControlMessageReader;
import com.genymobile.scrcpy.util.Ln;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;

/**
 * WebSocket Server for scrcpy v3.3.3
 * Based on NetrisTV implementation, ported to latest scrcpy features
 */
public class WSServer extends WebSocketServer {
    private static final String PID_FILE_PATH = "/data/local/tmp/ws_scrcpy.pid";

    public static final class SocketInfo {
        private static final HashSet<Short> INSTANCES_BY_ID = new HashSet<>();
        private final short id;
        private WebSocketConnection connection;

        SocketInfo(short id) {
            this.id = id;
            INSTANCES_BY_ID.add(id);
        }

        public static short getNextClientId() {
            short nextClientId = 0;
            while (INSTANCES_BY_ID.contains(++nextClientId)) {
                if (nextClientId == Short.MAX_VALUE) {
                    return -1;
                }
            }
            return nextClientId;
        }

        public short getId() {
            return id;
        }

        public WebSocketConnection getConnection() {
            return this.connection;
        }

        public void setConnection(WebSocketConnection connection) {
            this.connection = connection;
        }

        public void release() {
            INSTANCES_BY_ID.remove(id);
        }
    }

    private final Options options;
    private static final HashMap<Integer, WebSocketConnection> STREAM_BY_DISPLAY_ID = new HashMap<>();

    // Handler for posting keyboard events to main thread (required for Android
    // input)
    private static final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    public WSServer(Options options) {
        super(new InetSocketAddress(
                options.getWSListenOnAllInterfaces() ? "0.0.0.0" : "127.0.0.1",
                options.getWSPort()));
        this.options = options;
        unlinkPidFile();
    }

    public static android.os.Handler getMainHandler() {
        return mainHandler;
    }

    @Override
    public void onOpen(WebSocket webSocket, ClientHandshake handshake) {
        if (webSocket.isOpen()) {
            short clientId = SocketInfo.getNextClientId();
            if (clientId == -1) {
                Ln.w("Max clients reached, rejecting connection");
                webSocket.close(CloseFrame.TRY_AGAIN_LATER);
                return;
            }
            SocketInfo info = new SocketInfo(clientId);
            webSocket.setAttachment(info);

            Ln.i("WebSocket client #" + clientId + " connected from: " +
                    webSocket.getRemoteSocketAddress());

            // Automatically join stream for default display (0)
            joinStreamForDisplayId(webSocket, info, 0);
        }
    }

    @Override
    public void onClose(WebSocket webSocket, int code, String reason, boolean remote) {
        String address = webSocket.getRemoteSocketAddress() != null ? webSocket.getRemoteSocketAddress().toString()
                : "unknown";
        Ln.i("WebSocket client disconnected: " + address + " (reason: " + reason + ")");

        // Clean up file push handlers
        FilePushHandler.cancelAllForConnection(webSocket);

        SocketInfo socketInfo = webSocket.getAttachment();
        if (socketInfo != null) {
            WebSocketConnection connection = socketInfo.getConnection();
            if (connection != null) {
                connection.leave(webSocket);
            }
            socketInfo.release();
        }
    }

    @Override
    public void onMessage(WebSocket webSocket, String message) {
        String address = webSocket.getRemoteSocketAddress() != null
                ? webSocket.getRemoteSocketAddress().getAddress().getHostAddress()
                : "unknown";
        Ln.d("WebSocket text message from " + address + ": \"" + message + "\"");
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteBuffer message) {
        SocketInfo socketInfo = webSocket.getAttachment();
        if (socketInfo == null) {
            Ln.e("No SocketInfo attached to WebSocket connection");
            return;
        }

        WebSocketConnection connection = socketInfo.getConnection();
        String address = webSocket.getRemoteSocketAddress() != null
                ? webSocket.getRemoteSocketAddress().getAddress().getHostAddress()
                : "unknown";

        // Parse control message using ControlMessageReader
        try {
            byte[] bytes = new byte[message.remaining()];
            message.get(bytes);

            // Check if it's a direct binary message (UHID or Android Input)
            if (bytes.length > 0) {
                int type = bytes[0] & 0xFF;
                if (type == WebSocketConnection.TYPE_UHID_KEYBOARD ||
                        type == WebSocketConnection.TYPE_UHID_MOUSE ||
                        type == WebSocketConnection.TYPE_INJECT_KEYCODE) {
                    // Handle direct binary message
                    if (connection != null) {
                        connection.handleControlMessage(bytes);
                    }
                    return;
                }
            }

            // Otherwise, parse as standard control message
            ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
            ControlMessageReader reader = new ControlMessageReader(bais);
            ControlMessage controlMessage = reader.read();

            if (controlMessage != null) {
                // Handle different message types
                int type = controlMessage.getType();

                // Forward to connection handler
                if (connection != null) {
                    Ln.d("Received control message type: " + type);
                    connection.handleControlMessage(controlMessage);
                } else {
                    Ln.w("Control message received but no active connection");
                    // Auto-join to default display
                    joinStreamForDisplayId(webSocket, socketInfo, 0);
                }
            }
        } catch (Exception e) {
            Ln.e("Failed to parse control message from " + address, e);
        }
    }

    @Override
    public void onError(WebSocket webSocket, Exception ex) {
        if (webSocket != null) {
            String address = webSocket.getRemoteSocketAddress() != null ? webSocket.getRemoteSocketAddress().toString()
                    : "unknown";
            Ln.e("WebSocket error for client " + address, ex);
            FilePushHandler.cancelAllForConnection(webSocket);
        } else {
            Ln.e("WebSocket server error", ex);
        }

        // If bind error, exit
        if (ex instanceof BindException) {
            Ln.e("Failed to bind WebSocket server port. Exiting.");
            System.exit(1);
        }
    }

    @Override
    public void onStart() {
        Ln.i("WebSocket server started on " + this.getAddress().toString());
        this.setConnectionLostTimeout(0);
        this.setConnectionLostTimeout(100);
        writePidFile();
    }

    private void joinStreamForDisplayId(
            WebSocket webSocket,
            SocketInfo socketInfo,
            int displayId) {

        WebSocketConnection connection = STREAM_BY_DISPLAY_ID.get(displayId);
        if (connection == null) {
            // Create new connection for this display
            connection = new WebSocketConnection(options, displayId, this);
            STREAM_BY_DISPLAY_ID.put(displayId, connection);
        }

        socketInfo.setConnection(connection);
        connection.join(webSocket);
    }

    private static void unlinkPidFile() {
        try {
            File pidFile = new File(PID_FILE_PATH);
            if (pidFile.exists()) {
                if (!pidFile.delete()) {
                    Ln.w("Failed to delete PID file: " + PID_FILE_PATH);
                }
            }
        } catch (Exception e) {
            Ln.e("Error unlinking PID file", e);
        }
    }

    private static void writePidFile() {
        File file = new File(PID_FILE_PATH);
        try (FileOutputStream stream = new FileOutputStream(file, false)) {
            stream.write(Integer.toString(android.os.Process.myPid())
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Ln.e("Failed to write PID file", e);
        }
    }

    public static WebSocketConnection getConnectionForDisplay(int displayId) {
        return STREAM_BY_DISPLAY_ID.get(displayId);
    }

    public static void releaseConnectionForDisplay(int displayId) {
        STREAM_BY_DISPLAY_ID.remove(displayId);
    }

    public Options getOptions() {
        return options;
    }
}
