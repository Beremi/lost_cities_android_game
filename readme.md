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

## Web Arena

The repo also includes a LAN-oriented Python web server that reuses the Lost Cities v3 assets and the Python AI lab bots.

```bash
cd /home/ber0061/Repositories/lost_cities
python -m web_server --host 0.0.0.0 --port 8743
```

Then open `http://<your-lan-ip>:8743` from another device on the same network.

## App goals

- Discover peers on the local network by IP.
- Invite a specific peer and start the round automatically after acceptance.
- Keep the touch gameplay responsive on phones and emulators.
- Support repeatable build and install flow for two local emulators.
