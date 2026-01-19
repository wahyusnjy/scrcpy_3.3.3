# 🔍 Analisis Perubahan NetrisTV WebSocket Implementation

> **Branch:** `feature/websocket-server`  
> **Repository:** https://github.com/NetrisTV/scrcpy/tree/feature/websocket-server  
> **Base Version:** scrcpy v1.18

---

## 📊 Overview

NetrisTV telah memodifikasi scrcpy original untuk support WebSocket server yang embedded di dalam scrcpy-server.jar. Berikut adalah analisis lengkap perubahan yang dibuat.

---

## 🔧 Key Changes Summary

### **1. Server Type System**

NetrisTV menambahkan konsep "Server Type" untuk memisahkan mode operasi:

```java
// Options.java
public static final int TYPE_LOCAL_SOCKET = 1;  // Original scrcpy mode
public static final int TYPE_WEB_SOCKET = 2;    // NEW: WebSocket mode
```

### **2. New Class: `WSServer.java`**

Ini adalah **komponen utama** yang menambahkan WebSocket capability.

**Location:** `server/src/main/java/com/genymobile/scrcpy/WSServer.java`

**Key Features:**
- Extends `org.java_websocket.server.WebSocketServer`
- Mengelola multiple WebSocket connections
- Handle video streaming dan control messages
- Support multiple display IDs
- Persistent server (tetap running setelah client disconnect)

### **3. Modified: `Server.java`**

**Main Entry Point** diubah untuk support WebSocket mode.

**Key Changes:**

#### a. Argument Parsing
```java
// NEW: Parse "web" argument untuk enable WebSocket mode
if (args[1].toLowerCase().equals("web")) {
    options.setServerType(Options.TYPE_WEB_SOCKET);
    if (args.length > 2) {
        Ln.Level level = Ln.Level.valueOf(args[2].toUpperCase(Locale.ENGLISH));
        options.setLogLevel(level);
    }
    if (args.length > 3) {
        int portNumber = Integer.parseInt(args[3]);
        options.setPortNumber(portNumber);
    }
    if (args.length > 4) {
        boolean listenOnAllInterfaces = Boolean.parseBoolean(args[4]);
        options.setListenOnAllInterfaces(listenOnAllInterfaces);
    }
    return;
}
```

#### b. Server Mode Selection
```java
// NEW: Conditional server initialization
if (options.getServerType() == Options.TYPE_LOCAL_SOCKET) {
    new DesktopConnection(options, videoSettings);  // Original mode
} else if (options.getServerType() == Options.TYPE_WEB_SOCKET) {
    WSServer wsServer = new WSServer(options);      // NEW: WebSocket mode
    wsServer.setReuseAddr(true);
    wsServer.run();
}
```

### **4. Modified: `Options.java`**

Menambahkan properties untuk WebSocket configuration.

**New Properties:**
```java
private int serverType = TYPE_LOCAL_SOCKET;
private int portNumber = 8886;              // Default WebSocket port
private boolean listenOnAllInterfaces = true;
```

**New Getters/Setters:**
```java
public int getServerType()
public void setServerType(int type)
public void setPortNumber(int portNumber)
public int getPortNumber()
public boolean getListenOnAllInterfaces()
public void setListenOnAllInterfaces(boolean value)
```

### **5. New Class: `WebSocketConnection.java`**

Manages individual WebSocket connection lifecycle.

**Responsibilities:**
- Handle video streaming per connection
- Manage controller instance
- Send initial device info
- Handle client join/leave

### **6. New Class: `FilePushHandler.java`**

Handle file push via WebSocket (APK installation, etc).

### **7. Modified: `ControlMessageReader.java`**

Updated untuk parse control messages dari WebSocket format.

---

## 📁 File Structure Changes

### **New Files Added:**
```
server/src/main/java/com/genymobile/scrcpy/
├── WSServer.java                  [NEW] - Main WebSocket server
├── WebSocketConnection.java       [NEW] - Connection management
├── FilePushHandler.java          [NEW] - File push handling
└── ... (potentially more support files)
```

