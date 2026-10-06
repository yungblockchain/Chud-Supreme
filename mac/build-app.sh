#!/bin/bash
# Builds "Chud Supreme.app" for this Mac.
# Needs Apple's free command line tools (run: xcode-select --install) or Xcode 15.2 on macOS Ventura.
#
#   ./build-app.sh            build into ./build/
#   ./build-app.sh --install  build, then copy into /Applications
#   ARCH=x86_64 ./build-app.sh  build for a specific chip (x86_64 = Intel, arm64 = Apple silicon);
#                               without ARCH it builds for the Mac it's running on
set -euo pipefail
cd "$(dirname "$0")"

NAME="Chud Supreme"
EXECUTABLE="ChudStreams"
APP="build/$NAME.app"

if ! command -v swift >/dev/null 2>&1; then
    echo "Swift isn't installed. Run:  xcode-select --install"
    echo "then run this script again."
    exit 1
fi

ARCH_ARGS=()
if [ -n "${ARCH:-}" ]; then
    ARCH_ARGS=(--arch "$ARCH")
fi

echo "Compiling (the first build takes a few minutes)..."
# The ${…+…} form keeps an empty array safe under `set -u` in macOS's bash 3.2.
swift build -c release ${ARCH_ARGS[@]+"${ARCH_ARGS[@]}"}
BIN_DIR="$(swift build -c release ${ARCH_ARGS[@]+"${ARCH_ARGS[@]}"} --show-bin-path)"

echo "Packaging ${APP}..."
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN_DIR/$EXECUTABLE" "$APP/Contents/MacOS/$EXECUTABLE"
cp Resources/*.ttf Resources/brand_mascot.png "$APP/Contents/Resources/"
mkdir -p "$APP/Contents/Resources/shaders"
cp Resources/shaders/*.glsl "$APP/Contents/Resources/shaders/"

# App icon: every size macOS asks for, made from the 1024px artwork.
ICONSET="build/AppIcon.iconset"
rm -rf "$ICONSET"
mkdir -p "$ICONSET"
for size in 16 32 128 256 512; do
    sips -z "$size" "$size" Resources/AppIcon.png --out "$ICONSET/icon_${size}x${size}.png" >/dev/null
    double=$((size * 2))
    sips -z "$double" "$double" Resources/AppIcon.png --out "$ICONSET/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/AppIcon.icns"
rm -rf "$ICONSET"

# NSAllowsArbitraryLoads lets the app reach IPTV servers and streams on plain http://,
# which is how almost every Xtream provider serves them.
cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleDevelopmentRegion</key><string>en</string>
    <key>CFBundleExecutable</key><string>$EXECUTABLE</string>
    <key>CFBundleIdentifier</key><string>app.dial.supreme.mac</string>
    <key>CFBundleInfoDictionaryVersion</key><string>6.0</string>
    <key>CFBundleName</key><string>$NAME</string>
    <key>CFBundleDisplayName</key><string>$NAME</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>1.0.0</string>
    <key>CFBundleVersion</key><string>1</string>
    <key>CFBundleIconFile</key><string>AppIcon</string>
    <key>LSMinimumSystemVersion</key><string>13.0</string>
    <key>LSApplicationCategoryType</key><string>public.app-category.entertainment</string>
    <key>NSHighResolutionCapable</key><true/>
    <key>NSPrincipalClass</key><string>NSApplication</string>
    <key>NSAppTransportSecurity</key>
    <dict>
        <key>NSAllowsArbitraryLoads</key><true/>
        <key>NSAllowsArbitraryLoadsForMedia</key><true/>
        <key>NSAllowsArbitraryLoadsInWebContent</key><true/>
        <key>NSAllowsLocalNetworking</key><true/>
    </dict>
</dict>
</plist>
PLIST

# Ad-hoc signature so macOS treats it as a normal local app.
codesign --force --deep --sign - "$APP" >/dev/null 2>&1 || true

echo "Built: $APP"

if [ "${1:-}" = "--install" ]; then
    rm -rf "/Applications/$NAME.app"
    cp -R "$APP" "/Applications/"
    echo "Installed to /Applications/$NAME.app"
fi
