#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

DEVICE_SERIAL="${1:-${DEVICE_SERIAL:-96c0a906}}"
APP_ID="${APP_ID:-com.lost_cities.lan}"
MAIN_ACTIVITY="${MAIN_ACTIVITY:-.MainActivity}"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
CLEAN_INSTALL="${CLEAN_INSTALL:-1}"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

adb start-server >/dev/null 2>&1 || true

if ! adb devices | awk 'NR>1 {print $1}' | grep -qx "$DEVICE_SERIAL"; then
  echo "ERROR: device '$DEVICE_SERIAL' is not connected." >&2
  echo "Connected devices:" >&2
  adb devices -l >&2 || true
  exit 1
fi

STATE="$(adb -s "$DEVICE_SERIAL" get-state 2>/dev/null || true)"
if [[ "$STATE" != "device" ]]; then
  echo "ERROR: device '$DEVICE_SERIAL' is not ready (state=$STATE)." >&2
  echo "Check USB debugging authorization prompt on phone." >&2
  exit 1
fi

echo "Using device: $DEVICE_SERIAL"
echo "Building debug APK..."
./gradlew :app:assembleDebug --no-daemon --stacktrace

if [[ "$CLEAN_INSTALL" == "1" ]]; then
  echo "Cleaning old app state..."
  adb -s "$DEVICE_SERIAL" shell am force-stop "$APP_ID" >/dev/null 2>&1 || true
  adb -s "$DEVICE_SERIAL" shell pm clear "$APP_ID" >/dev/null 2>&1 || true
  adb -s "$DEVICE_SERIAL" uninstall "$APP_ID" >/dev/null 2>&1 || true
fi

echo "Installing APK on $DEVICE_SERIAL..."
adb -s "$DEVICE_SERIAL" install -r "$APK_PATH"

echo "Launching $APP_ID/$MAIN_ACTIVITY..."
adb -s "$DEVICE_SERIAL" shell am start -n "$APP_ID/$MAIN_ACTIVITY"

echo
echo "Done."
echo "If startup still hangs, capture logs with:"
echo "adb -s $DEVICE_SERIAL logcat -c"
echo "adb -s $DEVICE_SERIAL shell am start -n $APP_ID/$MAIN_ACTIVITY"
echo "adb -s $DEVICE_SERIAL logcat -d | grep -E \"AppViewModel|LanScanner|AndroidRuntime|Lost Cities|LostCities\""
