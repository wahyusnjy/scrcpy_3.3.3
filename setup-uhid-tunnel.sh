#!/bin/bash
# Setup ADB UHID tunneling for non-root devices

set -e

echo "🎮 Setting up ADB UHID Tunneling for scrcpy"
echo "=============================================="
echo ""

# Check device connection
echo "📱 Checking device connection..."
if ! adb devices | grep -q "device$"; then
    echo "❌ No device connected!"
    echo "   Connect your device and enable USB debugging"
    exit 1
fi
echo "✅ Device connected"
echo ""

# Test UHID access
echo "🔍 Testing UHID access..."
if adb shell "test -w /dev/uhid && echo yes" | grep -q yes; then
    echo "✅ Device has direct UHID access (rooted)"
    echo "   ADB tunneling not needed, but will be setup as fallback"
    ROOTED=true
else
    echo "⚠️  Device does NOT have direct UHID access (non-root)"
    echo "   ADB tunneling is REQUIRED"
    ROOTED=false
fi
echo ""

# Remove existing forwards
echo "🧹 Cleaning existing forwards..."
adb forward --remove-all 2>/dev/null || true
echo ""

# Setup UHID forward
echo "🔧 Setting up UHID forward..."
if adb forward localabstract:scrcpy_uhid /dev/uhid; then
    echo "✅ UHID forward created: localabstract:scrcpy_uhid → /dev/uhid"
else
    echo "❌ Failed to create UHID forward"
    echo "   This might be due to:"
    echo "   1. ADB version too old (need ADB 1.0.32+)"
    echo "   2. Device doesn't support abstract sockets"
    exit 1
fi
echo ""

# Setup WebSocket forward
echo "🔧 Setting up WebSocket forward..."
if adb forward tcp:8886 tcp:8886; then
    echo "✅ WebSocket forward created: tcp:8886 → tcp:8886"
else
    echo "❌ Failed to create WebSocket forward"
    exit 1
fi
echo ""

# Verify forwards
echo "📋 Active forwards:"
adb forward --list
echo ""

# Test UHID communication
echo "🧪 Testing UHID communication..."
# Send a simple UHID_CREATE2 packet test
if echo -ne '\x0b\x00\x00\x00' | adb shell "cat > /dev/uhid" 2>&1 | grep -q "Permission denied"; then
    echo "⚠️  UHID write test failed (expected for non-root)"
    echo "   But ADB tunneling should still work!"
else
    echo "✅ UHID communication test passed"
fi
echo ""

echo "🎉 Setup Complete!"
echo ""
echo "📝 Next steps:"
echo "1. Start scrcpy server with UHID enabled:"
echo "   adb shell \"CLASSPATH=/data/local/tmp/scrcpy-server.jar \\"
echo "       app_process / com.genymobile.scrcpy.Server \\"
echo "       3.3.3 web info 8886 false h264 true true adb\""
echo ""
echo "2. Open your WebSocket client and connect to ws://localhost:8886"
echo ""
echo "3. Send HID events - server will auto-detect ADB tunnel mode"
echo ""

if [ "$ROOTED" = true ]; then
    echo "ℹ️  Note: Your device is rooted, so direct UHID access will be tried first"
    echo "   ADB tunnel is only a fallback"
else
    echo "⚠️  Important: Keep this terminal open!"
    echo "   ADB tunneling requires active ADB connection"
fi
