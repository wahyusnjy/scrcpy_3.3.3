# 🎥 H264 Video Codec Configuration untuk ws-scrcpy

## ✅ Good News: Default Sudah H264!

**scrcpy v3.3.3 default codec:** `VideoCodec.H264`

Jadi **tidak perlu konfigurasi tambahan**! WebSocket server kita sudah otomatis streaming H264 yang compatible dengan:
- ✅ broadway.js
- ✅ tinyh264
- ✅ WebCodecs API
- ✅ ws-scrcpy client

---

## 📊 Codec yang Tersedia di scrcpy v3.3.3

```java
public enum VideoCodec {
    H264 - "video/avc"      ← DEFAULT (ws-scrcpy compatible)
    H265 - "video/hevc"     (tidak support di banyak browser)
    AV1  - "video/av01"     (support terbatas, Android 10+)
}
```

---

## 🔧 Cara Memastikan H264 (Explicit Configuration)

Meskipun default sudah H264, jika ingin **eksplisit** mengatur codec:

### **Option 1: Via Code (Hardcode)**

Edit `Options.java`:

```java
// Line 30 - Already default!
private VideoCodec videoCodec = VideoCodec.H264;  // ✅ Sudah H264
```

### **Option 2: Via Command Line (Flexible)**

Untuk socket mode (bukan WebSocket), bisa set via argument:

```bash
# Socket mode (original scrcpy)
scrcpy --video-codec=h264

# Atau
scrcpy --video-codec=h265  # untuk H265
```

**TAPI** untuk WebSocket mode, kita menggunakan simplified parsing yang tidak support video codec parameter (yet).

---

## 🎯 Untuk WebSocket Mode

### **Current Implementation:**

WebSocket mode menggunakan **default options**, yang berarti:

```java
Options options = new Options();  // Creates with defaults
// videoCodec = VideoCodec.H264 ← Sudah H264!
```

### **Verification:**

Lihat di `WebSocketVideoStreamer.java` line 42:

```java
Streamer streamer = new Streamer(
    writeFd, 
    options.getVideoCodec(),  // ← Ini akan return H264
    options.getSendCodecMeta(), 
    options.getSendFrameMeta()
);
```

---

## 🚀 Cara Menggunakan dengan ws-scrcpy

### **Step 1: Deploy Server (Default H264)**

```bash
cd /Users/macbook/Documents/riset/scrcpy-c/scrcpy

# Build (jika belum)
./gradlew assembleRelease

# Deploy
adb push server/build/outputs/apk/release/server-release-unsigned.apk \
    /data/local/tmp/scrcpy-server.jar

# Start WebSocket server (H264 by default)
adb shell CLASSPATH=/data/local/tmp/scrcpy-server.jar \
    app_process / com.genymobile.scrcpy.Server \
    3.3.3 web info 8886 false

# Forward port
adb forward tcp:8886 tcp:8886
```

### **Step 2: Configure ws-scrcpy Decoder**

ws-scrcpy akan otomatis detect H264 stream dan pilih decoder yang appropriate:

**Priority Order:**
1. **WebCodecs** (Modern browsers, hardware accelerated)
2. **broadway.js** (Software decoder, fallback)
3. **tinyh264** (Lightweight alternative)

**ws-scrcpy config** (di `src/client/index.ts` atau similar):

```typescript
const decoder = new VideoDecoder({
    codec: 'avc1.42E01E',  // H264 Baseline Profile
    // or 'avc1.64001F' for High Profile
    optimizeForLatency: true
});
```

---

## 📝 H264 Stream Format

WebSocket server kita mengirim:

```
[Header: 12 bytes]  [H264 NAL Units]
  ↓                    ↓
  PTS + Size           Video data
```

**Format detail:**
- **Byte 0-7:** PTS (Presentation Time Stamp) + flags
- **Byte 8-11:** Packet size (int)
- **Byte 12+:** H264 NAL units

ws-scrcpy expects:
- **H264 Annex-B format** (with start codes `00 00 00 01`)
- **SPS/PPS** (Sequence/Picture Parameter Sets) di config packets
- **I-frames** (keyframes) periodic

---

## 🔍 Debugging H264 Stream

### **Check Codec Being Used:**

```bash
# Monitor logcat untuk codec info
adb logcat | grep -i "codec\|h264\|video"

# Look for lines like:
# "Using video codec: h264"
# "MediaCodec encoder: OMX.google.h264.encoder"
```

### **Verify Stream with ffprobe:**

```bash
# Capture raw stream
adb forward tcp:8886 tcp:8886

# Use ffprobe (if you have ffmpeg installed)
ffprobe -i ws://localhost:8886 -show_streams
```

### **Browser Console Check:**

