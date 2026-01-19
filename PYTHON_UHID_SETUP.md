# 🎮 Python UHID Keyboard Controller - Setup Guide

Complete setup guide for controlling Android devices via UHID keyboard using Python.

---

## 📦 **File Structure**

```
python-uhid/
├── README.md                    # This file
├── file_jar/
│   └── scrcpy-server.jar       # Custom scrcpy server (ALREADY COPIED ✅)
└── uhid_keyboard_client.py     # Python client (copy from scrcpy-c/scrcpy/)
```

---

## 🚀 **Quick Setup**

### **Step 1: Copy Python Client**

```bash
# Copy Python client to python-uhid directory
cp /Users/macbook/Documents/riset/scrcpy-c/scrcpy/uhid_keyboard_client.py \
   /Users/macbook/Documents/riset/python-uhid/
```

### **Step 2: Install Dependencies**

```bash
pip install websockets
```

### **Step 3: Push Server JAR to Android**

```bash
# Push to device
adb push /Users/macbook/Documents/riset/python-uhid/file_jar/scrcpy-server.jar \
         /data/local/tmp/scrcpy-server.jar

# Verify
adb shell "ls -l /data/local/tmp/scrcpy-server.jar"
```

Expected output:
```
-rw-rw-rw- 1 shell shell 161392 2025-12-18 16:30 /data/local/tmp/scrcpy-server.jar
```

### **Step 4: Start Server with UHID**

```bash
adb shell "CLASSPATH=/data/local/tmp/scrcpy-server.jar \
    app_process / com.genymobile.scrcpy.Server \
    3.3.3 web info 8886 false h264 false true"
```

**Expected output:**
```
[server] INFO: WebSocket server mode enabled - port=8886, keyboardUhid=true
[server] INFO: WebSocket server started on /127.0.0.1:8886
[server] INFO: ✅ UHID keyboard initialized
```

### **Step 5: Forward Port** (in another terminal)

```bash
adb forward tcp:8886 tcp:8886
```

### **Step 6: Test Python Client**

**Demo mode:**
```bash
cd /Users/macbook/Documents/riset/python-uhid
python3 uhid_keyboard_client.py
```

**Interactive mode:**
```bash
python3 uhid_keyboard_client.py interactive
```

---

## 💻 **Usage Examples**

### **Example 1: Send Tab Key**

```python
import asyncio
from uhid_keyboard_client import UhidKeyboard

async def send_tab():
    kb = UhidKeyboard('ws://localhost:8886')
    await kb.connect()
    await kb.send_key('TAB')
    await kb.close()

asyncio.run(send_tab())
```

### **Example 2: Send 10 Tabs**

```python
async def send_tabs():
    kb = UhidKeyboard()
    await kb.connect()
    
    for i in range(10):
        print(f"Tab {i+1}/10")
        await kb.send_key('TAB')
        await asyncio.sleep(0.5)
    
    await kb.close()

asyncio.run(send_tabs())
```

### **Example 3: Keyboard Shortcuts**

```python
async def shortcuts():
    kb = UhidKeyboard()
    await kb.connect()
    
    # Ctrl+A (Select All)
    await kb.send_key('A', ctrl=True)
    await asyncio.sleep(0.3)
    
    # Ctrl+C (Copy)
    await kb.send_key('C', ctrl=True)
    await asyncio.sleep(0.3)
    
    # Ctrl+V (Paste)
    await kb.send_key('V', ctrl=True)
    
    await kb.close()

asyncio.run(shortcuts())
```

### **Example 4: Navigate Menu**

```python
async def navigate():
    kb = UhidKeyboard()
    await kb.connect()
    
    # Down 3 times
    for _ in range(3):
        await kb.send_key('DOWN')
        await asyncio.sleep(0.3)
    
    # Select
    await kb.send_key('ENTER')
    
    await kb.close()

asyncio.run(navigate())
```

---

## 🎯 **Available Keys**

### **Navigation:**
- TAB, ENTER, ESCAPE, BACKSPACE, SPACE, DELETE
- UP, DOWN, LEFT, RIGHT
- HOME, END, PAGE_UP, PAGE_DOWN

### **Letters:** A-Z

### **Numbers:** 0-9

### **Modifiers:**
- `ctrl=True` - Ctrl key
- `shift=True` - Shift key
- `alt=True` - Alt key
- `gui=True` - Windows/Cmd key

---

## 📋 **Server Command Reference**

### **Basic (keyboard only):**
```bash
adb shell "CLASSPATH=/data/local/tmp/scrcpy-server.jar \
    app_process / com.genymobile.scrcpy.Server \
    3.3.3 web info 8886 false h264 false true"
```

### **Debug mode:**
```bash
adb shell "CLASSPATH=/data/local/tmp/scrcpy-server.jar \
    app_process / com.genymobile.scrcpy.Server \
    3.3.3 web debug 8886 false h264 false true"
```

### **Command format:**
```
<version> web <log> <port> <all> <codec> <mouse_uhid> <keyboard_uhid>
3.3.3    web  info  8886   false h264    false        true
```

---

## 🐛 **Troubleshooting**

### **Server not starting:**
```bash
# Check JAR exists
adb shell "ls -l /data/local/tmp/scrcpy-server.jar"

# Re-push if needed
adb push file_jar/scrcpy-server.jar /data/local/tmp/
```

### **Python can't connect:**
```bash
# Check port forwarding
adb forward --list

# Add if missing
adb forward tcp:8886 tcp:8886
```

### **Keys not working:**
```bash
# Check UHID device created
adb shell "dumpsys input | grep scrcpy"

# Should show: scrcpy-keyboard
```

---

## 📚 **Python API Reference**

### **Methods:**

**`await connect()`**
- Connect to WebSocket server
- Receives device info

**`await send_key(key, ctrl=False, shift=False, alt=False)`**
- Send key press and release
- `key`: Key name (e.g., 'TAB', 'A', 'ENTER')
- `ctrl/shift/alt`: Modifier keys

**`await send_text(text)`**
- Type text string
- Auto-handles letters and spaces

**`await send_uhid_key(modifiers, keycode)`**
- Low-level HID report send
- `modifiers`: Bitfield (0x01=Ctrl, 0x02=Shift, 0x04=Alt)
- `keycode`: HID usage code

**`await close()`**
- Close WebSocket connection

---

## ✅ **Complete Setup Checklist**

- [ ] JAR copied to python-uhid/file_jar/
- [ ] Python client copied to python-uhid/
- [ ] websockets installed (`pip install websockets`)
- [ ] Server JAR pushed to device
- [ ] Server started with UHID enabled
- [ ] Port forwarded (8886)
- [ ] Python script tested

---

## 🎉 **Ready to Use!**

Your setup is complete! Run:

```bash
cd /Users/macbook/Documents/riset/python-uhid
python3 uhid_keyboard_client.py
```

**Happy Automating! 🚀**
