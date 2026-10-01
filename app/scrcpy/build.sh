#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")" && pwd)
BUILD="$ROOT/build"
SOURCE="$BUILD/source"
rm -rf "$SOURCE"
mkdir -p "$SOURCE"
tar -xzf "$ROOT/scrcpy-3.3.4.tar.gz" -C "$SOURCE" --strip-components=1
patch -d "$SOURCE" -p1 < "$ROOT/primecap-daemon.patch"
cp -R "$ROOT/overlay/." "$SOURCE/server/src/main/java/"
ANDROID_PLATFORM=35 ANDROID_BUILD_TOOLS=35.0.0 \
  BUILD_DIR="$BUILD/manual" "$SOURCE/server/build_without_gradle.sh"
cp "$BUILD/manual/scrcpy-server" "$BUILD/primecap-video-daemon"
mkdir -p "$BUILD/assets"
cp "$BUILD/manual/scrcpy-server" "$BUILD/assets/primecap-relay"
cp "$BUILD/manual/scrcpy-server" "$BUILD/assets/primecap-video-daemon"
