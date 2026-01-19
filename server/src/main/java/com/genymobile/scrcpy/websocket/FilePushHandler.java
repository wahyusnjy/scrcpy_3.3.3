package com.genymobile.scrcpy.websocket;

import org.java_websocket.WebSocket;

import com.genymobile.scrcpy.control.ControlMessage;
import com.genymobile.scrcpy.util.Ln;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles file push operations via WebSocket
 */
public class FilePushHandler {
    private static final Map<WebSocket, FilePushTask> activeTasks = new HashMap<>();
    private static final String TEMP_DIR = "/data/local/tmp/";

    public static void handlePush(WebSocket webSocket, ControlMessage message) {
        Ln.i("File push request received");

        // Cancel any existing push task for this connection
        cancelAllForConnection(webSocket);

        // Create new push task
        FilePushTask task = new FilePushTask(webSocket, message);
        activeTasks.put(webSocket, task);

        // Start push in background
        new Thread(task, "FilePush-" + webSocket.getRemoteSocketAddress()).start();
    }

    public static void cancelAllForConnection(WebSocket webSocket) {
        FilePushTask task = activeTasks.remove(webSocket);
        if (task != null) {
            task.cancel();
        }
    }

    private static class FilePushTask implements Runnable {
        private final WebSocket webSocket;
        private final ControlMessage message;
        private volatile boolean cancelled = false;

        FilePushTask(WebSocket webSocket, ControlMessage message) {
            this.webSocket = webSocket;
            this.message = message;
        }

        @Override
        public void run() {
            try {
                if (cancelled)
                    return;

                // Get file info from control message
                String fileName = "pushed_file"; // TODO: Extract from message
                byte[] fileData = new byte[0]; // TODO: Extract from message

                // Create temp file
                File tempFile = new File(TEMP_DIR, fileName);

                // Write file data
                try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                    fos.write(fileData);
                    fos.flush();
                }

                if (cancelled) {
                    tempFile.delete();
                    return;
                }

                Ln.i("File pushed successfully: " + tempFile.getAbsolutePath());

                // Send success response
                sendResponse(true, "File pushed successfully");

            } catch (IOException e) {
                Ln.e("File push failed", e);
                sendResponse(false, "File push failed: " + e.getMessage());
            } finally {
                activeTasks.remove(webSocket);
            }
        }

        void cancel() {
            cancelled = true;
        }

        private void sendResponse(boolean success, String message) {
            if (webSocket != null && webSocket.isOpen()) {
                try {
                    String response = String.format(
                            "{\"type\":\"filePushResponse\",\"success\":%b,\"message\":\"%s\"}",
                            success, message);
                    webSocket.send(response);
                } catch (Exception e) {
                    Ln.w("Failed to send file push response", e);
                }
            }
        }
    }
}
