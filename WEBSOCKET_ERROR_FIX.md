# 🔧 WebSocket Connection Error - Troubleshooting Guide

## ❌ Error: WebSocket readyState: 3 (CLOSED)

This means the WebSocket connection failed to establish. Let's fix it!

---

## ✅ Step-by-Step Fix

### **Step 1: Start Server Properly**

Use the startup script for reliable server start:

```bash
cd /Users/macbook/Documents/riset/scrcpy-c/scrcpy

# Make executable
chmod +x start-websocket-server.sh

# Start with H264 (default)
./start-websocket-server.sh h264

# Or with H265
./start-websocket-server.sh h265
```

**Expected output:**
```
╔════════════════════════════════════════╗
║  scrcpy WebSocket Server Starter      ║
╚════════════════════════════════════════╝

Stopping any existing servers... ✓
Checking server JAR... ✓ Found (156K)
Setting up port forwarding... ✓ localhost:8886 → device:8886

Starting WebSocket server...
  Codec: h264
  Port: 8886
  Listen All: false
  Log Level: info

Server process started (local PID: XXXXX)
✓ Server process is running
✓ Running (Android PID: XXXXX)

╔════════════════════════════════════════╗
║      Server Started Successfully!      ║
╚════════════════════════════════════════╝

📡 WebSocket URL: ws://localhost:8886
🎬 Codec: h264
```

---

### **Step 2: Verify Connection**

Test WebSocket connectivity:

```bash
./test-websocket.sh
```

**Expected:**
```
1. Checking if server process is running... ✓ Running
2. Checking port forwarding... ✓ Port 8886 forwarded
3. Testing TCP connection... ✓ Port is open
4. WebSocket handshake... ✓ WebSocket handshake successful
```

---

### **Step 3: Open Player**

```bash
open scrcpy-player.html
```

In the player:
1. URL should be: `ws://localhost:8886`
2. Select decoder: **Broadway.js** (for H264)
3. Click **Connect**

---

## 🔍 Common Issues & Solutions

### **Issue 1: Server Not Running**

**Symptoms:**
- `ps | grep scrcpy` shows nothing
- Test script says "Not running"

**Fix:**
```bash
# Kill any stuck processes
adb shell pkill -f scrcpy

# Restart server
./start-websocket-server.sh h264
```

---

### **Issue 2: Port Not Forwarded**

**Symptoms:**
- Browser console: "WebSocket connection failed"
- `adb forward --list` shows nothing

**Fix:**
```bash
# Manually forward port
adb forward tcp:8886 tcp:8886

# Verify
adb forward --list
# Should show: tcp:8886 tcp:8886
```

---

### **Issue 3: Wrong JAR File**

**Symptoms:**
- Log shows: "ClassNotFoundException"
- Server crashes immediately

**Fix:**
```bash
# Push latest JAR
adb push server/build/outputs/apk/release/server-release-unsigned.apk \
    /data/local/tmp/scrcpy-server.jar

# Verify
adb shell ls -lh /data/local/tmp/scrcpy-server.jar
# Should show: 156K or similar size
```

---

### **Issue 4: Device Screen Off**

**Symptoms:**
- Connection works but no video
- Decoder shows 0 FPS

**Fix:**
```bash
# Turn screen on
adb shell input keyevent KEYCODE_WAKEUP

# Or unlock
adb shell input keyevent 82
```

---

### **Issue 5: Codec Mismatch**

**Symptoms:**
- Player connects but shows black screen
- Console shows decode errors

**Fix:**
- Server H264 → Use **Broadway.js** or **WebCodecs H264**
- Server H265 → Use **WebCodecs H265** only
- **DON'T** use Broadway.js with H265!

---

## 🧪 Manual Verification Steps

### **Check 1: Server Process**

```bash
adb shell ps | grep app_process
```

**Good:**
```
shell  12345  ...  app_process / com.genymobile.scrcpy.Server
```

**Bad:** No output → Server not running

---

### **Check 2: Port Forward**

```bash
adb forward --list
```

**Good:**
```
R9RY101CHAM tcp:8886 tcp:8886
```

**Bad:** Empty → Need to forward

---

### **Check 3: Server Logs**

```bash
adb logcat -s scrcpy:*
```

**Look for:**
```
I scrcpy  : WebSocket server mode enabled - port=8886, listenAll=false, codec=h264
I scrcpy  : Starting WebSocket server mode on port 8886
I scrcpy  : WebSocket server started successfully
```

**Bad signs:**
```
E scrcpy  : Failed to start WebSocket server
F app_process: ClassNotFoundException
```

