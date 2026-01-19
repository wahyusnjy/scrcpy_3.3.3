# 🌐 WebSocket Implementation Plan untuk Scrcpy

> **Inspired by:** NetrisTV's [ws-scrcpy](https://github.com/NetrisTV/ws-scrcpy)  
> **Goal:** Menambahkan WebSocket support ke scrcpy untuk kontrol dan streaming melalui browser

---

## 📚 Table of Contents

1. [Arsitektur Overview](#arsitektur-overview)
2. [Komponen yang Perlu Dimodifikasi](#komponen-yang-perlu-dimodifikasi)
3. [Roadmap Implementasi](#roadmap-implementasi)
4. [Detail Implementasi Per Layer](#detail-implementasi-per-layer)
5. [Teknologi Stack](#teknologi-stack)
6. [Referensi dan Resources](#referensi-dan-resources)

---

## 🏗️ Arsitektur Overview

### **Current Architecture (scrcpy)**
```
┌─────────────────┐         USB/TCP Socket         ┌──────────────────┐
│                 │◄───────────────────────────────►│                  │
│  Desktop Client │         Video Stream (H264)     │  Android Device  │
│   (C/SDL2)      │         Control Messages        │  (scrcpy-server) │
│                 │         Audio Stream            │                  │
└─────────────────┘                                 └──────────────────┘
```

### **Target Architecture (ws-scrcpy style)**
```
┌──────────────┐    WebSocket    ┌──────────────┐    ADB Tunnel    ┌──────────────────┐
│              │◄───────────────►│              │◄─────────────────►│                  │
│  Web Browser │   WS Protocol   │  Node.js     │   Socket/TCP     │  Android Device  │
│   Client     │   H264 Stream   │  Proxy       │   H264 Stream    │  (Modified       │
│   (HTML5)    │   Control Msgs  │  Server      │   Control Msgs   │   scrcpy-server) │
│              │                 │              │                  │                  │
└──────────────┘                 └──────────────┘                  └──────────────────┘
       ▼                                                                     ▲
   ┌────────────┐                                                           │
   │  Decoders  │                                                           │
   │  - MSE     │                                              ┌────────────┴─────────┐
   │  - Broadway│                                              │  WebSocket Server    │
   │  - TinyH264│                                              │  (Embedded in JAR)   │
   │  - WebCodecs                                              └──────────────────────┘
   └────────────┘
```

---

## 🔧 Komponen yang Perlu Dimodifikasi

### **1. Server Side (Android - Java)**

#### Files yang perlu dimodifikasi/dibuat:
```
server/src/main/java/com/genymobile/scrcpy/
├── Server.java                    [MODIFY] - Add WebSocket mode detection
├── DesktopConnection.java         [MODIFY] - Support WebSocket connection
├── websocket/                     [NEW DIRECTORY]
│   ├── WebSocketServer.java      [NEW] - WebSocket server implementation
│   ├── WebSocketHandler.java     [NEW] - Handle WS messages
│   ├── WebSocketVideoStreamer.java [NEW] - Stream H264 via WebSocket
│   └── WebSocketControlReceiver.java [NEW] - Receive control commands
└── util/
    └── WebSocketUtils.java        [NEW] - WebSocket utilities
```

**Dependencies yang perlu ditambahkan:**
- `build.gradle`: Tambahkan WebSocket library (Java-WebSocket atau TooTallNate's WebSocket)

```gradle
dependencies {
    implementation 'org.java-websocket:Java-WebSocket:1.5.3'
    testImplementation 'junit:junit:4.13.2'
}
```

---

### **2. Proxy Server (Node.js)**

Buat project baru terpisah untuk WebSocket proxy:

```
ws-scrcpy-proxy/
├── package.json
├── src/
│   ├── server.js              [NEW] - Main Node.js server
│   ├── adb-manager.js         [NEW] - Manage ADB connections
│   ├── websocket-handler.js   [NEW] - Handle browser WebSocket
│   ├── device-connector.js    [NEW] - Connect to Android device
│   └── stream-processor.js    [NEW] - Process H264 stream
├── public/
│   ├── index.html             [NEW] - Web client interface
│   ├── js/
│   │   ├── main.js            [NEW] - Client logic
│   │   ├── video-decoder.js   [NEW] - H264 decoder wrapper
│   │   ├── control-handler.js [NEW] - Handle user input
│   │   └── decoders/
│   │       ├── mse-player.js  [NEW] - Media Source Extension player
│   │       ├── broadway.js    [NEW] - Broadway decoder
│   │       └── tinyh264.js    [NEW] - TinyH264 decoder
│   └── css/
│       └── styles.css         [NEW] - UI styles
└── config/
    └── default.json           [NEW] - Configuration
```

---

### **3. Web Client (Browser)**

Frontend stack yang diperlukan:
- **HTML5 Video** - Untuk playback
- **WebSocket API** - Untuk komunikasi real-time
- **Media Source Extensions (MSE)** - Untuk H264 decoding
- **WebAssembly** - Untuk software decoders (Broadway, TinyH264)
- **WebCodecs API** - Untuk hardware-accelerated decoding (modern browsers)

---

## 🗺️ Roadmap Implementasi

### **Phase 1: Research & Setup (1-2 weeks)**
- [x] Study ws-scrcpy architecture
- [ ] Setup development environment
- [ ] Analyze scrcpy current connection flow
- [ ] Choose WebSocket library for Java
- [ ] Setup Node.js project structure

### **Phase 2: Server-Side Implementation (2-3 weeks)**

#### Step 1: Add WebSocket Server to scrcpy-server.jar
```java
// server/src/main/java/com/genymobile/scrcpy/websocket/WebSocketServer.java
package com.genymobile.scrcpy.websocket;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import java.net.InetSocketAddress;

public class ScrcpyWebSocketServer extends WebSocketServer {
    
    private static final int DEFAULT_PORT = 8000;
    
    public ScrcpyWebSocketServer(int port) {
        super(new InetSocketAddress(port));
    }
    
    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        Ln.i("New WebSocket connection from: " + conn.getRemoteSocketAddress());
    }
    
    @Override
    public void onMessage(WebSocket conn, String message) {
        // Handle control messages (touch, keyboard, etc.)
        handleControlMessage(conn, message);
    }
    
    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Ln.i("WebSocket closed: " + reason);
    }
    
    @Override
    public void onError(WebSocket conn, Exception ex) {
        Ln.e("WebSocket error: " + ex.getMessage());
    }
    
    public void broadcastVideoFrame(byte[] h264Data) {
        // Send H264 frames to all connected clients
        broadcast(h264Data);
    }
    
    private void handleControlMessage(WebSocket conn, String message) {
        // Parse and handle control messages
        // Format: {"type": "touch", "action": "down", "x": 100, "y": 200}
    }
}
```

#### Step 2: Modify Server.java to support WebSocket mode
```java
// Add to server/src/main/java/com/genymobile/scrcpy/Server.java

private static void scrcpy(Options options) throws IOException, ConfigurationException {
    // ... existing code ...
    
    // NEW: Check if WebSocket mode is enabled
    if (options.isWebSocketMode()) {
        startWebSocketMode(options);
    } else {
        // Existing socket mode
        startSocketMode(options);
    }
}

private static void startWebSocketMode(Options options) {
    Ln.i("Starting WebSocket server on port " + options.getWebSocketPort());
    
    ScrcpyWebSocketServer wsServer = new ScrcpyWebSocketServer(options.getWebSocketPort());
    wsServer.start();
    
    // Setup video streaming to WebSocket
    // Setup control message handling from WebSocket
    // ...
}
```

#### Step 3: Modify Options.java to add WebSocket parameters
```java
// Add to server/src/main/java/com/genymobile/scrcpy/Options.java

private boolean webSocketMode = false;
private int webSocketPort = 8000;

public boolean isWebSocketMode() {
    return webSocketMode;
}

public int getWebSocketPort() {
    return webSocketPort;
}

// Add parsing for --websocket and --ws-port arguments
```

### **Phase 3: Node.js Proxy Server (2 weeks)**

#### Step 1: Create Express + WebSocket Server
```javascript
// ws-scrcpy-proxy/src/server.js
const express = require('express');
const WebSocket = require('ws');
const { Client } = require('adb-ts');
const app = express();

const HTTP_PORT = 3000;
const WS_PORT = 8080;

// Serve static files (web client)
app.use(express.static('public'));

// WebSocket server for browser clients
const wss = new WebSocket.Server({ port: WS_PORT });

wss.on('connection', async (browserWs) => {
    console.log('Browser client connected');
    
    // Connect to Android device via ADB
    const adbClient = new Client();
    const devices = await adbClient.listDevices();
    
    if (devices.length === 0) {
        browserWs.send(JSON.stringify({ error: 'No devices found' }));
        return;
    }
    
    const device = devices[0];
    
    // Forward port to device
    await adbClient.forward(device.id, 'tcp:8000', 'tcp:8000');
    
    // Connect to scrcpy WebSocket server on device
    const deviceWs = new WebSocket('ws://localhost:8000');
    
    // Pipe video stream: Device → Browser
    deviceWs.on('message', (data) => {
        if (browserWs.readyState === WebSocket.OPEN) {
            browserWs.send(data);
        }
    });
    
    // Pipe control messages: Browser → Device
    browserWs.on('message', (data) => {
        if (deviceWs.readyState === WebSocket.OPEN) {
            deviceWs.send(data);
        }
    });
    
    // Handle disconnection
    browserWs.on('close', () => {
        deviceWs.close();
    });
});

app.listen(HTTP_PORT, () => {
    console.log(`HTTP server running on http://localhost:${HTTP_PORT}`);
    console.log(`WebSocket server running on ws://localhost:${WS_PORT}`);
});
```

#### Step 2: Create ADB Manager
```javascript
// ws-scrcpy-proxy/src/adb-manager.js
const { Client } = require('adb-ts');

class ADBManager {
    constructor() {
        this.client = new Client();
    }
    
    async listDevices() {
        return await this.client.listDevices();
    }
    
    async startScrcpyServer(deviceId) {
        // Push scrcpy-server.jar to device
        await this.client.push(deviceId, 
            './scrcpy-server.jar', 
            '/data/local/tmp/scrcpy-server.jar'
        );
        
        // Start scrcpy with WebSocket mode
        const shell = await this.client.shell(deviceId, 
            'CLASSPATH=/data/local/tmp/scrcpy-server.jar ' +
            'app_process / com.genymobile.scrcpy.Server ' +
            '--websocket --ws-port=8000'
        );
        
        return shell;
    }
    
    async forwardPort(deviceId, local, remote) {
        await this.client.forward(deviceId, local, remote);
    }
}

module.exports = ADBManager;
```

### **Phase 4: Web Client (2 weeks)**

#### Step 1: Create HTML Interface
```html
<!-- ws-scrcpy-proxy/public/index.html -->
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>scrcpy Web Client</title>
    <link rel="stylesheet" href="css/styles.css">
</head>
<body>
    <div class="container">
        <h1>scrcpy Web Client</h1>
        <div class="video-container">
            <canvas id="video-canvas"></canvas>
        </div>
        <div class="controls">
            <button id="connect-btn">Connect</button>
            <button id="disconnect-btn" disabled>Disconnect</button>
            <select id="decoder-select">
                <option value="mse">MSE Decoder (Hardware)</option>
                <option value="broadway">Broadway (Software)</option>
                <option value="tinyh264">TinyH264 (Software)</option>
                <option value="webcodecs">WebCodecs (Modern)</option>
            </select>
        </div>
        <div class="info">
            <p>Status: <span id="status">Disconnected</span></p>
            <p>FPS: <span id="fps">0</span></p>
            <p>Latency: <span id="latency">0ms</span></p>
        </div>
    </div>
    
    <script src="js/decoders/mse-player.js"></script>
    <script src="js/decoders/broadway.js"></script>
    <script src="js/video-decoder.js"></script>
    <script src="js/control-handler.js"></script>
    <script src="js/main.js"></script>
</body>
</html>
```

#### Step 2: Main Client Logic
```javascript
// ws-scrcpy-proxy/public/js/main.js
class ScrcpyWebClient {
    constructor() {
        this.ws = null;
        this.decoder = null;
        this.canvas = document.getElementById('video-canvas');
        this.controlHandler = new ControlHandler(this);
    }
    
    connect(decoderType = 'mse') {
        this.ws = new WebSocket('ws://localhost:8080');
        
        this.ws.onopen = () => {
            console.log('Connected to proxy server');
            this.updateStatus('Connected');
            this.initDecoder(decoderType);
        };
        
        this.ws.onmessage = (event) => {
            if (event.data instanceof Blob) {
                // H264 video data
                this.handleVideoData(event.data);
            } else {
                // Control/Info message
                this.handleInfoMessage(JSON.parse(event.data));
            }
        };
        
        this.ws.onerror = (error) => {
            console.error('WebSocket error:', error);
            this.updateStatus('Error');
        };
        
        this.ws.onclose = () => {
            console.log('Disconnected from proxy server');
            this.updateStatus('Disconnected');
        };
    }
    
    initDecoder(type) {
        switch(type) {
            case 'mse':
                this.decoder = new MSEDecoder(this.canvas);
                break;
            case 'broadway':
                this.decoder = new BroadwayDecoder(this.canvas);
                break;
            case 'tinyh264':
                this.decoder = new TinyH264Decoder(this.canvas);
                break;
            case 'webcodecs':
                this.decoder = new WebCodecsDecoder(this.canvas);
                break;
        }
    }
    
    handleVideoData(blob) {
        blob.arrayBuffer().then(buffer => {
            this.decoder.decode(new Uint8Array(buffer));
            this.updateFPS();
        });
    }
    
    sendControlMessage(message) {
        if (this.ws && this.ws.readyState === WebSocket.OPEN) {
            this.ws.send(JSON.stringify(message));
        }
    }
    
    sendTouch(action, x, y) {
        this.sendControlMessage({
            type: 'touch',
            action: action,  // 'down', 'move', 'up'
            x: x,
            y: y,
            timestamp: Date.now()
        });
    }
    
    sendKey(keyCode, action) {
        this.sendControlMessage({
            type: 'key',
            keyCode: keyCode,
            action: action,  // 'down', 'up'
            timestamp: Date.now()
        });
    }
    
    updateStatus(status) {
        document.getElementById('status').textContent = status;
    }
    
    updateFPS() {
        // Calculate and update FPS
    }
}

// Initialize
const client = new ScrcpyWebClient();

document.getElementById('connect-btn').addEventListener('click', () => {
    const decoderType = document.getElementById('decoder-select').value;
    client.connect(decoderType);
});

document.getElementById('disconnect-btn').addEventListener('click', () => {
    client.disconnect();
});
```

#### Step 3: Control Handler
```javascript
// ws-scrcpy-proxy/public/js/control-handler.js
class ControlHandler {
    constructor(client) {
        this.client = client;
        this.canvas = client.canvas;
        this.initEventListeners();
    }
    
    initEventListeners() {
        // Touch events
        this.canvas.addEventListener('touchstart', this.handleTouchStart.bind(this));
        this.canvas.addEventListener('touchmove', this.handleTouchMove.bind(this));
        this.canvas.addEventListener('touchend', this.handleTouchEnd.bind(this));
        
        // Mouse events (for desktop)
        this.canvas.addEventListener('mousedown', this.handleMouseDown.bind(this));
        this.canvas.addEventListener('mousemove', this.handleMouseMove.bind(this));
        this.canvas.addEventListener('mouseup', this.handleMouseUp.bind(this));
        
        // Keyboard events
        window.addEventListener('keydown', this.handleKeyDown.bind(this));
        window.addEventListener('keyup', this.handleKeyUp.bind(this));
    }
    
    handleTouchStart(event) {
        event.preventDefault();
        const touch = event.touches[0];
        const coords = this.getCanvasCoordinates(touch.clientX, touch.clientY);
        this.client.sendTouch('down', coords.x, coords.y);
    }
    
    handleTouchMove(event) {
        event.preventDefault();
        const touch = event.touches[0];
        const coords = this.getCanvasCoordinates(touch.clientX, touch.clientY);
        this.client.sendTouch('move', coords.x, coords.y);
    }
    
    handleTouchEnd(event) {
        event.preventDefault();
        // Use last known position
        this.client.sendTouch('up', this.lastX, this.lastY);
    }
    
    handleMouseDown(event) {
        const coords = this.getCanvasCoordinates(event.clientX, event.clientY);
        this.client.sendTouch('down', coords.x, coords.y);
    }
    
    handleMouseMove(event) {
        if (event.buttons > 0) {  // Only if mouse button is pressed
            const coords = this.getCanvasCoordinates(event.clientX, event.clientY);
            this.client.sendTouch('move', coords.x, coords.y);
        }
    }
    
    handleMouseUp(event) {
        const coords = this.getCanvasCoordinates(event.clientX, event.clientY);
        this.client.sendTouch('up', coords.x, coords.y);
    }
    
    handleKeyDown(event) {
        this.client.sendKey(event.keyCode, 'down');
    }
    
    handleKeyUp(event) {
        this.client.sendKey(event.keyCode, 'up');
    }
    
    getCanvasCoordinates(clientX, clientY) {
        const rect = this.canvas.getBoundingClientRect();
        const x = ((clientX - rect.left) / rect.width) * this.canvas.width;
        const y = ((clientY - rect.top) / rect.height) * this.canvas.height;
        return { x: Math.round(x), y: Math.round(y) };
    }
}
```

#### Step 4: MSE Video Decoder
```javascript
// ws-scrcpy-proxy/public/js/decoders/mse-player.js
class MSEDecoder {
    constructor(canvas) {
        this.canvas = canvas;
        this.video = document.createElement('video');
        this.video.style.display = 'none';
        document.body.appendChild(this.video);
        
        this.mediaSource = new MediaSource();
        this.video.src = URL.createObjectURL(this.mediaSource);
        
        this.sourceBuffer = null;
        this.queue = [];
        
        this.mediaSource.addEventListener('sourceopen', () => {
            this.sourceBuffer = this.mediaSource.addSourceBuffer('video/mp4; codecs="avc1.64001f"');
            this.sourceBuffer.addEventListener('updateend', () => {
                if (this.queue.length > 0 && !this.sourceBuffer.updating) {
                    this.sourceBuffer.appendBuffer(this.queue.shift());
                }
            });
        });
        
        // Draw video to canvas
        this.video.addEventListener('play', () => {
            this.drawVideoFrame();
        });
        
        this.video.play();
    }
    
    decode(h264Data) {
        // Convert H264 NAL units to MP4 format
        const mp4Data = this.h264ToMP4(h264Data);
        
        if (this.sourceBuffer && !this.sourceBuffer.updating) {
            this.sourceBuffer.appendBuffer(mp4Data);
        } else {
            this.queue.push(mp4Data);
        }
    }
    
    h264ToMP4(nalUnits) {
        // This is a simplified version
        // In production, you'd use a proper MP4 muxer
        // See: https://github.com/NetrisTV/ws-scrcpy for reference
        
        // TODO: Implement proper H264 to MP4 conversion
        // using MP4Box or similar library
        return nalUnits;
    }
    
    drawVideoFrame() {
        if (this.video.paused || this.video.ended) return;
        
        const ctx = this.canvas.getContext('2d');
        this.canvas.width = this.video.videoWidth;
        this.canvas.height = this.video.videoHeight;
        ctx.drawImage(this.video, 0, 0);
        
        requestAnimationFrame(() => this.drawVideoFrame());
    }
}
```

### **Phase 5: Integration & Testing (2 weeks)**

1. **End-to-End Testing**
   - [ ] Test WebSocket connection stability
   - [ ] Test video streaming quality
   - [ ] Test control input latency
   - [ ] Test multi-touch support
   - [ ] Test keyboard input
   - [ ] Test different screen resolutions
   - [ ] Test different Android versions

2. **Performance Optimization**
   - [ ] Optimize H264 encoding settings
   - [ ] Minimize WebSocket overhead
   - [ ] Implement buffering strategies
   - [ ] Add frame dropping mechanism

3. **Error Handling**
   - [ ] Handle connection drops
   - [ ] Handle ADB disconnections
   - [ ] Implement reconnection logic
   - [ ] Add user-friendly error messages

### **Phase 6: Security & Polish (1 week)**

1. **Security Enhancements**
   - [ ] Add HTTPS support
   - [ ] Implement WSS (WebSocket Secure)
   - [ ] Add authentication/authorization
   - [ ] Implement rate limiting

2. **UX Improvements**
   - [ ] Add loading indicators
   - [ ] Add connection status feedback
   - [ ] Implement settings panel
   - [ ] Add fullscreen mode
   - [ ] Add virtual keyboard for mobile devices

---

## 🛠️ Teknologi Stack

### **Server Side (Android)**
- **Language:** Java 8+
- **WebSocket Library:** [Java-WebSocket](https://github.com/TooTallNate/Java-WebSocket)
- **Build Tool:** Gradle
- **Target SDK:** Android 21+ (Lollipop)

### **Proxy Server**
- **Runtime:** Node.js 16+
- **Framework:** Express.js
- **WebSocket:** ws (WebSocket library for Node.js)
- **ADB Client:** adb-ts or adbkit
- **Process Manager:** PM2 (for production)

### **Web Client**
- **Core:** Vanilla JavaScript (ES6+)
- **Video Decoding:**
  - MSE (Media Source Extensions)
  - Broadway.js (WASM H264 decoder)
  - TinyH264 (WASM H264 decoder)
  - WebCodecs API (for modern browsers)
- **UI Framework:** Optional (Vue.js/React for complex UI)
- **Build Tool:** Webpack/Vite (optional)

---

## 📊 Data Flow

### **Video Stream Flow**
```
Android Screen
    ↓ (Surface Capture)
Screen Encoder (H264)
    ↓ (Encoded frames)
WebSocket Server (On Device)
    ↓ (WS Binary)
Node.js Proxy
    ↓ (WS Binary)
Browser WebSocket Client
    ↓ (ArrayBuffer)
Video Decoder (MSE/Broadway/TinyH264/WebCodecs)
    ↓ (Video frames)
Canvas/Video Element
```

### **Control Flow**
```
Browser (Touch/Keyboard Event)
    ↓ (JSON message)
WebSocket Client
    ↓ (WS Text)
Node.js Proxy
    ↓ (WS Text)
WebSocket Server (On Device)
    ↓ (Parse control message)
Controller
    ↓ (Inject event)
Android System (Input)
```

---

## 🔍 Message Protocol

### **Control Messages (Browser → Device)**

```json
// Touch Event
{
    "type": "touch",
    "action": "down",  // "down", "move", "up"
    "pointerId": 0,
    "x": 540,
    "y": 960,
    "pressure": 1.0,
    "timestamp": 1639123456789
}

// Multi-touch
{
    "type": "multitouch",
    "pointers": [
        { "id": 0, "action": "down", "x": 100, "y": 200 },
        { "id": 1, "action": "down", "x": 300, "y": 400 }
    ],
    "timestamp": 1639123456789
}

// Keyboard Event
{
    "type": "key",
    "action": "down",  // "down", "up"
    "keyCode": 4,  // Android KeyEvent.KEYCODE_*
    "metaState": 0,
    "timestamp": 1639123456789
}

// Text Injection
{
    "type": "text",
    "text": "Hello World",
    "timestamp": 1639123456789
}

// Clipboard
{
    "type": "clipboard",
    "action": "set",
    "text": "Clipboard content",
    "timestamp": 1639123456789
}
```

### **Info Messages (Device → Browser)**

```json
// Device Info
{
    "type": "deviceInfo",
    "device": {
        "model": "Pixel 6",
        "manufacturer": "Google",
        "androidVersion": "13",
        "screenWidth": 1080,
        "screenHeight": 2400,
        "density": 440
    }
}

// Video Config
{
    "type": "videoConfig",
    "codec": "h264",
    "width": 1080,
    "height": 2400,
    "bitrate": 8000000,
    "fps": 60
}

// Frame Metadata
{
    "type": "frameMeta",
    "pts": 1639123456789,
    "frameSize": 12345
}
```

---

## 📦 Build & Deployment

### **Building Modified scrcpy-server.jar**

```bash
# 1. Add WebSocket dependency to server/build.gradle
echo "dependencies {
    implementation 'org.java-websocket:Java-WebSocket:1.5.3'
    testImplementation 'junit:junit:4.13.2'
}" >> server/build.gradle

# 2. Implement WebSocket classes (as shown above)

# 3. Build the server
cd /path/to/scrcpy
./gradlew assembleRelease

# 4. Output will be in:
# server/build/outputs/apk/release/server-release-unsigned.apk
# Rename to scrcpy-server.jar
```

### **Running Node.js Proxy**

```bash
# 1. Install dependencies
cd ws-scrcpy-proxy
npm install

# 2. Start the server
npm start

# 3. Access web client at:
# http://localhost:3000
```

### **Docker Deployment (Optional)**

```dockerfile
# Dockerfile for proxy server
FROM node:16-alpine

WORKDIR /app

COPY package*.json ./
RUN npm install --production

COPY . .

EXPOSE 3000 8080

CMD ["node", "src/server.js"]
```

---

## 🧪 Testing Strategy

### **Unit Tests**
- WebSocket server connection handling
- Control message parsing
- Video frame packaging
- Error handling

### **Integration Tests**
- End-to-end video streaming
- Control message delivery
- Multi-device support
- Reconnection scenarios

### **Performance Tests**
- Latency measurement (target: <100ms)
- Throughput testing (target: 8Mbps+)
- CPU/Memory usage monitoring
- Battery impact assessment

---

## 🔐 Security Considerations

1. **Authentication**
   - Implement token-based auth
   - Add device pairing mechanism
   - Rate limit connection attempts

2. **Encryption**
   - Use HTTPS for web client
   - Use WSS for WebSocket connections
   - Consider end-to-end encryption for sensitive data

3. **Input Validation**
   - Sanitize all control messages
   - Validate coordinate ranges
   - Prevent injection attacks

4. **Network Security**
   - Implement CORS properly
   - Use secure WebSocket origins
   - Add firewall rules for production

---

## 📚 Referensi dan Resources

### **Original Projects**
- [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy) - Original scrcpy
- [NetrisTV/ws-scrcpy](https://github.com/NetrisTV/ws-scrcpy) - WebSocket implementation reference

### **Libraries & Tools**
- [Java-WebSocket](https://github.com/TooTallNate/Java-WebSocket) - Java WebSocket library
- [ws](https://github.com/websockets/ws) - Node.js WebSocket library
- [adb-ts](https://github.com/Maaaartin/adb-ts) - TypeScript ADB client
- [Broadway.js](https://github.com/mbebenita/Broadway) - H264 decoder in JS
- [TinyH264](https://github.com/udevbe/tinyh264) - WebAssembly H264 decoder

### **Documentation**
- [WebSocket API MDN](https://developer.mozilla.org/en-US/docs/Web/API/WebSocket)
- [Media Source Extensions](https://developer.mozilla.org/en-US/docs/Web/API/Media_Source_Extensions_API)
- [WebCodecs API](https://developer.mozilla.org/en-US/docs/Web/API/WebCodecs_API)
- [Android Input System](https://source.android.com/devices/input)

### **Tutorials**
- [Building a WebSocket Server in Java](https://www.baeldung.com/java-websockets)
- [H.264 Video Streaming over WebSocket](https://blog.mux.com/streaming-h264-over-websockets/)
- [MSE Playback Tutorial](https://developer.mozilla.org/en-US/docs/Web/API/Media_Source_Extensions_API/Using_SourceBuffer)

---

## 🎯 Success Criteria

- [ ] Video latency < 100ms
- [ ] Input lag < 50ms
- [ ] Supports 1080p@60fps streaming
- [ ] Works on Chrome, Firefox, Safari (latest versions)
- [ ] Supports multi-touch (up to 10 fingers)
- [ ] Battery consumption < 10% increase
- [ ] Reconnection within 2 seconds after drop
- [ ] Works with Android 5.0+ devices

---

## 📈 Future Enhancements

1. **Advanced Features**
   - [] Add clipboard sync
   - [ ] File transfer (drag & drop)
   - [ ] Remote shell integration
   - [ ] Screen recording from browser
   - [ ] Multiple device support in one interface

2. **Performance**
   - [ ] Implement adaptive bitrate
   - [ ] Add hardware encoding options
   - [ ] Optimize for low-latency (<50ms)
   - [ ] Support VP9/AV1 codecs

3. **Platform Support**
   - [ ] iOS support (if possible via WebRTC)
   - [ ] Desktop app (Electron wrapper)
   - [ ] Mobile browser optimization
   - [ ] PWA (Progressive Web App) support

---

## 📝 Notes

- Coding ini adalah modifikasi significant dari scrcpy original
- Testing ekstensif diperlukan untuk berbagai Android devices
- Perhatikan copyright dan license (Apache 2.0)
- Consider contributing back to upstream project jika feasible

---

**Last Updated:** 2025-12-17  
**Version:** 1.0  
**Author:** Planning Document for scrcpy WebSocket Implementation