### **Modified Files:**
```
server/src/main/java/com/genymobile/scrcpy/
├── Server.java                   [MODIFIED] - Add WebSocket mode
├── Options.java                  [MODIFIED] - Add WebSocket options
├── ControlMessageReader.java    [MODIFIED] - Parse WebSocket messages
└── ... (other supporting files)
```

### **Dependencies Added:**
```gradle
// server/build.gradle
dependencies {
    implementation 'org.java-websocket:Java-WebSocket:1.5.1'
    // ... other dependencies
}
```

---

## 🚀 How It Works

### **Startup Flow:**

```
1. scrcpy-server.jar started dengan argument "web"
   │
   ├─→ parseArguments() detects "web" mode
   │   └─→ options.setServerType(TYPE_WEB_SOCKET)
   │
   ├─→ WSServer instance created
   │   ├─→ Bind to port (default: 8886)
   │   └─→ Listen on 0.0.0.0 atau 127.0.0.1
   │
   └─→ wsServer.run() - Server ready!
```

### **Client Connection Flow:**

```
Browser connects via WebSocket
   │
   ├─→ onOpen(webSocket, handshake)
   │   ├─→ Assign client ID
   │   ├─→ Create SocketInfo
   │   └─→ Send initial device info
   │
   ├─→ Client sends CHANGE_STREAM_PARAMETERS
   │   ├─→ Create/Join WebSocketConnection
   │   ├─→ Start video encoding
   │   └─→ Stream H264 frames
   │
   └─→ Receive control messages
       ├─→ Touch events
       ├─→ Keyboard events
       ├─→ Text injection
       └─→ File push
```

### **Video Streaming Flow:**

```
Android Screen
   ↓
SurfaceEncoder (H264)
   ↓
WebSocketConnection
   ↓
WSServer.broadcast()
   ↓
All connected WebSocket clients
```

### **Control Message Flow:**

```
Browser WebSocket sends ByteBuffer
   ↓
WSServer.onMessage(webSocket, message)
   ↓
ControlMessageReader.parseEvent(message)
   ↓
connection.getController().handleEvent(controlMessage)
   ↓
Android Input System
```

---

## 🔐 Security & Configuration

### **Network Binding:**

```java
// In WSServer constructor:
new InetSocketAddress(
    options.getListenOnAllInterfaces() ? "0.0.0.0" : "127.0.0.1",
    options.getPortNumber()
)
```

**Default:** Listen on all interfaces (0.0.0.0) - **SECURITY RISK!**

⚠️ **Warning:** No authentication, no encryption by default!

### **Port Configuration:**

Default port: **8886**

Can be changed via argument:
```bash
CLASSPATH=/data/local/tmp/scrcpy-server.jar \
    app_process / com.genymobile.scrcpy.Server \
    1.18 web info 9000 false
    # ↑    ↑   ↑    ↑    ↑
    # ver mode log  port all-interfaces
```

### **PID File Management:**

```java
private static final String PID_FILE_PATH = "/data/local/tmp/ws_scrcpy.pid";
```

Server writes PID to file untuk tracking dan management.

---

## 📝 Usage Examples

### **Start WebSocket Server (Basic):**

```bash
adb push scrcpy-server.jar /data/local/tmp/

adb shell CLASSPATH=/data/local/tmp/scrcpy-server.jar \
    app_process / com.genymobile.scrcpy.Server \
    1.18 web
```

### **Start with Custom Configuration:**

```bash
adb shell CLASSPATH=/data/local/tmp/scrcpy-server.jar \
    app_process / com.genymobile.scrcpy.Server \
    1.18 web info 8000 false
    # Arguments:
    # 1.18   - Server version
    # web    - WebSocket mode
    # info   - Log level (DEBUG, INFO, WARN, ERROR)
    # 8000   - Port number
    # false  - Listen on localhost only (not all interfaces)
```

### **Stop Server:**

```bash
# Find PID
cat /data/local/tmp/ws_scrcpy.pid

# Kill process
adb shell kill -9 $(cat /data/local/tmp/ws_scrcpy.pid)
```

---

## 🔗 Integration dengan ws-scrcpy

NetrisTV's ws-scrcpy web client **REQUIRES** this modified scrcpy-server.jar!

### **Architecture:**