---

### **Check 4: Network Test**

```bash
# Test if port is accessible
nc -zv localhost 8886
```

**Good:**
```
Connection to localhost port 8886 [tcp/*] succeeded!
```

**Bad:**
```
nc: connectx to localhost port 8886 failed
```

---

## 🎯 Quick Fix Checklist

Run these in order:

```bash
# 1. Kill existing server
adb shell pkill -f scrcpy

# 2. Verify JAR is present
adb shell ls -lh /data/local/tmp/scrcpy-server.jar

# 3. Forward port
adb forward tcp:8886 tcp:8886

# 4. Start server with logging
adb shell "CLASSPATH=/data/local/tmp/scrcpy-server.jar \
    app_process / com.genymobile.scrcpy.Server \
    3.3.3 web debug 8886 false h264" 2>&1 &

# 5. Wait and check
sleep 3
adb shell ps | grep scrcpy

# 6. Test connection
./test-websocket.sh

# 7. Open player
open scrcpy-player.html
```

---

## 📊 Diagnostic Commands

### **Full Environment Check:**

```bash
echo "=== Device Connection ==="
adb devices

echo -e "\n=== Server JAR ==="
adb shell ls -lh /data/local/tmp/scrcpy-server.jar

echo -e "\n=== Running Processes ==="
adb shell ps | grep scrcpy

echo -e "\n=== Port Forwarding ==="
adb forward --list

echo -e "\n=== Recent Logs ==="
adb logcat -d -s scrcpy:* | tail -20

echo -e "\n=== Network Test ==="
timeout 2 nc -zv localhost 8886 2>&1 || echo "Port not accessible"
```

---

## 🚀 Automated Fix Script

I've created helper scripts for you:

### **Start Server:**
```bash
./start-websocket-server.sh [codec]
```

- Kills existing servers
- Checks JAR file
- Forwards port
- Starts server with logging
- Verifies startup
- Shows live logs

### **Test Connection:**
```bash
./test-websocket.sh
```

- Checks server process
- Verifies port forwarding
- Tests TCP connection
- Tests WebSocket handshake
- Shows server logs

---

## 💡 Best Practices

### **Development:**

1. **Always use startup script:**
   ```bash
   ./start-websocket-server.sh h264
   ```

2. **Monitor logs in separate terminal:**
   ```bash
   adb logcat -s scrcpy:*
   ```

3. **Test before opening player:**
   ```bash
   ./test-websocket.sh
   ```

### **Production:**

1. **Use H264 for compatibility**
2. **Set `listenAll=false` for security**
3. **Use ADB port forwarding**
4. **Monitor for crashes**

---

## 🔄 Clean Restart Procedure

If everything is messed up, start fresh:

```bash
# 1. Kill everything
adb shell pkill -f scrcpy
adb forward --remove-all

# 2. Clean logs
adb logcat -c

# 3. Verify JAR
adb shell ls -lh /data/local/tmp/scrcpy-server.jar

# 4. If JAR missing or wrong, push again
adb push server/build/outputs/apk/release/server-release-unsigned.apk \
    /data/local/tmp/scrcpy-server.jar

# 5. Start fresh
./start-websocket-server.sh h264

# 6. Test
./test-websocket.sh

# 7. If all good, open player
open scrcpy-player.html
```

---

## 📝 Expected Error Messages & Meanings

| Error | Meaning | Fix |
|-------|---------|-----|
| `readyState: 3` | Connection closed/failed | Check server running & port forwarding |
| `Connection refused` | Port not accessible | Forward port: `adb forward tcp:8886 tcp:8886` |
| `ClassNotFoundException` | Wrong/corrupted JAR | Re-push JAR file |
| `Could not find class` | JAR not found | Check path: `/data/local/tmp/scrcpy-server.jar` |
| `Address already in use` | Port conflict | Kill existing: `adb shell pkill -f scrcpy` |
| Black screen | Codec mismatch or screen off | Check codec match & device screen |

---

## 🎉 Success Indicators

You'll know everything works when:

✅ Test script shows all green checkmarks  
✅ Player connects without errors  
✅ Stats show FPS > 0  
✅ Video appears in canvas  
✅ Device info displayed

---

## 📞 Still Having Issues?

1. Run diagnostic commands above
2. Share output of:
   ```bash
   ./test-websocket.sh
   adb logcat -d -s scrcpy:* | tail -50
   ```
3. Check browser console for errors

---

**Created:** 2025-12-18  
**For:** scrcpy v3.3.3 WebSocket Implementation  
**Status:** Troubleshooting Guide
