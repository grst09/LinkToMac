#!/bin/bash
# Builds LinkToMac and launches it as a real .app bundle.
#
# A bare `swift run`/`swift build` executable has no bundle identity, so macOS
# Launch Services registers the process as "BackgroundOnly" — it never gets a real
# WindowServer session, which means MenuBarExtra's status item can never render and
# UserNotifications can't register either. Wrapping the built binary in a minimal
# ad-hoc signed .app bundle (no paid Developer ID needed for local runs) fixes both.
set -euo pipefail
cd "$(dirname "$0")/.."

CONFIG="${1:-debug}"
APP_NAME="LinkToMac"
BUNDLE_DIR=".build/${APP_NAME}.app"

echo "Building ($CONFIG)…"
swift build --configuration "$CONFIG"

echo "Assembling ${BUNDLE_DIR}…"
rm -rf "$BUNDLE_DIR"
mkdir -p "$BUNDLE_DIR/Contents/MacOS"
cp "Info.plist" "$BUNDLE_DIR/Contents/Info.plist"
cp ".build/${CONFIG}/${APP_NAME}" "$BUNDLE_DIR/Contents/MacOS/${APP_NAME}"

echo "Ad-hoc signing…"
codesign --force --deep --sign - "$BUNDLE_DIR"

echo "Launching…"
open "$BUNDLE_DIR"