```
┌─────────────┐    WebSocket     ┌──────────────────────┐
│             │◄─────────────────►│                      │
│  ws-scrcpy  │   Port 8886      │  Modified            │
│  Web Client │   (default)      │  scrcpy-server.jar   │
│  (Browser)  │                  │  (Android Device)    │
│             │                  │                      │
└─────────────┘                  └──────────────────────┘
       ▲
       │
       │ HTTP/WS
       │
┌──────┴───────┐
│  Node.js     │
│  Proxy       │
│  (Optional)  │
└──────────────┘
```

### **Direct Connection (No Proxy):**

Jika device dan browser di **network yang sama**:

```javascript
// ws-scrcpy web client
const ws = new WebSocket('ws://DEVICE_IP:8886');
```

### **Via Node.js Proxy:**

Jika menggunakan ADB tunnel:

```bash
# Forward port from device
adb forward tcp:8886 tcp:8886

# Connect browser to localhost
ws://localhost:8886
```

---

## 🎯 How to Use with Your ws-scrcpy Clone

Anda sudah clone ws-scrcpy di folder berbeda. Berikut cara integrasinya:

### **Step 1: Build Modified scrcpy-server.jar**

```bash
# Clone NetrisTV's modified scrcpy
cd ~/Documents/riset/scrcpy-c
git clone -b feature/websocket-server https://github.com/NetrisTV/scrcpy.git scrcpy-netris

cd scrcpy-netris

# Build the server
./gradlew assembleRelease

# Output location:
# server/build/outputs/apk/release/server-release-unsigned.apk
```

### **Step 2: Copy to ws-scrcpy Directory**

```bash
# Assuming your ws-scrcpy is at ~/Documents/riset/ws-scrcpy
cp server/build/outputs/apk/release/server-release-unsigned.apk \
   ~/Documents/riset/ws-scrcpy/dist/vendor/scrcpy-server.jar
```

### **Step 3: Configure ws-scrcpy**

Edit ws-scrcpy configuration jika necessary (biasanya sudah default).

### **Step 4: Start Everything**

```bash
# 1. Push server to device
adb push ~/Documents/riset/ws-scrcpy/dist/vendor/scrcpy-server.jar \
    /data/local/tmp/scrcpy-server.jar

# 2. Start WebSocket server on device
adb shell CLASSPATH=/data/local/tmp/scrcpy-server.jar \
    app_process / com.genymobile.scrcpy.Server \
    1.18 web info

# 3. In another terminal, start ws-scrcpy
cd ~/Documents/riset/ws-scrcpy
npm start

# 4. Open browser to ws-scrcpy web interface
open http://localhost:8000
```

---

## 📊 Comparison: Original vs NetrisTV

| Feature | Original scrcpy | NetrisTV WebSocket |
|---------|----------------|-------------------|
| **Communication** | Socket over ADB | WebSocket |
| **Client** | Desktop app (C/SDL) | Web browser (JavaScript) |
| **Network** | USB/ADB tunnel | WebSocket (WiFi/USB) |
| **Multi-client** | ❌ Single client | ✅ Multiple clients |
| **Persistent Server** | ❌ Stops when client disconnects | ✅ Keeps running |
| **File Transfer** | Via ADB | Via WebSocket |
| **Authentication** | ADB auth | ❌ None |
| **Encryption** | ADB encrypted | ❌ None |
| **Browser Support** | ❌ N/A | ✅ Modern browsers |

---

## 🛠️ Development Tips

### **Debugging WebSocket Server:**

```bash
# Enable verbose logging
adb shell CLASSPATH=/data/local/tmp/scrcpy-server.jar \
    app_process / com.genymobile.scrcpy.Server \
    1.18 web debug 8886 false

# Monitor logcat
adb logcat | grep scrcpy
```

### **Testing Connection:**

```javascript
// Simple WebSocket test in browser console
const ws = new WebSocket('ws://localhost:8886');
ws.onopen = () => console.log('Connected!');
ws.onmessage = (e) => console.log('Received:', e.data);
ws.onerror = (e) => console.error('Error:', e);
```

### **Monitoring Traffic:**

```bash
# Use Chrome DevTools
# 1. Open DevTools (F12)
# 2. Network tab
# 3. WS filter
# 4. See all WebSocket frames
```

