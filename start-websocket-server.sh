#!/bin/bash
# Start scrcpy WebSocket Server
# Usage: ./start-websocket-server.sh [codec]
# Example: ./start-websocket-server.sh h264

set -e

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

# Configuration
CODEC=${1:-h264}  # Default to h264
PORT=8886
LOG_LEVEL="info"
LISTEN_ALL="false"

echo -e "${GREEN}╔════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║  scrcpy WebSocket Server Starter      ║${NC}"
echo -e "${GREEN}╚════════════════════════════════════════╝${NC}"
echo ""

# Kill existing server
echo -n "Stopping any existing servers... "
adb shell "pkill -f 'com.genymobile.scrcpy.Server'" 2>/dev/null || true
sleep 1
echo -e "${GREEN}✓${NC}"

# Check if JAR exists
echo -n "Checking server JAR... "
if adb shell "[ -f /data/local/tmp/scrcpy-server.jar ]"; then
    SIZE=$(adb shell "ls -lh /data/local/tmp/scrcpy-server.jar" |  awk '{print $5}')
    echo -e "${GREEN}✓${NC} Found ($SIZE)"
else
    echo -e "${RED}✗${NC} Not found!"
    echo "Please push JAR first:"
    echo "  adb push server/build/outputs/apk/release/server-release-unsigned.apk \\"
    echo "       /data/local/tmp/scrcpy-server.jar"
    exit 1
fi

# Setup port forwarding
echo -n "Setting up port forwarding... "
adb forward tcp:$PORT tcp:$PORT 2>/dev/null
echo -e "${GREEN}✓${NC} localhost:$PORT → device:$PORT"

# Clear logcat
adb logcat -c 2>/dev/null

# Start server
echo ""
echo -e "${YELLOW}Starting WebSocket server...${NC}"
echo "  Codec: $CODEC"
echo "  Port: $PORT"
echo "  Listen All: $LISTEN_ALL"
echo "  Log Level: $LOG_LEVEL"
echo ""

# Run in background and capture output
adb shell "CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server 3.3.3 web $LOG_LEVEL $PORT $LISTEN_ALL $CODEC" > /tmp/scrcpy-server.log 2>&1 &
SERVER_PID=$!

echo "Server process started (local PID: $SERVER_PID)"
echo "Waiting for server to initialize..."
sleep 2

# Check if process is still running
if ps -p $SERVER_PID > /dev/null 2>&1; then
    echo -e "${GREEN}✓${NC} Server process is running"
else
    echo -e "${RED}✗${NC} Server process died!"
    echo "Check logs:"
    cat /tmp/scrcpy-server.log
    exit 1
fi

# Check Android process
echo -n "Checking Android process... "
if adb shell ps | grep -q "app_process.*scrcpy"; then
    PID=$(adb shell ps | grep "app_process.*scrcpy" | awk '{print $2}')
    echo -e "${GREEN}✓${NC} Running (Android PID: $PID)"
else
    echo -e "${RED}✗${NC} Not found in Android processes"
    echo "Server output:"
    cat /tmp/scrcpy-server.log | head -20
    exit 1
fi

# Monitor logs for successful start
echo ""
echo "Server logs:"
echo "════════════════════════════════════════"
timeout 3 adb logcat -s scrcpy:* AndroidRuntime:I 2>/dev/null | head -15 || true
echo "════════════════════════════════════════"

echo ""
echo -e "${GREEN}╔════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║      Server Started Successfully!      ║${NC}"
echo -e "${GREEN}╚════════════════════════════════════════╝${NC}"
echo ""
echo "📡 WebSocket URL: ws://localhost:$PORT"
echo "🎬 Codec: $CODEC"
echo ""
echo "Next steps:"
echo "  1. Open player: open scrcpy-player.html"
echo "  2. Or test: ./test-websocket.sh"
echo ""
echo "Monitor logs:"
echo "  adb logcat -s scrcpy:*"
echo ""
echo "Stop server:"
echo "  adb shell pkill -f scrcpy"
echo ""
echo -e "${YELLOW}Server is now running in background...${NC}"
echo "Press Ctrl+C to stop log monitoring (server will keep running)"
echo ""
echo "════════════════════════════════════════"

# Keep showing logs
adb logcat -s scrcpy:* 2>/dev/null
