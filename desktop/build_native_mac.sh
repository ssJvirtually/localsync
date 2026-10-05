#!/usr/bin/env bash
# ==============================================================================
# LocalSync macOS Native Image Build Script
# Uses GraalVM (Liberica NIK with JavaFX) to compile into a native binary
# and packages a standalone macOS application bundle (.app)
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "=========================================================="
echo "    LocalSync macOS GraalVM Native Image Build"
echo "=========================================================="

# 1. Initialize SDKMAN and activate GraalVM
if [[ -f "$HOME/.sdkman/bin/sdkman-init.sh" ]]; then
    # Disable nounset temporarily as sdkman script may reference unset vars
    set +u
    source "$HOME/.sdkman/bin/sdkman-init.sh"
    # Prefer Liberica NIK FX (built-in JavaFX support), fallback to graalce
    if sdk use java 23.1.12-fx+1.1.r21-nik 2>/dev/null; then
        echo "Using Liberica NIK with JavaFX (GraalVM 21)."
    elif sdk use java 21.0.2-graalce 2>/dev/null; then
        echo "Using GraalVM CE 21."
    fi
    set -u
fi

echo "Java Version:"
java -version
echo ""
echo "Native Image Version:"
native-image --version
echo ""

# 2. Build Native Image using Maven
echo "[1/3] Building native executable via Maven (-Pnative)..."
mvn clean package -Pnative

EXECUTABLE_PATH="$SCRIPT_DIR/target/localsync-desktop"
if [[ ! -f "$EXECUTABLE_PATH" ]]; then
    echo "Error: Native executable was not found at $EXECUTABLE_PATH" >&2
    exit 1
fi

echo "[2/3] Native binary successfully built at: $EXECUTABLE_PATH"

# 3. Package into macOS .app bundle
echo "[3/3] Packaging macOS Application Bundle (.app)..."
DIST_DIR="$SCRIPT_DIR/target/dist"
APP_DIR="$DIST_DIR/LocalSync.app"

rm -rf "$APP_DIR"
mkdir -p "$APP_DIR/Contents/MacOS"
mkdir -p "$APP_DIR/Contents/Resources"

# Copy native binary
cp "$EXECUTABLE_PATH" "$APP_DIR/Contents/MacOS/localsync-desktop"
chmod +x "$APP_DIR/Contents/MacOS/localsync-desktop"

# Generate .icns if tools are available
ICON_SRC="$SCRIPT_DIR/src/main/resources/icon.png"
ICNS_DEST="$APP_DIR/Contents/Resources/AppIcon.icns"
if command -v sips >/dev/null 2>&1 && command -v iconutil >/dev/null 2>&1 && [[ -f "$ICON_SRC" ]]; then
    ICONSET_DIR="$DIST_DIR/icon.iconset"
    mkdir -p "$ICONSET_DIR"
    sips -z 16 16     "$ICON_SRC" --out "$ICONSET_DIR/icon_16x16.png" >/dev/null 2>&1
    sips -z 32 32     "$ICON_SRC" --out "$ICONSET_DIR/icon_16x16@2x.png" >/dev/null 2>&1
    sips -z 32 32     "$ICON_SRC" --out "$ICONSET_DIR/icon_32x32.png" >/dev/null 2>&1
    sips -z 64 64     "$ICON_SRC" --out "$ICONSET_DIR/icon_32x32@2x.png" >/dev/null 2>&1
    sips -z 128 128   "$ICON_SRC" --out "$ICONSET_DIR/icon_128x128.png" >/dev/null 2>&1
    sips -z 256 256   "$ICON_SRC" --out "$ICONSET_DIR/icon_128x128@2x.png" >/dev/null 2>&1
    sips -z 256 256   "$ICON_SRC" --out "$ICONSET_DIR/icon_256x256.png" >/dev/null 2>&1
    sips -z 512 512   "$ICON_SRC" --out "$ICONSET_DIR/icon_256x256@2x.png" >/dev/null 2>&1
    sips -z 512 512   "$ICON_SRC" --out "$ICONSET_DIR/icon_512x512.png" >/dev/null 2>&1
    sips -z 1024 1024 "$ICON_SRC" --out "$ICONSET_DIR/icon_512x512@2x.png" >/dev/null 2>&1
    iconutil -c icns "$ICONSET_DIR" -o "$ICNS_DEST"
    rm -rf "$ICONSET_DIR"
fi

# Create Info.plist
cat << 'EOF' > "$APP_DIR/Contents/Info.plist"
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>localsync-desktop</string>
    <key>CFBundleIconFile</key>
    <string>AppIcon</string>
    <key>CFBundleIdentifier</key>
    <string>com.localsync.desktop</string>
    <key>CFBundleName</key>
    <string>LocalSync</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleShortVersionString</key>
    <string>1.0.0</string>
    <key>CFBundleVersion</key>
    <string>1.0.0</string>
    <key>NSHighResolutionCapable</key>
    <true/>
    <key>LSMinimumSystemVersion</key>
    <string>11.0</string>
</dict>
</plist>
EOF

echo ""
echo "=========================================================="
echo " BUILD COMPLETE!"
echo " Standalone Native Executable: $EXECUTABLE_PATH"
echo " macOS Application Bundle:     $APP_DIR"
echo "=========================================================="
