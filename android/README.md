# Lost Cities LAN Android

This Android module is a two-player Lost Cities build for local-network play.

## What the app does

- Runs a small local lobby server inside each app instance.
- Scans the LAN for other Lost Cities peers by IP.
- Lets you invite a specific peer.
- Shows the invited peer an accept or deny dialog.
- Starts the round automatically for both devices after acceptance.

## Current debug package

- Application id: `com.lost_cities.lan`
- Main activity: `com.lost_cities.lan/.MainActivity`

The visible product and the debug package id are both Lost Cities branded.

## Build

```bash
cd /home/ber0061/Repositories/lost_cities/android
./gradlew :app:assembleDebug --no-daemon --stacktrace
./gradlew :app:testDebugUnitTest --no-daemon --stacktrace
```

## Dual emulators

```bash
cd /home/ber0061/Repositories/lost_cities/android
./scripts/start_first_emulator.sh
./scripts/start_second_emulator.sh
./scripts/build_install_dual_emulators.sh
```

`build_install_dual_emulators.sh` builds once, installs to both emulators, launches the app, and sets the emulator forwarding needed for `10.0.2.2:<LAN_PORT>` peer visibility.