---

## 🔄 Applying Changes to Your scrcpy

Jika ingin apply perubahan ke scrcpy Anda yang existing:

### **Option 1: Direct Integration (Recommended)**

```bash
cd ~/Documents/riset/scrcpy-c/scrcpy

# Add NetrisTV remote
git remote add netris https://github.com/NetrisTV/scrcpy.git

# Fetch their branches
git fetch netris

# Create new branch
git checkout -b websocket-integration

# Cherry-pick or merge their commits
git merge netris/feature/websocket-server

# Resolve conflicts if any
```

### **Option 2: Manual File Copy**

Copy files dari NetrisTV repo ke yours:

```bash
# Core files to copy:
WSServer.java
WebSocketConnection.java
FilePushHandler.java

# Modified files - merge changes carefully:
Server.java
Options.java
ControlMessageReader.java
```

### **Option 3: Use NetrisTV's Build Directly**

Simply use their pre-built jar dengan ws-scrcpy:

```bash
# Download from NetrisTV releases or build yourself
# Use it directly with ws-scrcpy
```

---

## 📋 Complete File List of Changes

### **New Java Classes:**

1. `WSServer.java` - Main WebSocket server
2. `WebSocketConnection.java` - Connection lifecycle management
3. `FilePushHandler.java` - File push over WebSocket
4. `SocketInfo` (inner class in WSServer) - Socket metadata

### **Modified Java Classes:**

1. `Server.java` - Entry point modifications
2. `Options.java` - WebSocket configuration options
3. `ControlMessageReader.java` - Message parsing
4. `DesktopConnection.java` - Possibly modified
5. `Controller.java` - Possibly modified for WebSocket

### **Dependencies Added:**

```gradle
implementation 'org.java-websocket:Java-WebSocket:1.5.1'
```

### **Configuration Files:**

No significant changes to build.gradle or other config files beyond dependency addition.

---

## 🎓 Key Learnings

### **Design Patterns Used:**

1. **Server Type Pattern** - Separation of concerns antara socket modes
2. **Connection Pool** - Multiple WebSocket connections management
3. **Event-driven Architecture** - WebSocket callbacks untuk async handling
4. **Persistent Service** - Server continues running independently

### **Android-specific Considerations:**

1. **PID File Management** - Track server process
2. **Display ID Support** - Multiple virtual displays
3. **Surface Encoding** - H264 encoding dari Android surface
4. **Input Injection** - Control messages → Android input events

---

## 🚦 Next Steps

### **To Use with ws-scrcpy:**

1. ✅ Build NetrisTV's modified scrcpy-server.jar
2. ✅ Place it in ws-scrcpy vendor directory
3. ✅ Start server on device
4. ✅ Connect via ws-scrcpy web interface

### **To Customize:**

1. Study `WSServer.java` untuk understand WebSocket handling
2. Modify `Options.java` untuk add custom configurations
3. Extend `ControlMessageReader.java` untuk custom control messages
4. Customize video encoding settings in `WebSocketConnection.java`

### **To Improve Security:**

1. Add authentication layer
2. Implement TLS/WSS support
3. Add rate limiting
4. Implement access control

---

## 📚 References

- **NetrisTV scrcpy fork:** https://github.com/NetrisTV/scrcpy
- **WebSocket branch:** https://github.com/NetrisTV/scrcpy/tree/feature/websocket-server
- **ws-scrcpy (web client):** https://github.com/NetrisTV/ws-scrcpy
- **Java-WebSocket library:** https://github.com/TooTallNate/Java-WebSocket

---

## 💡 Conclusion

NetrisTV's modifications are **well-architected** and **minimal intrusive**:

✅ **Pros:**
- Clean separation via server type pattern
- Backward compatible dengan original mode
- Persistent server design
- Multi-client support
- Well-integrated dengan ws-scrcpy

⚠️ **Cons:**
- No built-in security
- Requires modified server (tidak compatible dengan official scrcpy client)
- Based on older scrcpy version (v1.18)

**Recommendation:** Use NetrisTV's build directly dengan ws-scrcpy untuk best compatibility!

---

**Created:** 2025-12-17  
**Author:** Analysis of NetrisTV WebSocket Implementation
