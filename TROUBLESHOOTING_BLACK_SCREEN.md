# 🔧 Troubleshooting: Layar Tidak Muncul di ws-scrcpy

## ❌ Problem: Layar Hitam / Tidak Ada Video

Dari screenshot yang kamu kirim, masalahnya adalah **ws-scrcpy tidak connect ke WebSocket server kita**!

---

## 🔍 Root Cause Analysis

### **Problem 1: ws-scrcpy Mode Mismatch**

ws-scrcpy punya **2 connection modes:**

#### **Mode 1: proxy-adb (Default)** ← Yang kamu pakai sekarang
```
Browser → ws-scrcpy Node.js Server → ADB → Android Device
```
- URL: `ws://localhost:8000/?action=proxy-adb`
- Requires: **Node.js proxy server** + **original scrcpy-server**
  
#### **Mode 2: Direct WebSocket** ← Yang kita butuh
```
Browser → Direct WebSocket → Android Device (WebSocket scrcpy-server)
```
- URL: `ws://localhost:8886` (direct to our server)
- Requires: **WebSocket-enabled scrcpy-server** (yang kita build)

**Kamu currently di Mode 1, but we need Mode 2!**

---

## ✅ Solution: 3 Options

### **Option A: Use Simple HTML Test Client** (Recommended untuk Debug)

Saya sudah buatkan debug tool. Gunakan ini dulu untuk verify server works:

```bash
# 1. Start WebSocket server
adb shell CLASSPATH=/data/local/tmp/scrcpy-server.jar \
    app_process / com.genymobile.scrcpy.Server \
    3.3.3 web info 8886 false

# 2. Forward port
adb forward tcp:8886 tcp:8886

# 3. Open debug tool
open /Users/macbook/Documents/riset/scrcpy-c/scrcpy/websocket-debug.html
```

Jika **debug tool shows video frames** → Server OK, ws-scrcpy configuration issue  
Jika **debug tool shows nothing** → Server issue, need to fix

---

### **Option B: Configure ws-scrcpy untuk Direct WebSocket**

ws-scrcpy perlu di-configure untuk connect direct ke server kita.

**Locate ws-scrcpy directory first:**

```bash
# Find ws-scrcpy
find ~ -name "ws-scrcpy" -type d 2>/dev/null

# Or if you know the location:
cd ~/path/to/ws-scrcpy
```

**Then configure direct WebSocket mode:**

Ada 2 cara:

#### **B.1: Via URL Parameter (Quick Test)**

Buka browser dengan URL custom:

```
http://localhost:8000/#!action=stream&udid=YOUR_DEVICE_ID&player=broadway&ws=ws://localhost:8886
```

Replace `YOUR_DEVICE_ID` dengan device ID kamu (lihat dari `adb devices`).

**URL Parameters explained:**
- `action=stream` - Stream mode
- `udid=R9RY101CHAM` - Your device ID
- `player=broadway` - Use broadway.js decoder
- `ws=ws://localhost:8886` - **Direct WebSocket URL**

#### **B.2: Modify ws-scrcpy Source (Permanent)**

Edit ws-scrcpy config file (biasanya `src/common/Constants.ts` or similar):

```typescript
// Before:
export const WS_URL = 'ws://localhost:8000';  // Node.js proxy

// After:
export const WS_URL = 'ws://localhost:8886';  // Direct WebSocket
```

Then rebuild:
```bash
cd ~/path/to/ws-scrcpy
npm run build
```

---

### **Option C: Use ws-scrcpy dengan Node.js Proxy** (Original Way)

Jika mau pakai ws-scrcpy cara original (via proxy):

**Pros:**
- ✅ Full ws-scrcpy features work
- ✅ File push, APK install, dll
- ✅ UI controls complete

**Cons:**
- ❌ Perlu Node.js proxy running
- ❌ Tidak menggunakan WebSocket server kita
- ❌ More complex architecture

**Setup:**

```bash
cd ~/path/to/ws-scrcpy

# 1. Copy our JAR ke ws-scrcpy vendor
cp /Users/macbook/Documents/riset/scrcpy-c/scrcpy/server/build/outputs/apk/release/server-release-unsigned.apk \
   dist/vendor/scrcpy-server.jar

# 2. Start ws-scrcpy server
npm start

# 3. Open browser
open http://localhost:8000

# 4. Di UI, pilih device → start stream
```

**TAPI:** Ini akan pakai Node.js proxy, **bukan direct WebSocket** yang kita build.

---

## 🧪 Diagnostic Steps

### **Step 1: Verify Our WebSocket Server Works**

```bash
# Terminal 1: Start server
adb shell CLASSPATH=/data/local/tmp/scrcpy-server.jar \
    app_process / com.genymobile.scrcpy.Server \
    3.3.3 web debug 8886 false

# Terminal 2: Monitor logs
adb logcat | grep -i scrcpy

# Look for:
# "WebSocket server started on ..."
# "WebSocket client #X connected from ..."
# "Sent initial device info to client #X"
```

### **Step 2: Test with Debug Tool**

```bash
open /Users/macbook/Documents/riset/scrcpy-c/scrcpy/websocket-debug.html
```

