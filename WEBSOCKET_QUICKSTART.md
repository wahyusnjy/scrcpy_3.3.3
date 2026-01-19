# 🚀 Quick Start Guide: WebSocket Implementation

> **Panduan cepat untuk memulai implementasi WebSocket di scrcpy**

## 📋 Prerequisites

Sebelum memulai, pastikan Anda sudah menginstall:

- ✅ **Java 17** (sudah terinstall di sistem Anda)
- ✅ **Node.js 16+** (untuk proxy server)
- ✅ **Android SDK** (untuk build server)
- ✅ **ADB** (Android Debug Bridge)
- ✅ **Git** (untuk version control)

---

## 🎯 Pilihan Implementasi

Anda punya 2 pilihan:

### **Option 1: Quick Prototype (Menggunakan ws-scrcpy existing)**
Gunakan ini untuk testing dan understanding cepat.

### **Option 2: Custom Implementation (Build dari scratch)**
Untuk full control dan customization.

---

## Option 1: Quick Prototype with ws-scrcpy

### Step 1: Clone ws-scrcpy
```bash
cd ~/Documents/riset/scrcpy-c
git clone https://github.com/NetrisTV/ws-scrcpy.git
cd ws-scrcpy
```

### Step 2: Install Dependencies
```bash
npm install
```

### Step 3: Start the Server
```bash
npm start
```

### Step 4: Access Web Interface
Buka browser dan akses: `http://localhost:8000`

### Step 5: Connect Device
1. Pastikan Android device terhubung via USB atau WiFi
2. Enable USB debugging
3. Di web interface, pilih device Anda
4. Klik "Start"

**Selesai!** Anda bisa lihat dan kontrol device dari browser.

---

## Option 2: Custom Implementation (Recommended untuk Learning)

### Phase 1: Setup Project Structure

```bash
cd ~/Documents/riset/scrcpy-c/scrcpy

# Buat folder untuk WebSocket implementation
mkdir -p server/src/main/java/com/genymobile/scrcpy/websocket
mkdir -p ws-scrcpy-proxy/{src,public/{js,css},config}
mkdir -p ws-scrcpy-proxy/public/js/decoders
```

### Phase 2: Modify Server (Android)

#### 2.1: Add WebSocket Dependency

Edit `server/build.gradle`:

```bash
cat >> server/build.gradle << 'EOF'

dependencies {
    implementation 'org.java-websocket:Java-WebSocket:1.5.3'
    testImplementation 'junit:junit:4.13.2'
}
EOF
```

#### 2.2: Create WebSocket Server Class

```bash
cat > server/src/main/java/com/genymobile/scrcpy/websocket/WebSocketServer.java << 'EOF'
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
        System.out.println("New WebSocket connection: " + conn.getRemoteSocketAddress());
    }
    
    @Override
    public void onMessage(WebSocket conn, String message) {
        System.out.println("Received: " + message);
        // Handle control messages here
    }
    
    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println("WebSocket closed: " + reason);
    }
    
    @Override
    public void onError(WebSocket conn, Exception ex) {
        ex.printStackTrace();
    }
    
    public void broadcastVideoFrame(byte[] h264Data) {
        broadcast(h264Data);
    }
}
EOF
```

#### 2.3: Build Modified Server

```bash
cd ~/Documents/riset/scrcpy-c/scrcpy

# Set Android SDK environment
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# Build server
./gradlew assembleRelease

# Copy output
cp server/build/outputs/apk/release/server-release-unsigned.apk \
   scrcpy-server-ws.jar
```

### Phase 3: Create Node.js Proxy Server

#### 3.1: Initialize Node.js Project

```bash
cd ~/Documents/riset/scrcpy-c/scrcpy/ws-scrcpy-proxy

# Create package.json
cat > package.json << 'EOF'
{
  "name": "ws-scrcpy-proxy",
  "version": "1.0.0",
  "description": "WebSocket proxy for scrcpy",
  "main": "src/server.js",
  "scripts": {
    "start": "node src/server.js",
    "dev": "nodemon src/server.js"
  },
  "dependencies": {
    "express": "^4.18.2",
    "ws": "^8.14.2",
    "adb-ts": "^3.3.1"
  },
  "devDependencies": {
    "nodemon": "^3.0.1"
  }
}
EOF

# Install dependencies
npm install
```

#### 3.2: Create Proxy Server

