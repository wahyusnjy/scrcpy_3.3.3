# WebSocket Touch Event Protocol

## Touch Event Format

Untuk mengirim touch/tap events ke Android device melalui WebSocket, kirim binary message dengan format berikut:

### Binary Format
```
[type:1][action:4][pointerId:8][x:4][y:4][pressure:4][actionButton:4][buttons:4]
Total: 33 bytes
```

### Field Details:
- **type** (1 byte): `103` (TYPE_INJECT_TOUCH)
- **action** (4 bytes, int32, big-endian): Android MotionEvent action
  - `0` = ACTION_DOWN (touch start)
  - `1` = ACTION_UP (touch end)
  - `2` = ACTION_MOVE (touch move/drag)
- **pointerId** (8 bytes, int64, big-endian): Pointer ID (biasanya `0` untuk single touch)
- **x** (4 bytes, int32, big-endian): Koordinat X (pixels)
- **y** (4 bytes, int32, big-endian): Koordinat Y (pixels)
- **pressure** (4 bytes, float32, big-endian): Pressure (biasanya `1.0`)
- **actionButton** (4 bytes, int32, big-endian): Button state (biasanya `0`)
- **buttons** (4 bytes, int32, big-endian): Buttons pressed (biasanya `0`)

## Client-Side JavaScript Example

```javascript
function sendTouchEvent(ws, action, x, y) {
    const buffer = new ArrayBuffer(33);
    const view = new DataView(buffer);
    let offset = 0;
    
    // Type (1 byte)
    view.setUint8(offset, 103); // TYPE_INJECT_TOUCH
    offset += 1;
    
    // Action (4 bytes)
    view.setInt32(offset, action, false); // big-endian
    offset += 4;
    
    // Pointer ID (8 bytes)
    view.setBigInt64(offset, 0n, false); // big-endian
    offset += 8;
    
    // X coordinate (4 bytes)
    view.setInt32(offset, Math.round(x), false);
    offset += 4;
    
    // Y coordinate (4 bytes)
    view.setInt32(offset, Math.round(y), false);
    offset += 4;
    
    // Pressure (4 bytes as float)
    view.setFloat32(offset, 1.0, false);
    offset += 4;
    
    // Action button (4 bytes)
    view.setInt32(offset, 0, false);
    offset += 4;
    
    // Buttons (4 bytes)
    view.setInt32(offset, 0, false);
    
    ws.send(buffer);
}

// Constants for actions
const ACTION_DOWN = 0;
const ACTION_UP = 1;
const ACTION_MOVE = 2;

// Example: Send tap at (100, 200)
function sendTap(ws, x, y) {
    sendTouchEvent(ws, ACTION_DOWN, x, y);
    setTimeout(() => {
        sendTouchEvent(ws, ACTION_UP, x, y);
    }, 50); // 50ms delay
}

// Example: Mouse event handler
const videoElement = document.getElementById('video');
videoElement.addEventListener('mousedown', (e) => {
    const rect = videoElement.getBoundingClientRect();
    const scaleX = videoElement.videoWidth / rect.width;
    const scaleY = videoElement.videoHeight / rect.height;
    
    const x = (e.clientX - rect.left) * scaleX;
    const y = (e.clientY - rect.top) * scaleY;
    
    sendTouchEvent(ws, ACTION_DOWN, x, y);
});

videoElement.addEventListener('mouseup', (e) => {
    const rect = videoElement.getBoundingClientRect();
    const scaleX = videoElement.videoWidth / rect.width;
    const scaleY = videoElement.videoHeight / rect.height;
    
    const x = (e.clientX - rect.left) * scaleX;
    const y = (e.clientY - rect.top) * scaleY;
    
    sendTouchEvent(ws, ACTION_UP, x, y);
});

videoElement.addEventListener('mousemove', (e) => {
    if (e.buttons === 1) { // Left mouse button pressed
        const rect = videoElement.getBoundingClientRect();
        const scaleX = videoElement.videoWidth / rect.width;
        const scaleY = videoElement.videoHeight / rect.height;
        
        const x = (e.clientX - rect.left) * scaleX;
        const y = (e.clientY - rect.top) * scaleY;
        
        sendTouchEvent(ws, ACTION_MOVE, x, y);
    }
});
```

## Touch Event Support

### Supported Actions:
- ✅ **Tap**: Send ACTION_DOWN followed by ACTION_UP
- ✅ **Long Press**: Send ACTION_DOWN, wait, then ACTION_UP
- ✅ **Swipe/Drag**: Send ACTION_DOWN, multiple ACTION_MOVE, then ACTION_UP
- ✅ **Multi-touch**: Use different pointerIds for each finger

### Requirements:
- ✅ **No root required** - Uses Android Input system
- ✅ **Works in WebSocket mode** - Make sure control is enabled
- ✅ **Coordinates**: Use actual device resolution (not scaled)

## Troubleshooting

### Touch not responding:
1. **Check control enabled**: Make sure `options.control = true` (uncomment the line in Options.java)
2. **Check logs**: Look for `📱 Touch event:` in logcat
3. **Verify coordinates**: X, Y must be within device screen bounds
4. **Check WebSocket connection**: Ensure binary messages are being sent

### Check Server Logs:
```bash
adb logcat | grep "Touch event"
```

You should see:
```
📱 Touch event: action=0, x=100, y=200, pressure=1.0, pointerId=0
✅ Touch injected: (100, 200)
```
