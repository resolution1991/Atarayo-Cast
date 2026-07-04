#!/bin/zsh
# build.sh — Build AirCast debug APK with environment configured for macOS.
#
# Usage:
#   ./build.sh            # assemble debug APK
#   ./build.sh clean      # clean + assemble
#   ./build.sh install    # assemble and install on connected device

set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Android Studio bundled JDK (JDK 21)
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# Work around macOS 26 / Gradle native-platform incompatibility
export GRADLE_OPTS="-Dorg.gradle.native=false"

# Keep Gradle caches in a writable temp directory
export GRADLE_USER_HOME="${TMPDIR:-/tmp}/aircast-gradle-home"

# Project-level cache also writable
PROJECT_CACHE_DIR="${TMPDIR:-/tmp}/aircast-project-cache"

BUILD_TYPE="${1:-assemble}"

cd "$PROJECT_DIR"

mkdir -p "$GRADLE_USER_HOME"
mkdir -p "$PROJECT_CACHE_DIR"

case "$BUILD_TYPE" in
    clean)
        ./gradlew clean --no-daemon --project-cache-dir "$PROJECT_CACHE_DIR"
        ./gradlew assembleDebug --no-daemon --project-cache-dir "$PROJECT_CACHE_DIR"
        ;;
    install)
        ./gradlew assembleDebug --no-daemon --project-cache-dir "$PROJECT_CACHE_DIR"
        adb install -r "$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
        ;;
    *)
        ./gradlew assembleDebug --no-daemon --project-cache-dir "$PROJECT_CACHE_DIR"
        ;;
esac

echo ""
echo "APK: $PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