```bash
cat > src/server.js << 'EOF'
const express = require('express');
const WebSocket = require('ws');
const path = require('path');

const app = express();
const HTTP_PORT = 3000;
const WS_PORT = 8080;

// Serve static files
app.use(express.static('public'));

app.get('/', (req, res) => {
    res.sendFile(path.join(__dirname, '../public/index.html'));
});

// WebSocket server
const wss = new WebSocket.Server({ port: WS_PORT });

wss.on('connection', (ws) => {
    console.log('Browser client connected');
    
    // Connect to Android device WebSocket
    // This is simplified - in production, use ADB to forward port
    const deviceWs = new WebSocket('ws://localhost:8000');
    
    deviceWs.on('open', () => {
        console.log('Connected to Android device');
    });
    
    // Forward video stream: Device → Browser
    deviceWs.on('message', (data) => {
        if (ws.readyState === WebSocket.OPEN) {
            ws.send(data);
        }
    });
    
    // Forward control: Browser → Device
    ws.on('message', (data) => {
        if (deviceWs.readyState === WebSocket.OPEN) {
            deviceWs.send(data);
        }
    });
    
    ws.on('close', () => {
        console.log('Browser client disconnected');
        deviceWs.close();
    });
    
    deviceWs.on('close', () => {
        console.log('Device disconnected');
        ws.close();
    });
});

app.listen(HTTP_PORT, () => {
    console.log(`HTTP Server: http://localhost:${HTTP_PORT}`);
    console.log(`WebSocket Server: ws://localhost:${WS_PORT}`);
});
EOF
```

#### 3.3: Create Web Client

```bash
cat > public/index.html << 'EOF'
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>scrcpy WebSocket Client</title>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }
        
        body {
            font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            min-height: 100vh;
            display: flex;
            justify-content: center;
            align-items: center;
            padding: 20px;
        }
        
        .container {
            background: rgba(255, 255, 255, 0.95);
            border-radius: 20px;
            padding: 30px;
            box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
            max-width: 800px;
            width: 100%;
        }
        
        h1 {
            color: #333;
            margin-bottom: 20px;
            text-align: center;
        }
        
        .video-container {
            background: #000;
            border-radius: 10px;
            overflow: hidden;
            margin-bottom: 20px;
            aspect-ratio: 9/16;
            position: relative;
        }
        
        canvas {
            width: 100%;
            height: 100%;
            display: block;
        }
        
        .controls {
            display: flex;
            gap: 10px;
            margin-bottom: 20px;
            flex-wrap: wrap;
        }
        
        button {
            flex: 1;
            padding: 12px 24px;
            border: none;
            border-radius: 8px;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: white;
            font-weight: 600;
            cursor: pointer;
            transition: transform 0.2s, box-shadow 0.2s;
        }
        
        button:hover {
            transform: translateY(-2px);
            box-shadow: 0 5px 15px rgba(0, 0, 0, 0.2);
        }
        
        button:disabled {
            opacity: 0.5;
            cursor: not-allowed;
            transform: none;
        }
        
        .info {
            background: #f5f5f5;
            padding: 15px;
            border-radius: 10px;
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
            gap: 10px;
        }
        
        .info p {
            margin: 0;
            color: #666;
        }
        
        .info span {
            font-weight: 600;
            color: #333;
        }
        
        #status {
            display: inline-block;
            padding: 4px 12px;
            border-radius: 20px;
            font-size: 12px;
            font-weight: 600;
        }
        
        .status-disconnected {
            background: #ff6b6b;
            color: white;
        }
        
        .status-connected {
            background: #51cf66;
            color: white;
        }
    </style>
