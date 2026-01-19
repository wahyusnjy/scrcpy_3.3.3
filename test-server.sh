#!/bin/bash
# Quick test script for scrcpy WebSocket server

# Kill old server
adb shell pkill -f scrcpy 2>/dev/null
sleep 1

# Start server with DEBUG logging
echo "Starting server with DEBUG logging..."
echo "Press Ctrl+C to stop"
echo ""

adb shell "CLASSPATH=/data/local/tmp/scrcpy-server.jar \
    app_process / com.genymobile.scrcpy.Server \
    3.3.3 web debug 8886 false h264" 2>&1
