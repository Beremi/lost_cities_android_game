# Android development instructions

This project is the mobile LAN version of **Lost Cities**.
This file explains where things live and how to run, build, and install the app on emulators or a phone.

## Project layout

- `android/` – Android project root (Gradle wrapper, app module, scripts, etc.)
- `android/app/` – Android app module (`src`, resources, manifest, tests)
- `android/scripts/` – helper scripts for starting emulators and installing APKs
- `instructions.md` – this doc
- `readme.md` – project entry point, includes links and overview

## 1) Prepare SDK / Java tools

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
export JAVA_HOME="/usr/lib/jvm/java-17-openjdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
```

> If your Java path differs, use your installed JDK 17+ path instead.

## 2) Create and list Android Virtual Devices (AVDs)

```bash
emulator -list-avds
```

To run one:

```bash
emulator -avd <AVD_NAME>
```

Optional scripted one-time creation is documented in `android/scripts/README.md`.
The old project docs referenced:

- `Pixel_Lite_API24` (default host emulator, port `5554`)
- `Pixel_Lite_API24_B` (default client emulator, port `5556`)

Use these names if you keep the same AVD setup; otherwise replace with your own.

## 3) Start two emulators (if needed)

From the project root:

```bash
cd /home/ber0061/Repositories/lost_cities/android
./scripts/start_first_emulator.sh
./scripts/start_second_emulator.sh
```

Check serials:

```bash
adb devices -l
```

Typical serials: `emulator-5554`, `emulator-5556`.

## 4) Build, install, and launch

From `android/`:

```bash
./gradlew :app:assembleDebug --no-daemon --stacktrace
```

Manual install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Launch app on active device:

```bash
adb shell am start -n <APPLICATION_ID>/.MainActivity
```

Current debug package and activity:

- `com.carcassonne.lan/.MainActivity`

### Dual emulators

Recommended for quick LAN/multiplayer testing:

```bash
./scripts/build_install_dual_emulators.sh
```

That script builds once, installs on both emulators, and launches the main activity.

## 5) Push app to a specific emulator/device

```bash
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk   # target emulator
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk   # target emulator
```

If needed, install to a physical phone:

```bash
./scripts/build_install_phone.sh <SERIAL>
```

`<SERIAL>` is the device id from `adb devices -l`.

## 6) Useful commands

```bash
adb -s <SERIAL> shell am force-stop <APPLICATION_ID>
adb -s <SERIAL> logcat | tail -n 200
./gradlew :app:clean
```

## Troubleshooting

- If `adb` cannot find emulators, run:
  - `killall adb` (Linux/macOS)
  - then rerun `adb devices -l`
- If installation is blocked by signature mismatch, use:
  - `adb install -r -d app/build/outputs/apk/debug/app-debug.apk`
- If Gradle cannot resolve the SDK, confirm the `ANDROID_HOME`/`ANDROID_SDK_ROOT` exports are active in the current shell.

## What to update as the project evolves

- If you later rename the debug package id away from the inherited `com.carcassonne.lan`, update the launch commands and helper scripts here.
- Add simulator names, ports, and script names if your environment differs from the defaults.
- Extend this guide with any release signing or distribution steps if needed later.
