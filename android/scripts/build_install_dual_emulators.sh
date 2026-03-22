#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

DEVICE_A="${1:-emulator-5554}"
DEVICE_B="${2:-emulator-5556}"
LAN_PORT="${LAN_PORT:-18473}"
LAN_PORT_B="${LAN_PORT_B:-$((LAN_PORT + 1))}"
APP_ID="${APP_ID:-com.lost_cities.lan}"
MAIN_ACTIVITY="${MAIN_ACTIVITY:-.MainActivity}"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

wait_for_boot() {
  local device="$1"
  echo "Waiting for $device..."
  adb -s "$device" wait-for-device
  until [ "$(adb -s "$device" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    sleep 1
  done
  adb -s "$device" shell input keyevent 82 >/dev/null 2>&1 || true
  adb -s "$device" shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true
  adb -s "$device" shell settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
  adb -s "$device" shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true
}

adb start-server >/dev/null 2>&1 || true
adb devices -l

wait_for_boot "$DEVICE_A"
wait_for_boot "$DEVICE_B"

echo "Setting emulator forwarding on $DEVICE_A: host tcp:$LAN_PORT -> guest tcp:$LAN_PORT"
adb -s "$DEVICE_A" emu redir del "tcp:$LAN_PORT" >/dev/null 2>&1 || true
adb -s "$DEVICE_A" emu redir add "tcp:$LAN_PORT:$LAN_PORT"
adb -s "$DEVICE_A" emu redir list

echo "Setting emulator forwarding on $DEVICE_B: host tcp:$LAN_PORT_B -> guest tcp:$LAN_PORT"
adb -s "$DEVICE_B" emu redir del "tcp:$LAN_PORT_B" >/dev/null 2>&1 || true
adb -s "$DEVICE_B" emu redir add "tcp:$LAN_PORT_B:$LAN_PORT"
adb -s "$DEVICE_B" emu redir list

echo "Building debug APK..."
./gradlew :app:assembleDebug --no-daemon --stacktrace

echo "Installing APK on $DEVICE_A and $DEVICE_B..."
adb -s "$DEVICE_A" install --no-streaming -r "$APK_PATH"
adb -s "$DEVICE_B" install --no-streaming -r "$APK_PATH"

echo "Launching app on both emulators..."
adb -s "$DEVICE_A" shell am start -n "$APP_ID/$MAIN_ACTIVITY"
adb -s "$DEVICE_B" shell am start -n "$APP_ID/$MAIN_ACTIVITY"

echo "Testing from $DEVICE_B to forwarded endpoint 10.0.2.2:$LAN_PORT..."
if adb -s "$DEVICE_B" shell "command -v nc >/dev/null 2>&1"; then
  adb -s "$DEVICE_B" shell "nc -z -w 2 10.0.2.2 $LAN_PORT" >/dev/null 2>&1 \
    && echo "Connectivity OK" \
    || echo "Connectivity failed (check host emulator and forwarding)."
else
  echo "nc is not available in emulator shell; skipping connectivity probe."
fi

echo
echo "Done."
echo "Forwarded emulator endpoints:"
echo "  $DEVICE_A -> 10.0.2.2:$LAN_PORT"
echo "  $DEVICE_B -> 10.0.2.2:$LAN_PORT_B"
