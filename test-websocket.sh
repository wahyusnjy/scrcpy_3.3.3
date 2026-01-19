#!/bin/bash
# Quick test WebSocket connectivity

echo "🔍 Testing WebSocket Server Connection..."
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Check if server is running
echo -n "1. Checking if server process is running... "
if adb shell ps | grep -q "app_process.*scrcpy"; then
    echo -e "${GREEN}✓ Running${NC}"
else
    echo -e "${RED}✗ Not running${NC}"
    echo "Start server first with:"
    echo "  adb shell CLASSPATH=/data/local/tmp/scrcpy-server.jar \\"
    echo "      app_process / com.genymobile.scrcpy.Server \\"
    echo "      3.3.3 web debug 8886 false h264"
    exit 1
fi

# Check port forwarding
echo -n "2. Checking port forwarding... "
if adb forward --list | grep -q "tcp:8886"; then
    echo -e "${GREEN}✓ Port 8886 forwarded${NC}"
else
    echo -e "${YELLOW}⚠ Not forwarded${NC}"
    echo "  Forwarding now..."
    adb forward tcp:8886 tcp:8886
    if [ $? -eq 0 ]; then
        echo -e "  ${GREEN}✓ Forwarded successfully${NC}"
    else
        echo -e "  ${RED}✗ Failed to forward${NC}"
        exit 1
    fi
fi

# Test WebSocket connection with netcat
echo -n "3. Testing TCP connection to localhost:8886... "
if command -v nc &> /dev/null; then
    timeout 2 nc -z localhost 8886 2>/dev/null
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✓ Port is open${NC}"
    else
        echo -e "${RED}✗ Port not accessible${NC}"
        echo "  Server might not be listening correctly"
        exit 1
    fi
else
    echo -e "${YELLOW}⚠ netcat not available, skipping${NC}"
fi

# Check recent logs
echo ""
echo "4. Recent server logs:"
echo "════════════════════════════════════════"
adb logcat -d -s scrcpy:* AndroidRuntime:I | tail -10
echo "════════════════════════════════════════"

# Test with curl (WebSocket upgrade)
echo ""
echo -n "5. Testing WebSocket handshake... "
if command -v curl &> /dev/null; then
    RESPONSE=$(curl -i -N -H "Connection: Upgrade" \
                        -H "Upgrade: websocket" \
                        -H "Sec-WebSocket-Version: 13" \
                        -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
                        http://localhost:8886/ 2>/dev/null | head -1)
    
    if echo "$RESPONSE" | grep -q "101"; then
        echo -e "${GREEN}✓ WebSocket handshake successful${NC}"
    elif echo "$RESPONSE" | grep -q "HTTP"; then
        echo -e "${YELLOW}⚠ Server responding but not WebSocket (got: $RESPONSE)${NC}"
    else
        echo -e "${RED}✗ No response${NC}"
        echo "  curl output: $RESPONSE"
    fi
else
    echo -e "${YELLOW}⚠ curl not available, skipping${NC}"
fi

echo ""
echo "════════════════════════════════════════"
echo "Summary:"
echo "  WebSocket URL: ws://localhost:8886"
echo "  Decoder: Use Broadway.js for H264"
echo ""
echo "Open player:"
echo "  open scrcpy-player.html"
echo "════════════════════════════════════════"