**Expected output:**
```
✅ WebSocket connected!
📱 Device: Google Pixel 6
🎥 H264 Frame: 12345 bytes, NAL Type: 7 (SPS)
🎥 H264 Frame: 67890 bytes, NAL Type: 5 (IDR)
```

### **Step 3: Test with Browser Console**

```javascript
// Open Chrome DevTools → Console
const ws = new WebSocket('ws://localhost:8886');

ws.onopen = () => console.log('✅ Connected');

ws.onmessage = (e) => {
    if (typeof e.data === 'string') {
        console.log('Device info:', JSON.parse(e.data));
    } else {
        console.log('Video frame:', e.data.size, 'bytes');
    }
};

// Expected:
// ✅ Connected
// Device info: {type: "deviceInfo", device: {...}}
// Video frame: 12345 bytes
// Video frame: 67890 bytes
```

---

## 🎯 Most Likely Issue

Based on your screenshot, **ws-scrcpy is trying to connect via Node.js proxy**, not direct to our WebSocket server.

**Evidence:**
- URL shows: `action=proxy-adb`
- Port: `8000` (ws-scrcpy Node.js server)
- Not: `8886` (our WebSocket server)

---

## ✅ Quick Fix (Recommended)

### **For Quick Test:**

1. **Start our WebSocket server:**
```bash
adb shell CLASSPATH=/data/local/tmp/scrcpy-server.jar \
    app_process / com.genymobile.scrcpy.Server \
    3.3.3 web debug 8886 false

adb forward tcp:8886 tcp:8886
```

2. **Open debug tool to verify:**
```bash
open /Users/macbook/Documents/riset/scrcpy-c/scrcpy/websocket-debug.html
```

3. **If debug tool shows video frames, create simple HTML player:**

```html
<!DOCTYPE html>
<html>
<head>
    <title>scrcpy WebSocket Player</title>
</head>
<body>
    <h1>scrcpy WebSocket Direct</h1>
    <canvas id="screen" width="1280" height="720"></canvas>
    <div id="status">Connecting...</div>
    <div id="stats"></div>
    
    <script src="https://cdn.jsdelivr.net/npm/broadway-player@1.0.5/dist/Broadway.min.js"></script>
    <script>
        // Broadway.js H264 decoder
        const canvas = document.getElementById('screen');
        const player = new Player({
            useWorker: true,
            workerFile: 'https://cdn.jsdelivr.net/npm/broadway-player@1.0.5/dist/Decoder.js',
            webgl: true,
            size: { width: 1280, height: 720}
        });
        
        player.canvas = canvas;
        
        // WebSocket connection
        const ws = new WebSocket('ws://localhost:8886');
        let frameCount = 0;
        
        ws.onopen = () => {
            document.getElementById('status').textContent = '✅ Connected';
        };
        
        ws.onmessage = (event) => {
            if (event.data instanceof Blob) {
                event.data.arrayBuffer().then(buffer => {
                    // Decode H264 with broadway.js
                    player.decode(new Uint8Array(buffer));
                    frameCount++;
                    document.getElementById('stats').textContent = 
                        `Frames: ${frameCount}`;
                });
            } else if (typeof event.data === 'string') {
                console.log('Device info:', JSON.parse(event.data));
            }
        };
        
        ws.onerror = (err) => {
            document.getElementById('status').textContent = '❌ Error: ' + err;
        };
        
        ws.onclose = () => {
            document.getElementById('status').textContent = '🔌 Disconnected';
        };
    </script>
</body>
</html>
```

Save as `simple-player.html` dan open di browser.

---

## 📊 Expected vs Actual

### **What Should Happen:**

1. ✅ WebSocket server starts on port 8886
2. ✅ Client connects to ws://localhost:8886
3. ✅ Device info JSON received
4. ✅ H264 video frames received
5. ✅ Broadway.js decodes H264
6. ✅ Canvas shows video

### **What's Happening:**

1. ✅ ws-scrcpy UI opens
2. ❌ But connects to ws://localhost:8000 (Node.js proxy)
3. ❌ Proxy tries to use ADB → scrcpy-server
4. ❌ But our scrcpy-server is in WebSocket mode, not ADB mode
5. ❌ **Mismatch** → Black screen

---

## 🎯 Action Plan

**Do this in order:**

1. **Test WebSocket server dengan debug tool:**
   ```bash
   open /Users/macbook/Documents/riset/scrcpy-c/scrcpy/websocket-debug.html
   ```
   
2. **If debug tool works:**
   - ✅ Server is OK
   - ❌ ws-scrcpy configuration issue
   - → Use custom URL or modify ws-scrcpy
   
3. **If debug tool doesn't work:**
   - ❌ Server issue
   - → Check logs: `adb logcat | grep scrcpy`
   - → Share logs with me

---

## 📝 Quick Checklist

Before debugging further, verify:

- [ ] WebSocket server is running (`adb shell ps | grep app_process`)
- [ ] Port is forwarded (`adb forward --list`)
- [ ] No firewall blocking port 8886
- [ ] Device screen is ON
- [ ] Server logs show "WebSocket server started"
- [ ] Server logs show client connections

---

**Next:** Try the debug tool dan kasih tau hasilnya! 🚀
