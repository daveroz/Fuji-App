# Fuji Remote

An open-source Android app that talks to a Fujifilm camera over **Bluetooth LE only**. It's built for
the **X100VI** (firmware 1.30 and later, which use Fujifilm's newer "secure" Bluetooth pairing). It does
three things:

1. **Camera info**: shutter count and usage counters, plus model, name, serial number, firmware, lens
   and power state
2. **Back up custom recipes**: download the camera's settings backup, which holds every setting,
   including your **C1–C7 custom settings** with your film-simulation recipes
3. **Upload recipes**: restore a backup to the camera, putting all those settings back

No WiFi, no live view, no remote shooting. It uses the same Bluetooth interface as Fujifilm's own
XApp. **No camera firmware is decompiled, modified, flashed, or redistributed.**

---

## Status

| Feature | State |
|---|---|
| Pairing and registration (secure firmware) | Implemented. The sequence was tested on an X100VI 01.32 by GeoShutter (see references). |
| Shutter count and usage | Implemented. The camera sends its "vital data" as a JSON file: total shots, mechanical and electronic shutter counts, rated shutter life (with % used), power-ons, flash count, operating/shooting/viewfinder time, battery wear, sensor cleanings and more. |
| Camera info | Implemented: model, name, serial, firmware/hardware/software versions, lens and lens firmware, power switch, *connect while power off*, paired phones, MAC address, Bluetooth protocol version. |
| Settings backup (C1–C7 recipes included) | Implemented. Backups are saved in the app with name, model and firmware, and can be shared. |
| Restore (upload) to the camera | Implemented. A backup restores only to the same camera model (Fujifilm's rule; the app checks it). The camera may restart to apply the settings. |

The protocol comes from Fujifilm XApp 2.7.6 and public sources, but **this project hasn't tested the
app on real hardware yet**. If something doesn't work, use **Share Bluetooth log** on the main screen.
It records every Bluetooth exchange (bytes and camera names only).

### Recipes vs. film simulations

The X100VI can't load new film-simulation LUTs through any supported interface. That needs firmware
modification, which this project doesn't do. A **recipe** is the set of settings that defines a look:
film simulation, grain, Color Chrome, white balance and shift, tone curve, color, sharpness, clarity,
noise reduction. These live in the camera's C1–C7 custom settings, which are part of the settings
backup. A restore puts back the whole backup (all settings). The backup file format isn't public, so
the app keeps it byte for byte and doesn't edit individual recipes inside it.

---

## Using the app

**First time (pairing):**

1. On the camera, open **PAIRING REGISTRATION** in its Bluetooth / smartphone settings.
2. In the app, tap **Find camera** and pick the camera (shown as *Ready to pair*).
3. The camera shows a six-digit code. Confirm it on the camera with **MENU/OK** within about 30 s,
   and confirm the same code on the phone.

**Later:** tap your camera under **Paired cameras**, then switch the camera on (or off and on again).
A paired X100VI only advertises for a few seconds after it's switched on, so the app waits for it.

Once connected:

- **Camera info** reads the shutter count and usage counters, then everything else the camera reports.
- **Backups & recipes**: **Back up camera settings** saves a backup. **Restore** sends one back to the
  camera and replaces all its settings, so back up the current settings first.

> The phone's Bluetooth pairing is shared with XApp. If you pair with XApp afterwards, the camera
> may need to be paired with this app again.

---

## How it works

The camera is a Bluetooth LE GATT server; the phone is the client. All values are little-endian and
every write is a write with response. Every UUID, with its source, is in
[`FujiGatt.kt`](app/src/main/java/com/fujiapp/remote/ble/FujiGatt.kt). The transfer sequences are in
[`FileTransfer.kt`](app/src/main/java/com/fujiapp/remote/ble/FileTransfer.kt).

- **Registration** (every connection, on a bonded link): read the 4-byte status from `f557d96b`,
  write it back with the last byte set to `0x20`, write the client name to `85b9163e`, then
  subscribe to the camera-state indications and notifications.
- **Files** go through the file-transfer service `af854c2e` in 120-byte chunks:
  `u16 seq, u32 len, data`. `seq` counts from 0 and the last chunk is `0xFFFF`. Its data ends with
  the 16-bit sum of every data byte. The camera reports progress on `FILE_TRANSACTION_STATE`
  (`u16 state, u16 seq`): 1 start, 2 transferring, 3 file finished, 4 done, 0 error.
- **Backup**: `BACKUP_REQUEST = 1` → wait for `BACKUP_STATE = 1` → chunk size 120, index 0 → read a
  chunk on each state 1/2 → on state 3 check the sum, read the file name/size, and write
  `FILE_TRANSFER_RESULT = 3, 1`.
- **Shutter count**: the same download, started with `LOG_TRANSFER_REQUEST = 2` and
  `LOG_TRANSFER_STATE = 1`. The file is JSON (`total_shot_count`, `rear_curtain_shutter_count`,
  `shutter_upper_limit_count`, …).
- **Restore**: `RESTORE_REQUEST = 1` → wait for `RESTORE_STATE = 1` → chunk size 120, index 0, then
  `FILE_INFORMATION` (`"backup.dat"` padded to 40 bytes + `u32 size`) → wait for state 1 → write
  each chunk and wait for state 2 → state 3 means the camera has it all, then write
  `FILE_TRANSFER_RESULT = 3`.

---

## Building

The APK is built in CI (GitHub Actions) with **AGP 8.13.2, Gradle 8.14.3, compileSdk 36, Java 17**.
Every push uploads `FujiRemote.apk` as a build artifact.

- **Via GitHub Actions:** push to a branch (or run the *Build APK* workflow manually). Download the
  `FujiRemote.apk` artifact from the run, then `adb install -r FujiRemote.apk`.
- **Locally** (needs the Android SDK): `./gradlew assembleRelease` →
  `app/build/outputs/apk/release/app-release.apk`.
- **Tests:** `./gradlew testReleaseUnitTest`.

### Stable signing (optional)

To publish to the `app-latest` release and keep a stable upgrade key, add one repository secret:
`FUJI_KEYSTORE_BASE64`, the keystore file as one line of base64 (`base64 -w0 release.jks`). The build
assumes the standard passwords `android` / alias `androiddebugkey`. A keystore with other values
also needs `FUJI_KEYSTORE_PASSWORD`, `FUJI_KEY_ALIAS` and `FUJI_KEY_PASSWORD`. Without the secret,
the APK is signed with a throwaway debug key. That's fine for sideloading, but it won't upgrade a
differently-signed install.

---

## Architecture

```
app/src/main/java/com/fujiapp/remote/
├── ble/
│   ├── FujiGatt.kt        Every service/characteristic UUID, with sources
│   ├── FujiProtocol.kt    Advertisement parsing, status ack, client name, value decoding (pure, tested)
│   ├── FileTransfer.kt    Chunk format, checksum, restore encoding, state/result codes (pure, tested)
│   ├── GattClient.kt      Coroutine wrapper around BluetoothGatt (one operation at a time)
│   ├── FujiCamera.kt      Registration, camera info, backup, restore, shutter count
│   ├── CameraScanner.kt   Finds cameras by Fujifilm's company ID (0x04D8)
│   └── Bonding.kt         System pairing (numeric comparison)
├── model/                 CameraInfo, CameraVitals, BackupMeta
├── data/BackupStore.kt    Backups in app storage (.dat as received + .json metadata)
├── util/ProtocolLog.kt    In-memory Bluetooth log for troubleshooting
├── CameraSession.kt       Scan → connect → pair → register → info / transfers
├── App.kt                 Holds the process-wide CameraSession
└── ui/                    MainActivity, CameraInfoActivity, BackupsActivity
```

---

## References

- FUJIFILM XApp 2.7.6 for Android: static analysis for interoperability of the restore, vital-data
  and file-transfer sequences, and their state and result values. Only identifiers, byte values and
  the order of operations were taken. No code was copied, and nothing from the app is
  redistributed.
- [`sarmadjari/GeoShutter`](https://github.com/sarmadjari/GeoShutter): secure pairing and session
  setup, tested on an X100VI (`docs/fujifilm-protocol.md`)
- [`Th0rgal/fuji-bridge`](https://github.com/Th0rgal/fuji-bridge): the settings-backup download
  (`FujiBridge/FujiBackup.swift`)
- [`tiredboffin/fffw`](https://github.com/tiredboffin/fffw): per-model GATT tables
  (`ffbt/cfg/gatt-ad14d4.yaml` is the X100VI 1.31) and the shape of the vital-data JSON
- [`MaxRink/furble`](https://github.com/MaxRink/furble): firmware-checked UUID table
  (`docs/camera-research/fujifilm-secure-gatt.md`)
- [`gkoh/furble`](https://github.com/gkoh/furble) and [`petabyt/libfuji`](https://github.com/petabyt/libfuji):
  the original Fujifilm BLE reverse-engineering
- FUJIFILM Camera Remote 4.10.2: characteristic names and value formats for camera information

## License

MIT. See [LICENSE](LICENSE).

This project is not affiliated with or endorsed by Fujifilm. "Fujifilm", "X100VI", and film
simulation names are trademarks of FUJIFILM Corporation, used here only to describe interoperability.
