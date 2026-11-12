# Fuji Remote

An open-source Android app that talks to a Fujifilm camera over **Bluetooth LE only**. It's built for
the **X100VI** (firmware 1.30 and later, which use Fujifilm's newer "secure" Bluetooth pairing). It does
three things:

1. **Camera info**: model, name, serial number, firmware, lens, power state and more
2. **Back up custom recipes**: download the camera's settings backup, which holds every setting,
   including your **C1–C7 custom settings** with your film-simulation recipes
3. **Upload recipes**: restore a backup to the camera (see status below)

No WiFi, no live view, no remote shooting. It uses the same Bluetooth interface as Fujifilm's own
XApp. **No camera firmware is decompiled, modified, flashed, or redistributed.**

---

## Status

| Feature | State |
|---|---|
| Pairing and registration (secure firmware) | Implemented. The sequence was tested on an X100VI 01.32 by GeoShutter (see references). |
| Camera info | Implemented: model, name, serial, firmware/hardware/software versions, lens and lens firmware, power switch, *connect while power off*, paired phones, MAC address, Bluetooth protocol version. |
| Settings backup (C1–C7 recipes included) | Implemented using XApp's file-transfer sequence. Backups are saved in the app with name, model and firmware, and can be shared. |
| **Shutter count** and usage counters | **Not yet.** The camera sends them over Bluetooth as a JSON file (total shots, mechanical/electronic shutter counts, rated shutter life, flash count, battery age, …) through the same file service as the backup. But the request that asks for that file isn't publicly documented. |
| **Restore (upload) to the camera** | **Not yet.** Same reason: the request that starts a restore isn't publicly documented. |

The app has **not been validated on real hardware by this project yet**. If something doesn't work, use
**Share Bluetooth log** on the main screen. It records every Bluetooth exchange (bytes and camera
names only).

### What's needed for shutter count and restore

Both are XApp-only features. Fujifilm's older Camera Remote app doesn't contain them, and the camera
firmware has several write-only control characteristics whose purpose isn't publicly known. Guessing
which one starts a restore would mean writing to unknown camera controls, so the app doesn't do it.
They can be added once the exact request is confirmed, either from FUJIFILM XApp itself (static
analysis of the Android app, the same method fuji-bridge and GeoShutter used), or from an Android
*Bluetooth HCI snoop log* of XApp doing a restore and opening its equipment-status screen.

### Recipes vs. film simulations

The X100VI can't load new film-simulation LUTs through any supported interface. That needs firmware
modification, which this project doesn't do. A **recipe** is the set of settings that defines a look:
film simulation, grain, Color Chrome, white balance and shift, tone curve, color, sharpness, clarity,
noise reduction. These live in the camera's C1–C7 custom settings, which are part of the settings
backup. A backup restores only to the same camera model (Fujifilm's rule). The backup file format
isn't public, so the app keeps it byte for byte and doesn't edit individual recipes inside it.

---

## Using the app

**First time (pairing):**

1. On the camera, open **PAIRING REGISTRATION** in its Bluetooth / smartphone settings.
2. In the app, tap **Find camera** and pick the camera (shown as *Ready to pair*).
3. The camera shows a six-digit code. Confirm it on the camera with **MENU/OK** within about 30 s,
   and confirm the same code on the phone.

**Later:** tap your camera under **Paired cameras**, then switch the camera on (or off and on again).
A paired X100VI only advertises for a few seconds after it's switched on, so the app waits for it.

Once connected, open **Camera info** or **Backups & recipes**.

> The phone's Bluetooth pairing is shared with XApp. If you pair with XApp afterwards, the camera
> may need to be paired with this app again.

---

## How it works

The camera is a Bluetooth LE GATT server; the phone is the client. All values are little-endian and
every write is a write with response.

- **Registration** (every connection, on a bonded link): read the 4-byte status from `f557d96b`,
  write it back with the last byte set to `0x20`, write the client name to `85b9163e`, then
  subscribe to the camera-state indications and notifications.
- **Camera info**: standard Device Information (`180A`) plus Fujifilm's camera-information, lens,
  camera-settings and startup-information services.
- **Settings backup** (file-transfer service `af854c2e`): write `BACKUP_REQUEST = 1`, wait for
  `BACKUP_STATE = 1`, set a 120-byte chunk size and file index 0, then read one chunk
  (`u16 seq, u32 len, data`) per transaction-state notification. The last chunk (`seq = 0xFFFF`)
  ends with a 16-bit sum of all data bytes. Finally read the file name and size and write
  `FILE_TRANSFER_RESULT = 3`.

Every UUID, with its source, is in
[`FujiGatt.kt`](app/src/main/java/com/fujiapp/remote/ble/FujiGatt.kt).

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

To publish to the `app-latest` release and keep a stable upgrade key, add these repository secrets:
`FUJI_KEYSTORE_BASE64`, `FUJI_KEYSTORE_PASSWORD`, `FUJI_KEY_ALIAS`, `FUJI_KEY_PASSWORD`. Without
them the APK is signed with a throwaway debug key. That's fine for sideloading, but it won't upgrade
a differently-signed install.

---

## Architecture

```
app/src/main/java/com/fujiapp/remote/
├── ble/
│   ├── FujiGatt.kt        Every service/characteristic UUID, with sources
│   ├── FujiProtocol.kt    Advertisement parsing, status ack, client name, value decoding (pure, tested)
│   ├── FileTransfer.kt    Backup chunk format, reassembly and checksum (pure, tested)
│   ├── GattClient.kt      Coroutine wrapper around BluetoothGatt (one operation at a time)
│   ├── FujiCamera.kt      Registration, camera info, backup download
│   ├── CameraScanner.kt   Finds cameras by Fujifilm's company ID (0x04D8)
│   └── Bonding.kt         System pairing (numeric comparison)
├── model/                 CameraInfo, BackupMeta
├── data/BackupStore.kt    Backups in app storage (.dat as received + .json metadata)
├── util/ProtocolLog.kt    In-memory Bluetooth log for troubleshooting
├── CameraSession.kt       Scan → connect → pair → register → info / backup
├── App.kt                 Holds the process-wide CameraSession
└── ui/                    MainActivity, CameraInfoActivity, BackupsActivity
```

---

## References

- [`sarmadjari/GeoShutter`](https://github.com/sarmadjari/GeoShutter): secure pairing and session
  setup, tested on an X100VI (`docs/fujifilm-protocol.md`)
- [`Th0rgal/fuji-bridge`](https://github.com/Th0rgal/fuji-bridge): the settings-backup transfer, from
  XApp (`FujiBridge/FujiBackup.swift`)
- [`tiredboffin/fffw`](https://github.com/tiredboffin/fffw): per-model GATT tables
  (`ffbt/cfg/gatt-ad14d4.yaml` is the X100VI 1.31)
- [`MaxRink/furble`](https://github.com/MaxRink/furble): firmware-checked UUID table
  (`docs/camera-research/fujifilm-secure-gatt.md`)
- [`gkoh/furble`](https://github.com/gkoh/furble) and [`petabyt/libfuji`](https://github.com/petabyt/libfuji):
  the original Fujifilm BLE reverse-engineering
- FUJIFILM Camera Remote 4.10.2: characteristic names and value formats for camera information

## License

MIT. See [LICENSE](LICENSE).

This project is not affiliated with or endorsed by Fujifilm. "Fujifilm", "X100VI", and film
simulation names are trademarks of FUJIFILM Corporation, used here only to describe interoperability.
