# ⌨️ **HID Keyboard Key Codes Reference**

## 🎯 **Common Keys:**

| Key | HID Code | Hex | Description |
|-----|----------|-----|-------------|
| **Navigation** ||||
| Tab | 43 | 0x2B | Tab key |
| Enter | 40 | 0x28 | Return/Enter |
| Escape | 41 | 0x29 | Esc key |
| Backspace | 42 | 0x2A | Backspace |
| Delete | 76 | 0x4C | Delete forward |
| **Arrows** ||||
| Right Arrow | 79 | 0x4F | → |
| Left Arrow | 80 | 0x50 | ← |
| Down Arrow | 81 | 0x51 | ↓ |
| Up Arrow | 82 | 0x52 | ↑ |
| **Letters** ||||
| A | 4 | 0x04 | Letter A |
| B | 5 | 0x05 | Letter B |
| ... | ... | ... | ... |
| Z | 29 | 0x1D | Letter Z |
| **Numbers** ||||
| 1 | 30 | 0x1E | Number 1 |
| 2 | 31 | 0x1F | Number 2 |
| ... | ... | ... | ... |
| 0 | 39 | 0x27 | Number 0 |
| **Special** ||||
| Space | 44 | 0x2C | Spacebar |
| Minus | 45 | 0x2D | - |
| Equals | 46 | 0x2E | = |
| Left Bracket | 47 | 0x2F | [ |
| Right Bracket | 48 | 0x30 | ] |

## 🎮 **Modifier Keys (Byte 0):**

| Modifier | Bit | Hex | Description |
|----------|-----|-----|-------------|
| Left Ctrl | 0 | 0x01 | Left Control |
| Left Shift | 1 | 0x02 | Left Shift |
| Left Alt | 2 | 0x04 | Left Alt |
| Left GUI | 3 | 0x08 | Left Windows/Cmd |
| Right Ctrl | 4 | 0x10 | Right Control |
| Right Shift | 5 | 0x20 | Right Shift |
| Right Alt | 6 | 0x40 | Right AltGr |
| Right GUI | 7 | 0x80 | Right Windows/Cmd |

## 📝 **HID Report Format (8 bytes):**

```
[Byte 0] Modifier keys (bitfield)
[Byte 1] Reserved (always 0x00)
[Byte 2] Key code 1 (or 0x00 if no key)
[Byte 3] Key code 2 (or 0x00 if no key)
[Byte 4] Key code 3 (or 0x00 if no key)
[Byte 5] Key code 4 (or 0x00 if no key)
[Byte 6] Key code 5 (or 0x00 if no key)
[Byte 7] Key code 6 (or 0x00 if no key)
```

## 🚀 **Quick Examples:**

### **Send Tab:**
```bash
./test-uhid-keyboard-tab.sh
```

### **Send Ctrl+C:**
```python
# Modifiers: 0x01 (Left Ctrl)
# Key: 0x06 (C)
report = [0x01, 0x00, 0x06, 0x00, 0x00, 0x00, 0x00, 0x00]
```

### **Send Shift+Tab:**
```python
# Modifiers: 0x02 (Left Shift)
# Key: 0x2B (Tab)
report = [0x02, 0x00, 0x2B, 0x00, 0x00, 0x00, 0x00, 0x00]
```

### **Send Alt+F4:**
```python
# Modifiers: 0x04 (Left Alt)
# Key: 0x3D (F4)
report = [0x04, 0x00, 0x3D, 0x00, 0x00, 0x00, 0x00, 0x00]
```

### **Send Multiple Keys (A+B+C):**
```python
# No modifiers
# Keys: A(0x04), B(0x05), C(0x06)
report = [0x00, 0x00, 0x04, 0x05, 0x06, 0x00, 0x00, 0x00]
```

## 🔧 **Test Commands:**

### **Tab:**
```bash
python3 -c "import subprocess, struct
packet = bytearray(14)
struct.pack_into('<I', packet, 0, 12)
struct.pack_into('<H', packet, 4, 8)
packet[6:14] = [0x00, 0x00, 0x2B, 0x00, 0x00, 0x00, 0x00, 0x00]
subprocess.run(['adb', 'shell', 'cat > /dev/uhid'], input=packet)"
```

### **Enter:**
```bash
python3 -c "import subprocess, struct
packet = bytearray(14)
struct.pack_into('<I', packet, 0, 12)
struct.pack_into('<H', packet, 4, 8)
packet[6:14] = [0x00, 0x00, 0x28, 0x00, 0x00, 0x00, 0x00, 0x00]
subprocess.run(['adb', 'shell', 'cat > /dev/uhid'], input=packet)"
```

### **Ctrl+A (Select All):**
```bash
python3 -c "import subprocess, struct
packet = bytearray(14)
struct.pack_into('<I', packet, 0, 12)
struct.pack_into('<H', packet, 4, 8)
packet[6:14] = [0x01, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00]
subprocess.run(['adb', 'shell', 'cat > /dev/uhid'], input=packet)"
```

## 📚 **Full HID Usage Table:**

For complete list, see: [USB HID Usage Tables](https://www.usb.org/sites/default/files/documents/hut1_12v2.pdf)

Page 53: Keyboard/Keypad usage IDs

## 💡 **Tips:**

1. **Always send key release** (all zeros) after key press
2. **Wait 50-100ms** between press and release
3. **Modifier keys** can be combined (e.g., `0x03` = Ctrl+Shift)
4. **Maximum 6 keys** can be pressed simultaneously
5. **Use getevent** to verify: `adb shell getevent -l`

---

**Created:** 2025-12-18  
**Usage:** Combined with `test-uhid-keyboard-tab.sh`
