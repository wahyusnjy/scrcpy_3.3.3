#!/bin/bash
# 🚀 Quick Integration Script: NetrisTV WebSocket scrcpy + ws-scrcpy
# This script automates the setup process

set -e  # Exit on error

echo "=================================================="
echo "🚀 NetrisTV WebSocket scrcpy Integration Script"
echo "=================================================="
echo ""

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Configuration
WORK_DIR="$HOME/Documents/riset"
NETRIS_DIR="$WORK_DIR/scrcpy-c/scrcpy-netris"
WS_SCRCPY_DIR="$WORK_DIR/ws-scrcpy"
SERVER_JAR="scrcpy-server.jar"

# Functions
print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

print_step() {
    echo -e "\n${YELLOW}➜ $1${NC}"
}

check_prerequisites() {
    print_step "Checking prerequisites..."
    
    # Check Java
    if command -v java &> /dev/null; then
        JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}')
        print_success "Java installed: $JAVA_VERSION"
    else
        print_error "Java not found! Please install Java 17+"
        exit 1
    fi
    
    # Check Git
    if command -v git &> /dev/null; then
        print_success "Git installed"
    else
        print_error "Git not found! Please install Git"
        exit 1
    fi
    
    # Check ADB
    if command -v adb &> /dev/null; then
        print_success "ADB installed"
    else
        print_warning "ADB not found in PATH. You'll need it later."
    fi
    
    # Check Node.js (for ws-scrcpy)
    if command -v node &> /dev/null; then
        NODE_VERSION=$(node --version)
        print_success "Node.js installed: $NODE_VERSION"
    else
        print_error "Node.js not found! Please install Node.js 16+"
        exit 1
    fi
}

clone_netris_scrcpy() {
    print_step "Cloning NetrisTV scrcpy (WebSocket fork)..."
    
    if [ -d "$NETRIS_DIR" ]; then
        print_warning "Directory already exists: $NETRIS_DIR"
        read -p "Delete and re-clone? (y/n) " -n 1 -r
        echo
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            rm -rf "$NETRIS_DIR"
        else
            print_warning "Skipping clone..."
            return
        fi
    fi
    
    cd "$WORK_DIR"
    git clone -b feature/websocket-server https://github.com/NetrisTV/scrcpy.git scrcpy-netris
    print_success "NetrisTV scrcpy cloned"
}

build_netris_server() {
    print_step "Building NetrisTV scrcpy-server.jar..."
    
    cd "$NETRIS_DIR"
    
    # Check if gradlew exists
    if [ ! -f "./gradlew" ]; then
        print_error "gradlew not found in $NETRIS_DIR"
        exit 1
    fi
    
    # Make gradlew executable
    chmod +x ./gradlew
    
    # Build the server
    print_warning "Building... This may take a few minutes..."
    ./gradlew assembleRelease
    
    # Check if build succeeded
    SERVER_APK="$NETRIS_DIR/server/build/outputs/apk/release/server-release-unsigned.apk"
    if [ -f "$SERVER_APK" ]; then
        print_success "Build successful!"
        print_success "Server JAR location: $SERVER_APK"
    else
        print_error "Build failed! Server JAR not found."
        exit 1
    fi
}

setup_ws_scrcpy() {
    print_step "Setting up ws-scrcpy..."
    
    # Check if ws-scrcpy already cloned
    if [ ! -d "$WS_SCRCPY_DIR" ]; then
        print_warning "ws-scrcpy not found. Cloning..."
        cd "$WORK_DIR"
        git clone https://github.com/NetrisTV/ws-scrcpy.git
        print_success "ws-scrcpy cloned"
    else
        print_success "ws-scrcpy already exists"
    fi
    
    # Install dependencies
    cd "$WS_SCRCPY_DIR"
    if [ ! -d "node_modules" ]; then
        print_warning "Installing npm dependencies..."
        npm install
        print_success "Dependencies installed"
    else
        print_success "Dependencies already installed"
    fi
}

copy_server_jar() {
    print_step "Copying server JAR to ws-scrcpy..."
    
    SERVER_APK="$NETRIS_DIR/server/build/outputs/apk/release/server-release-unsigned.apk"
    WS_VENDOR_DIR="$WS_SCRCPY_DIR/dist/vendor"
    
    # Create vendor directory if not exists
    mkdir -p "$WS_VENDOR_DIR"
    
    # Copy and rename
    cp "$SERVER_APK" "$WS_VENDOR_DIR/$SERVER_JAR"
    print_success "Server JAR copied to: $WS_VENDOR_DIR/$SERVER_JAR"
}

test_adb_connection() {
    print_step "Testing ADB connection..."
    
    # Check if ADB is available
    if ! command -v adb &> /dev/null; then
        print_warning "ADB not found. Skipping device check."
        return
    fi
    
    # List devices
    DEVICES=$(adb devices | grep -v "List" | grep "device$" | wc -l | xargs)
    
    if [ "$DEVICES" -gt 0 ]; then
        print_success "Found $DEVICES Android device(s)"
        adb devices
    else
        print_warning "No Android devices found!"
        print_warning "Please connect your device and enable USB debugging"
    fi
}

print_usage_guide() {
    echo ""
    echo "=================================================="
    echo "✅ Setup Complete!"
    echo "=================================================="
    echo ""
    echo "📁 Directory Structure:"
    echo "   $WORK_DIR/"
    echo "   ├── scrcpy-netris/          (NetrisTV fork)"
    echo "   └── ws-scrcpy/              (Web client)"
    echo ""
    echo "🚀 Quick Start:"
    echo ""
    echo "   1. Push server to device:"
    echo "      cd $WS_SCRCPY_DIR"
    echo "      adb push dist/vendor/scrcpy-server.jar /data/local/tmp/"
    echo ""
    echo "   2. Start WebSocket server:"
    echo "      adb shell CLASSPATH=/data/local/tmp/scrcpy-server.jar \\"
    echo "          app_process / com.genymobile.scrcpy.Server \\"
    echo "          1.18 web info 8886 true"
    echo ""
    echo "   3. Start ws-scrcpy web interface:"
    echo "      cd $WS_SCRCPY_DIR"
    echo "      npm start"
    echo ""
    echo "   4. Open browser:"
    echo "      http://localhost:8000"
    echo ""
    echo "🔍 Useful Commands:"
    echo ""
    echo "   Check server status:"
    echo "      adb shell cat /data/local/tmp/ws_scrcpy.pid"
    echo ""
    echo "   View server logs:"
    echo "      adb logcat | grep scrcpy"
    echo ""
    echo "   Stop server:"
    echo "      adb shell pkill -f scrcpy"
    echo ""
    echo "Happy coding! 🎉"
    echo ""
}

# Main execution
main() {
    check_prerequisites
    clone_netris_scrcpy
    build_netris_server
    setup_ws_scrcpy
    copy_server_jar
    test_adb_connection
    print_usage_guide
}

# Run main function
main