```javascript
const ws = new WebSocket('ws://localhost:8886');

ws.onmessage = (event) => {
    if (event.data instanceof Blob) {
        event.data.arrayBuffer().then(buffer => {
            const view = new DataView(buffer);
            
            // Check for H264 NAL unit start code
            if (view.getUint32(0) === 0x00000001 || 
                (view.getUint32(0) & 0xFFFFFF00) === 0x00000100) {
                console.log('✅ H264 NAL unit detected!');
                
                // NAL unit type
                const nalType = view.getUint8(4) & 0x1F;
                console.log('NAL Type:', nalType);
                // 7 = SPS, 8 = PPS, 5 = IDR frame, 1 = P frame
            }
        });
    }
};
```

---

## ⚙️ Advanced: Custom Codec Configuration (Future)

Jika nanti ingin support codec selection via WebSocket arguments:

### **Modify Options.parse():**

```java
// In Options.java, line ~350 (WebSocket mode parsing)
if (args.length > 5) {
    String codec = args[5].toLowerCase();
    if ("h265".equals(codec)) {
        options.videoCodec = VideoCodec.H265;
    } else if ("av1".equals(codec)) {
        options.videoCodec = VideoCodec.AV1;
    } else {
        options.videoCodec = VideoCodec.H264;  // default
    }
}
```

### **Usage:**

```bash
adb shell CLASSPATH=/data/local/tmp/scrcpy-server.jar \
    app_process / com.genymobile.scrcpy.Server \
    3.3.3 web info 8886 false h264
    #                          ↑
    #                        codec
```

---

## 🎬 H264 Encoder Settings (Optional)

Untuk kontrol lebih detail, bisa set encoder options:

### **Bitrate:**

```java
// In Options.java
private int videoBitRate = 8000000;  // 8 Mbps default
```

Bisa diubah untuk quality/bandwidth trade-off:
- **Low:** 2-4 Mbps (mobile data)
- **Medium:** 8 Mbps (default, WiFi)
- **High:** 16+ Mbps (LAN, high quality)

### **Frame Rate:**

```java
private float maxFps;  // 0 = unlimited (default)
```

Set ke 30 atau 60 untuk consistent frame rate.

### **Resolution:**

```java
private int maxSize;  // Max dimension (width or height)
```

Examples:
- `1920` for Full HD
- `1280` for HD
- `720` for performance

---

## 🔄 ws-scrcpy Integration Flow

```
Android Screen
     ↓
MediaCodec Encoder (H264)
     ↓
Streamer (writes to pipe)
     ↓
WebSocketVideoStreamer (reads from pipe)
     ↓
WebSocket.send(h264_data)
     ↓
Browser WebSocket
     ↓
ws-scrcpy Decoder (broadway.js / WebCodecs)
     ↓
Canvas Rendering
```

---

## ✅ Compatibility Matrix

| Component | H264 Support | Notes |
|-----------|--------------|-------|
| **Android Encoder** | ✅ Yes | All Android 5+ |
| **scrcpy v3.3.3** | ✅ Yes | Default codec |
| **WebSocket Transport** | ✅ Yes | Binary frames |
| **broadway.js** | ✅ Yes | Software decoder |
| **tinyh264** | ✅ Yes | Lightweight |
| **WebCodecs** | ✅ Yes | Hardware accelerated |
| **ws-scrcpy** | ✅ Yes | Fully compatible |

| Browser | WebCodecs H264 | broadway.js |
|---------|----------------|-------------|
| Chrome 94+ | ✅ Yes | ✅ Yes |
| Edge 94+ | ✅ Yes | ✅ Yes |
| Firefox | ⚠️ Partial | ✅ Yes |
| Safari 16+ | ✅ Yes | ✅ Yes |

---

## 🎯 tldr; Quick Answer

**Q: Codec apa yang digunakan?**  
**A:** H264 (default, automatic)

**Q: Perlu konfigurasi?**  
**A:** Tidak! Sudah default H264

**Q: Compatible dengan ws-scrcpy?**  
**A:** 100% compatible!

**Q: Cara deploy?**  
**A:** 
```bash
adb push server-release-unsigned.apk /data/local/tmp/scrcpy-server.jar
adb shell CLASSPATH=/data/local/tmp/scrcpy-server.jar \
    app_process / com.genymobile.scrcpy.Server \
    3.3.3 web info 8886 false
adb forward tcp:8886 tcp:8886
```

**Q: Decoder di browser?**  
**A:** ws-scrcpy otomatis pilih terbaik:
- WebCodecs (hardware) jika support
- broadway.js (software) sebagai fallback

---

## 🚀 Ready to Stream!

Your WebSocket scrcpy server **sudah configured** untuk H264 streaming yang optimal untuk ws-scrcpy!

**Next:** Deploy dan test!

```bash
# Quick deploy
./quick-deploy.sh

# Or manual
adb push server/build/outputs/apk/release/server-release-unsigned.apk \
    /data/local/tmp/scrcpy-server.jar
adb shell CLASSPATH=/data/local/tmp/scrcpy-server.jar \
    app_process / com.genymobile.scrcpy.Server \
    3.3.3 web info 8886 false
adb forward tcp:8886 tcp:8886
```

---

**Documentation:** `H264_CODEC_GUIDE.md`  
**Created:** 2025-12-18  
**scrcpy Version:** 3.3.3  
**Default Codec:** ✅ H264
