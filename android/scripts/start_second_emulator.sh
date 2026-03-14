#!/usr/bin/env bash
set -euo pipefail

AVD_NAME="${1:-Pixel_Lite_API24_B}"
PORT="${2:-5556}"
GPU_MODE="${GPU_MODE:-host}"
EMULATOR_NOFILE="${EMULATOR_NOFILE:-65535}"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
export QT_QPA_PLATFORM="${QT_QPA_PLATFORM:-xcb}"

is_int() {
  [[ "${1:-}" =~ ^[0-9]+$ ]]
}

ensure_fd_limit() {
  local soft hard target after
  soft="$(ulimit -Sn)"
  hard="$(ulimit -Hn)"
  target="$EMULATOR_NOFILE"
  if ! is_int "$target"; then
    target=65535
  fi
  if is_int "$hard" && (( target > hard )); then
    target="$hard"
  fi
  if is_int "$soft" && (( soft < target )); then
    ulimit -Sn "$target" 2>/dev/null || true
  fi
  after="$(ulimit -Sn)"
  echo "Open files limit: soft=$after hard=$hard"
  if is_int "$after" && (( after < 4096 )); then
    echo "ERROR: open-file limit too low for emulator (need at least 4096)." >&2
    echo "Try in this terminal first: ulimit -n 65535" >&2
    exit 1
  fi
}

stop_existing_on_port() {
  local adb_port pid pids
  adb_port=$((PORT + 1))
  adb start-server >/dev/null 2>&1 || true
  adb -s "emulator-$PORT" emu kill >/dev/null 2>&1 || true
  sleep 1
  pids="$(
    ss -ltnp 2>/dev/null \
      | awk -v p1=":$PORT" -v p2=":$adb_port" '$4 ~ p1"$" || $4 ~ p2"$" { print }' \
      | sed -n 's/.*pid=\([0-9]\+\).*/\1/p' \
      | sort -u
  )"
  if [[ -n "$pids" ]]; then
    echo "Stopping stale emulator process(es) on port $PORT/$adb_port: $pids"
    while read -r pid; do
      [[ -z "$pid" ]] && continue
      kill "$pid" >/dev/null 2>&1 || true
    done <<< "$pids"
    sleep 2
  fi
}

ensure_fd_limit
stop_existing_on_port

echo "Starting emulator '$AVD_NAME' on port $PORT (gpu=$GPU_MODE)..."
exec emulator -avd "$AVD_NAME" \
  -port "$PORT" \
  -gpu "$GPU_MODE" \
  -accel on \
  -no-boot-anim \
  -noaudio \
  -camera-back none \
  -camera-front none \
  -netdelay none \
  -netspeed full \
  -no-snapshot \
  -no-snapshot-save
