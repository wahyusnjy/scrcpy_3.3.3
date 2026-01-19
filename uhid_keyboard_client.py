#!/usr/bin/env python3
"""
UHID Keyboard Client for scrcpy WebSocket server
Sends keyboard events via UHID (Type 100)
"""

import asyncio
import websockets
import struct
import time

# Message types
TYPE_UHID_KEYBOARD = 100
TYPE_INJECT_KEYCODE = 102

# HID Keycodes
HID_KEYS = {
    'TAB': 0x2B,
    'ENTER': 0x28,
    'ESCAPE': 0x29,
    'BACKSPACE': 0x2A,
    'SPACE': 0x2C,
    'RIGHT': 0x4F,
    'LEFT': 0x50,
    'DOWN': 0x51,
    'UP': 0x52,
    'DELETE': 0x4C,
    'HOME': 0x4A,
    'END': 0x4D,
    'PAGE_UP': 0x4B,
    'PAGE_DOWN': 0x4E,
    # Letters
    'A': 0x04, 'B': 0x05, 'C': 0x06, 'D': 0x07,
    'E': 0x08, 'F': 0x09, 'G': 0x0A, 'H': 0x0B,
    'I': 0x0C, 'J': 0x0D, 'K': 0x0E, 'L': 0x0F,
    'M': 0x10, 'N': 0x11, 'O': 0x12, 'P': 0x13,
    'Q': 0x14, 'R': 0x15, 'S': 0x16, 'T': 0x17,
    'U': 0x18, 'V': 0x19, 'W': 0x1A, 'X': 0x1B,
    'Y': 0x1C, 'Z': 0x1D,
    # Numbers
    '1': 0x1E, '2': 0x1F, '3': 0x20, '4': 0x21,
    '5': 0x22, '6': 0x23, '7': 0x24, '8': 0x25,
    '9': 0x26, '0': 0x27,
}

# Modifiers
MOD_CTRL = 0x01
MOD_SHIFT = 0x02
MOD_ALT = 0x04
MOD_GUI = 0x08


class UhidKeyboard:
    def __init__(self, ws_url='ws://localhost:8886'):
        self.ws_url = ws_url
        self.ws = None
    
    async def connect(self):
        """Connect to WebSocket server"""
        print(f"🔗 Connecting to {self.ws_url}...")
        self.ws = await websockets.connect(self.ws_url)
        print("✅ Connected!")
        
        # Read device info (first message from server)
        msg = await self.ws.recv()
        if isinstance(msg, str):
            print(f"📱 Device info: {msg[:100]}...")
    
    async def send_uhid_key(self, modifiers: int, keycode: int):
        """Send UHID keyboard event (9 bytes)"""
        msg = bytearray(9)
        msg[0] = TYPE_UHID_KEYBOARD
        msg[1] = modifiers
        msg[2] = 0  # reserved
        msg[3] = keycode
        # msg[4-8] = 0 (additional keys)
        
        await self.ws.send(bytes(msg))
    
    async def send_key(self, key: str, ctrl=False, shift=False, alt=False, gui=False):
        """Send key press + release"""
        keycode = HID_KEYS.get(key.upper())
        if keycode is None:
            print(f"❌ Unknown key: {key}")
            return
        
        # Build modifiers
        modifiers = 0
        if ctrl: modifiers |= MOD_CTRL
        if shift: modifiers |= MOD_SHIFT
        if alt: modifiers |= MOD_ALT
        if gui: modifiers |= MOD_GUI
        
        # Press
        await self.send_uhid_key(modifiers, keycode)
        print(f"⌨️  Sent: {key.upper()}{' (Ctrl)' if ctrl else ''}{' (Shift)' if shift else ''}")
        
        # Release after 50ms
        await asyncio.sleep(0.05)
        await self.send_uhid_key(0, 0)
    
    async def send_text(self, text: str):
        """Send text string (letters and spaces)"""
        for char in text:
            if char == ' ':
                await self.send_key('SPACE')
            else:
                await self.send_key(char.upper())
            await asyncio.sleep(0.1)
    
    async def close(self):
        """Close WebSocket connection"""
        if self.ws:
            await self.ws.close()
            print("🔌 Disconnected")


async def demo():
    """Demo: Send various keyboard commands"""
    kb = UhidKeyboard()
    
    try:
        await kb.connect()
        
        print("\n🎮 Demo: Sending keyboard commands...\n")
        
        # Test Tab key
        print("1. Sending Tab...")
        await kb.send_key('TAB')
        await asyncio.sleep(1)
        
        # Test Enter
        print("2. Sending Enter...")
        await kb.send_key('ENTER')
        await asyncio.sleep(1)
        
        # Test arrows
        print("3. Sending arrow keys...")
        await kb.send_key('RIGHT')
        await asyncio.sleep(0.5)
        await kb.send_key('DOWN')
        await asyncio.sleep(0.5)
        await kb.send_key('LEFT')
        await asyncio.sleep(0.5)
        await kb.send_key('UP')
        await asyncio.sleep(1)
        
        # Test Ctrl combinations
        print("4. Sending Ctrl+C...")
        await kb.send_key('C', ctrl=True)
        await asyncio.sleep(1)
        
        print("5. Sending Ctrl+V...")
        await kb.send_key('V', ctrl=True)
        await asyncio.sleep(1)
        
        # Test text
        print("6. Sending text 'HELLO'...")
        await kb.send_text('HELLO')
        
        print("\n✅ Demo complete!")
        
    except Exception as e:
        print(f"❌ Error: {e}")
    finally:
        await kb.close()


async def interactive():
    """Interactive mode: Send keys from keyboard input"""
    kb = UhidKeyboard()
    
    try:
        await kb.connect()
        
        print("\n🎮 Interactive Mode")
        print("Commands:")
        print("  TAB, ENTER, ESCAPE, SPACE")
        print("  UP, DOWN, LEFT, RIGHT")
        print("  A-Z, 0-9")
        print("  CTRL+C (type: c ctrl)")
        print("  quit - Exit")
        print()
        
        while True:
            cmd = input("Key> ").strip().upper()
            
            if cmd == 'QUIT':
                break
            
            # Parse modifiers
            parts = cmd.split()
            key = parts[0]
            ctrl = 'CTRL' in parts
            shift = 'SHIFT' in parts
            alt = 'ALT' in parts
            
            await kb.send_key(key, ctrl=ctrl, shift=shift, alt=alt)
        
    except KeyboardInterrupt:
        print("\n👋 Bye!")
    finally:
        await kb.close()


if __name__ == '__main__':
    import sys
    
    if len(sys.argv) > 1 and sys.argv[1] == 'interactive':
        # Interactive mode
        asyncio.run(interactive())
    else:
        # Demo mode
        asyncio.run(demo())
