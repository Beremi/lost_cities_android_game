# Lost Cities (Android LAN)

Android build of **Lost Cities** focused on local-network play between two devices.

## Start here

- See [`instructions.md`](/home/ber0061/Repositories/lost_cities/instructions.md) for setup, build, and emulator/device install flow.

## Development quick reference

```bash
cd /home/ber0061/Repositories/lost_cities/android
./gradlew :app:assembleDebug --no-daemon --stacktrace
```

Then install to an emulator/device as documented in [`instructions.md`](/home/ber0061/Repositories/lost_cities/instructions.md).

## App goals

- Discover peers on the local network by IP.
- Invite a specific peer and start the round automatically after acceptance.
- Keep the touch gameplay responsive on phones and emulators.
- Support repeatable build and install flow for two local emulators.