</head>
<body>
    <div class="container">
        <h1>🎮 scrcpy WebSocket Client</h1>
        
        <div class="video-container">
            <canvas id="video-canvas"></canvas>
        </div>
        
        <div class="controls">
            <button id="connect-btn">🔌 Connect</button>
            <button id="disconnect-btn" disabled>🔌 Disconnect</button>
        </div>
        
        <div class="info">
            <p>Status: <span id="status" class="status-disconnected">Disconnected</span></p>
            <p>FPS: <span id="fps">0</span></p>
            <p>Latency: <span id="latency">0ms</span></p>
        </div>
    </div>
    
    <script>
        class ScrcpyClient {
            constructor() {
                this.ws = null;
                this.canvas = document.getElementById('video-canvas');
                this.ctx = this.canvas.getContext('2d');
                this.setupEventListeners();
            }
            
            connect() {
                this.ws = new WebSocket('ws://localhost:8080');
                
                this.ws.onopen = () => {
                    console.log('Connected!');
                    this.updateStatus('connected');
                    document.getElementById('connect-btn').disabled = true;
                    document.getElementById('disconnect-btn').disabled = false;
                };
                
                this.ws.onmessage = (event) => {
                    // Handle incoming video/data
                    console.log('Received data:', event.data);
                };
                
                this.ws.onerror = (error) => {
                    console.error('WebSocket error:', error);
                };
                
                this.ws.onclose = () => {
                    console.log('Disconnected');
                    this.updateStatus('disconnected');
                    document.getElementById('connect-btn').disabled = false;
                    document.getElementById('disconnect-btn').disabled = true;
                };
            }
            
            disconnect() {
                if (this.ws) {
                    this.ws.close();
                }
            }
            
            setupEventListeners() {
                // Touch/Mouse events on canvas
                this.canvas.addEventListener('mousedown', (e) => {
                    this.sendTouch('down', e.offsetX, e.offsetY);
                });
                
                this.canvas.addEventListener('mousemove', (e) => {
                    if (e.buttons > 0) {
                        this.sendTouch('move', e.offsetX, e.offsetY);
                    }
                });
                
                this.canvas.addEventListener('mouseup', (e) => {
                    this.sendTouch('up', e.offsetX, e.offsetY);
                });
            }
            
            sendTouch(action, x, y) {
                if (this.ws && this.ws.readyState === WebSocket.OPEN) {
                    this.ws.send(JSON.stringify({
                        type: 'touch',
                        action: action,
                        x: x,
                        y: y
                    }));
                }
            }
            
            updateStatus(status) {
                const statusEl = document.getElementById('status');
                statusEl.textContent = status.charAt(0).toUpperCase() + status.slice(1);
                statusEl.className = 'status-' + status;
            }
        }
        
        const client = new ScrcpyClient();
        
        document.getElementById('connect-btn').addEventListener('click', () => {
            client.connect();
        });
        
        document.getElementById('disconnect-btn').addEventListener('click', () => {
            client.disconnect();
        });
    </script>
</body>
</html>
EOF
```

### Phase 4: Testing

#### 4.1: Start Android Device with Modified Server

```bash
# Push modified server to device
adb push scrcpy-server-ws.jar /data/local/tmp/

# Start server with WebSocket mode
adb shell CLASSPATH=/data/local/tmp/scrcpy-server-ws.jar \
    app_process / com.genymobile.scrcpy.Server \
    --websocket --ws-port=8000
```

#### 4.2: Forward Port from Device

```bash
# Forward WebSocket port from device to localhost
adb forward tcp:8000 tcp:8000
```

#### 4.3: Start Proxy Server

```bash
cd ~/Documents/riset/scrcpy-c/scrcpy/ws-scrcpy-proxy
npm start
```

#### 4.4: Open Web Client

```bash
open http://localhost:3000
```

---

## 🐛 Troubleshooting

### Problem: Cannot connect to device

**Solution:**
```bash
# Check ADB connection
adb devices

# Restart ADB server
adb kill-server
adb start-server

# Check port forwarding
adb forward --list
```

### Problem: WebSocket connection refused

**Solution:**
```bash
# Check if server is running on device
adb shell ps | grep app_process

# Check port is listening
adb shell netstat -an | grep 8000

# Re-forward port
adb forward --remove tcp:8000
adb forward tcp:8000 tcp:8000
```

### Problem: No video showing

**Solution:**
- Check browser console for errors
- Verify H264 codec is supported in browser
- Try different video decoder
- Check network tab for WebSocket messages

---

## 📊 Next Steps

Setelah berhasil basic implementation, Anda bisa:

1. **Improve Video Decoding**
   - Implement MSE decoder
   - Add Broadway.js for software decoding
   - Test WebCodecs API for hardware acceleration

2. **Enhance Control**
   - Add multi-touch support
   - Implement keyboard input
   - Add clipboard sync

3. **Optimize Performance**
   - Reduce latency with buffering strategies
   - Implement adaptive bitrate
   - Add frame dropping mechanism

4. **Add Features**
   - File transfer
   - Screen recording
   - Multiple device support
   - Remote shell

---

## 📚 Resources

- **Documentation:** `/WEBSOCKET_IMPLEMENTATION_PLAN.md`
- **Original scrcpy:** https://github.com/Genymobile/scrcpy
- **ws-scrcpy reference:** https://github.com/NetrisTV/ws-scrcpy
- **WebSocket API:** https://developer.mozilla.org/en-US/docs/Web/API/WebSockets_API

---

## 💡 Tips

1. **Start Simple:** Mulai dengan basic WebSocket connection dulu, baru tambahkan video streaming
2. **Use Existing Code:** Study ws-scrcpy source code untuk reference
3. **Test Incrementally:** Test setiap component secara terpisah
4. **Monitor Performance:** Gunakan browser dev tools untuk monitor latency dan FPS
5. **Read Logs:** Enable verbose logging di semua layer untuk debugging

---

**Happy Coding!** 🚀

Jika ada pertanyaan atau stuck, refer ke `WEBSOCKET_IMPLEMENTATION_PLAN.md` untuk detail lengkap.
