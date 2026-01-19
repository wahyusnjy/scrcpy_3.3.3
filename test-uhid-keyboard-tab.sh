#!/bin/bash
# Test UHID Keyboard - Tab Key (Pure Binary Method)

set -e

echo "⌨️  Testing UHID Keyboard (Tab Key) - Pure Binary"
echo "=================================================="
echo ""

echo "🔍 Step 1: Check UHID access..."
if adb shell "test -e /dev/uhid && echo yes" | grep -q yes; then
    echo "✅ /dev/uhid exists"
else
    echo "❌ /dev/uhid not found"
    exit 1
fi
echo ""

echo "⌨️  Step 2: Creating UHID keyboard device..."

# Create UHID_CREATE2 packet using printf (pure shell)
# We'll generate hex and convert to binary

# Generate UHID_CREATE2 (simplified version)
UHID_CREATE2_HEX=$(cat << 'HEXDATA'
0b000000
7363726370792d6b626400000000000000000000000000000000000000000000
0000000000000000000000000000000000000000000000000000000000000000
0000000000000000000000000000000000000000000000000000000000000000
0000000000000000
0000000000000000000000000000000000000000000000000000000000000000
0000000000000000000000000000000000000000000000000000000000000000
0000000000000000000000000000000000000000000000000000000000000000
0000000000000000
3f00
0600
d1180000
ff5a0000
01000000
00000000
05010906a1010507850119e029e71500250175019508810295017508810395057501050819012905910295017503910395067508150025650507190029658100c0
HEXDATA
)

# Remove newlines and convert hex to binary using xxd
echo "$UHID_CREATE2_HEX" | tr -d '\n' | xxd -r -p > /tmp/uhid_create.bin

# Send to device
echo "📤 Sending UHID_CREATE2 ($(wc -c < /tmp/uhid_create.bin) bytes)..."
adb push /tmp/uhid_create.bin /data/local/tmp/ > /dev/null 2>&1
adb shell "cat /data/local/tmp/uhid_create.bin > /dev/uhid && echo 'OK'" | grep -q OK && \
    echo "✅ UHID keyboard created" || echo "⚠️  May need root"

sleep 1
echo ""

echo "⌨️  Step 3: Sending Tab key press..."

# UHID_INPUT2 for Tab key DOWN
# Type=12(0x0c), Size=8, Report=[0,0,0x2b,0,0,0,0,0]
TAB_DOWN_HEX="0c000000 0800 00002b0000000000"

# Tab key UP (all zeros)
TAB_UP_HEX="0c000000 0800 0000000000000000"

# Generate binary
echo "$TAB_DOWN_HEX" | tr -d ' ' | xxd -r -p > /tmp/uhid_tab_down.bin
echo "$TAB_UP_HEX" | tr -d ' ' | xxd -r -p > /tmp/uhid_tab_up.bin

# Send Tab DOWN
echo "  ⬇️  Tab key DOWN..."
adb push /tmp/uhid_tab_down.bin /data/local/tmp/ > /dev/null 2>&1
adb shell "cat /data/local/tmp/uhid_tab_down.bin > /dev/uhid"

sleep 0.1

# Send Tab UP
echo "  ⬆️  Tab key UP..."
adb push /tmp/uhid_tab_up.bin /data/local/tmp/ > /dev/null 2>&1
adb shell "cat /data/local/tmp/uhid_tab_up.bin > /dev/uhid"

echo ""
echo "✅ Tab key sent successfully!"
echo ""

echo "🔍 To verify:"
echo "   - Check if focus changed in active app"
echo "   - Run: adb shell getevent -l | grep KEY_TAB"
echo ""

# Cleanup
echo "🧹 Cleanup..."
sleep 1

# UHID_DESTROY
DESTROY_HEX="01000000"
echo "$DESTROY_HEX" | xxd -r -p > /tmp/uhid_destroy.bin
adb push /tmp/uhid_destroy.bin /data/local/tmp/ > /dev/null 2>&1
adb shell "cat /data/local/tmp/uhid_destroy.bin > /dev/uhid" 2>/dev/null

# Remove temp files
rm -f /tmp/uhid_*.bin
adb shell "rm -f /data/local/tmp/uhid_*.bin" 2>/dev/null

echo "✨ Test complete!"
echo ""
echo "💡 Tip: If permission denied, device may need root for UHID access"
