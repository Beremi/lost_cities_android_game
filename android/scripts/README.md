# Android helper scripts

These scripts are for repeatable Lost Cities LAN testing on two emulators or a phone.

## Scripts

- `start_first_emulator.sh`
  Starts the first emulator on port `5554`.
- `start_second_emulator.sh`
  Starts the second emulator on port `5556`.
- `build_install_dual_emulators.sh`
  Builds the debug APK once, installs it on both emulators, launches the app, and sets separate host-port forwards for both emulators so they are both discoverable through `10.0.2.2`.
- `build_install_phone.sh`
  Builds the APK, optionally clears old app data, installs it on a connected phone, and launches the app.

## Typical dual-emulator run

Terminal 1:

```bash
cd /home/ber0061/Repositories/lost_cities/android
./scripts/start_first_emulator.sh
```

Terminal 2:

```bash
cd /home/ber0061/Repositories/lost_cities/android
./scripts/start_second_emulator.sh
```

Terminal 3:

```bash
cd /home/ber0061/Repositories/lost_cities/android
./scripts/build_install_dual_emulators.sh
```

After launch, scan on both devices. The script forwards emulator A to `10.0.2.2:18473` and emulator B to `10.0.2.2:18474` by default, while the app filters out each device's own forwarded endpoint by server id. The in-app flow is invite-only: invite a peer, accept on the other emulator, and the round should start automatically for both.
