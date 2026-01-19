#!/usr/bin/env python3
"""
MJPEG WebSocket Server for Android Screen Streaming
Captures screenshots via ADB and streams as MJPEG over WebSocket
"""

import asyncio
import websockets
import subprocess
import json
from PIL import Image
from io import BytesIO
import time

# Configuration
PORT = 8887
FPS = 15
JPEG_QUALITY = 75
SCALE_WIDTH = 720

async def capture_screenshot():
    """Capture screenshot from Android device via ADB"""
    try:
        # Capture screenshot using adb
        result = subprocess.run(
            ['adb', 'exec-out', 'screencap', '-p'],
            capture_output=True,
            timeout=2
        )
        
        if result.returncode == 0 and result.stdout:
            # Open image with PIL
            img = Image.open(BytesIO(result.stdout))
            
            # Resize to reduce bandwidth
            aspect_ratio = img.height / img.width
            new_height = int(SCALE_WIDTH * aspect_ratio)
            img = img.resize((SCALE_WIDTH, new_height), Image.LANCZOS)
            
            # Convert to JPEG
            buffer = BytesIO()
            img.save(buffer, format='JPEG', quality=JPEG_QUALITY, optimize=True)
            return buffer.getvalue()
        
        return None
    except Exception as e:
        print(f"Screenshot error: {e}")
        return None

async def stream_mjpeg(websocket, path):
    """Stream MJPEG frames to WebSocket client"""
    print(f"Client connected from {websocket.remote_address}")
    
    # Send device info
    try:
        device_info = subprocess.run(
            ['adb', 'shell', 'getprop', 'ro.product.model'],
            capture_output=True,
            text=True,
            timeout=1
        )
        
        if device_info.returncode == 0:
            device_msg = json.dumps({
                "device": {
                    "manufacturer": "Samsung",
                    "model": device_info.stdout.strip()
                }
            })
            await websocket.send(device_msg)
    except:
        pass
    
    # Stream frames
    frame_count = 0
    frame_delay = 1.0 / FPS
    
    try:
        while True:
            start_time = time.time()
            
            # Capture and send frame
            jpeg_data = await capture_screenshot()
            if jpeg_data:
                await websocket.send(jpeg_data)
                frame_count += 1
                
                if frame_count % 30 == 0:
                    print(f"Streamed {frame_count} frames, size: {len(jpeg_data)//1024}KB")
            
            # Frame rate limiting
            elapsed = time.time() - start_time
            sleep_time = max(0, frame_delay - elapsed)
            if sleep_time > 0:
                await asyncio.sleep(sleep_time)
                
    except websockets.exceptions.ConnectionClosed:
        print(f"Client disconnected")
    except Exception as e:
        print(f"Streaming error: {e}")

async def main():
    # Check ADB connection
    try:
        result = subprocess.run(['adb', 'devices'], capture_output=True, text=True)
        if 'device' not in result.stdout:
            print("❌ No Android device connected via ADB!")
            return
        print("✅ ADB device detected")
    except FileNotFoundError:
        print("❌ ADB not found! Please install Android SDK Platform Tools")
        return
    
    print(f"🎬 Starting MJPEG WebSocket server on ws://localhost:{PORT}")
    print(f"   FPS: {FPS}, JPEG Quality: {JPEG_QUALITY}, Width: {SCALE_WIDTH}px")
    print(f"\n   Open player-mjpeg.html and connect to ws://localhost:{PORT}\n")
    
    async with websockets.serve(stream_mjpeg, "localhost", PORT):
        await asyncio.Future()  # Run forever

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n✓ Server stopped")
