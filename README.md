# Fuji Remote

An open-source Android companion for Fujifilm cameras that works over **Bluetooth LE only**. It's
built for the **X100VI** (firmware 1.30 and later, with Fujifilm's "secure" Bluetooth pairing).
It covers what Fujifilm's XApp does over Bluetooth, without the clutter:

- **Camera**: live status of everything the camera reports, plus a remote shutter release
- **Equipment**: shutter count and every usage counter the camera keeps
- **Backups**: back up the camera's settings (your **C1–C7 recipes** included) and restore them
- **Settings**: clock sync, optional location sync (geotagging), the camera's Bluetooth settings,
  and the **log**

No Wi-Fi, no live view, no account, no ads. **No camera firmware is decompiled, modified, flashed,
or redistributed.**

---

## The app

| Tab | What's on it |
|---|---|
| **Camera** | Before connecting: find and pair a camera, or reconnect to a paired one. Connected: a readout strip (shots, power, Wi-Fi, firmware), the **remote release** (hold to focus, let go to shoot), and every value the camera reports, decoded and kept live. **State:** power switch, Wi-Fi access point, pictures waiting, why it's busy, card, firmware update. **Camera:** model, name, serial, firmware, model code. **Connection:** paired phones, connect while off, location sync and interval, transfer settings, activity log. **Lens**, and **Technical** details. |
| **Equipment** | Total shots, shutter actuations against the rated shutter life, and all of the camera's usage counters: power-ons, operating, shooting and viewfinder time, viewfinder switches, flash, half-presses, OK and command-dial use, sensor cleanings, heat warnings and shutdowns, time spent with the sensor hot, battery, USB and lens counts, and the camera's error log. |
| **Backups** | Back up the camera's settings file, restore it to the same model, share it, delete it. |
| **Settings** | **Set the camera clock** (on by default, as XApp does). **Location sync** (off by default; see below). The camera's own **Smartphone location sync**, **location interval** and **Connect while power off** settings, written straight to the camera. **Log**: save, share, clear. |

The **LOG** button at the top of every screen saves the app's recording as a `.txt` file in
**Downloads** (on Android 9 and lower you pick where). The recording runs all the time and
survives restarts and crashes. It holds every Bluetooth exchange, what the app did, every error,
and the app, Android and phone versions. It holds no locations or passwords. If something looks
off, save it and send it.

### Location sync

When the camera's *Smartphone location sync* is on, it asks the phone for its location every few
seconds. Answering those requests is how XApp geotags pictures. In this app it's **off by
default**, and while it's off the app doesn't listen to the requests at all. In field logs from an
X100VI, every transfer the camera dropped came 1–2 s after a location request that went
unanswered. Turn it on in Settings to geotag: the app then asks for location access, listens to
the phone's GPS and network location while a camera is connected, and answers each request.

### Look

The app uses the Deco "light in fog" design language from Worldly and the Deco launcher: a night
stage, glass panels with hairline edges, one amber key light ("lit" means on), and spaced bold
capital labels. The layout borrows the restraint of medium-format camera software: generous
margins, big numerals for the readings that matter, small labels and hairline rules.

Worldly's typeface (Carbon+) isn't bundled here, because it comes with its own license. To get it,
copy `carbonplus.otf` and `carbonplus-Bold.otf` from Worldly's `app/src/main/assets/fonts/` into
this app's `app/src/main/assets/fonts/`. The app picks them up automatically; without them it uses
the system sans-serif.

---

## Using the app

**First time (pairing):** on the camera, open **PAIRING REGISTRATION** in its Bluetooth /
smartphone settings. In the app, tap **Find camera** and pick the camera. Confirm the six-digit
code on the camera (**MENU/OK**, within about 30 s) and on the phone.

**Later:** tap your camera under **Paired cameras**, then switch the camera on (or off and on
again). A paired X100VI only advertises for a few seconds after it's switched on, so the app waits
for it.

> The phone's Bluetooth pairing is shared with XApp. If you pair with XApp afterwards, the camera
> may need to be paired with this app again.

---

## How it works

The camera is a Bluetooth LE GATT server; the phone is the client. All values are little-endian and
every write is a write with response. Every UUID, with its source, is in
[`FujiGatt.kt`](app/src/main/java/com/fujiapp/remote/ble/FujiGatt.kt).

- **Registration** (every connection, on a bonded link): read the 4-byte status from `f557d96b`,
  write this phone's identification number back, write the client name to `85b9163e`, then
  subscribe. On the pairing connection, the number is the camera's value with the last byte set to
  `0x20`. Afterwards it's **the number stored from that pairing**, as XApp does. In field logs the
  camera offered a changing value (`9a 8d 06`, `9c 8d 06`, …) and dropped the link when that was
  echoed back. After such a rejection it ignored transfer requests for 1–2 minutes. Writing the
  stored `65 48 26 20` worked every time.
- **Live status**: every readable characteristic is read on connect and on refresh. Power,
  connect-while-off, location settings and vital-data changes notify, and the screen updates
  as they arrive.
- **Clock**: when the camera asks (`DATE_SYNC_STATE = 1`), the app writes the 12-byte UTC and time
  zone packet to `c52edbce`.
- **Location**: when location sync is on, each request (`LOCATION_SYNC_STATE = 1`) gets the 23-byte
  packet on `0f36ec14` (latitude and longitude × 10⁷, altitude, speed, UTC time).
- **Remote release**: `7fcf49c6` takes `1` half press, `2` full press, `0` release.
- **Files** go through the file-transfer service `af854c2e` in 120-byte chunks:
  `u16 seq, u32 len, data`. `seq` counts from 0 and the last chunk is `0xFFFF`. Its data ends with
  the 16-bit sum of every data byte. Progress comes on `FILE_TRANSACTION_STATE` (`u16 state,
  u16 seq`): 1 start, 2 transferring, 3 file finished, 4 done, 0 error.
- **Every transfer** turns its notifications on at the start and off at the end, as XApp does.
  While they're on, the camera keeps the session open and answers the next request with "busy". A
  "busy" or unanswered request gets `FILE_TRANSFER_RESULT = 2` (cancel) and one more try.
- **Backup**: `BACKUP_REQUEST = 1` → `BACKUP_STATE = 1` → chunk size 120, index 0 → read a chunk on
  each state 1/2 → on state 3 check the sum, read the file name and size, then write
  `FILE_TRANSFER_RESULT = 3, 1`. The file is compressed settings data, about 2 KB on an X100VI.
- **Shutter count**: the same download, started with `LOG_TRANSFER_REQUEST = 2`. The file is JSON
  (`total_shot_count`, `rear_curtain_shutter_count`, `shutter_upper_limit_count`, …; 30 fields on
  an X100VI).
- **Restore**: `RESTORE_REQUEST = 1` → `RESTORE_STATE = 1` → chunk size 120, index 0,
  `FILE_INFORMATION` (`"backup.dat"` padded to 40 bytes + `u32 size`) → state 1 → write each chunk
  and wait for state 2 → state 3 means the camera has it all; write `FILE_TRANSFER_RESULT = 3`.
- **Recovery**: connecting retries up to 3 times, a dropped link reconnects on its own, and backups
  and shutter-count reads retry once on a fresh connection. A restore never retries on its own.

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
│   ├── FujiProtocol.kt    Status ack, time/location packets, state decoding (pure, tested)
│   ├── FileTransfer.kt    Chunk format, checksum, restore encoding, state/result codes (pure, tested)
│   ├── GattClient.kt      Coroutine wrapper around BluetoothGatt (one operation at a time, retries)
│   ├── FujiCamera.kt      Registration, status, settings, shutter, backup, restore, shutter count
│   ├── CameraScanner.kt   Finds cameras by Fujifilm's company ID (0x04D8)
│   └── Bonding.kt         System pairing (numeric comparison)
├── model/                 CameraSnapshot (all raw values, decoded), CameraInfo, CameraVitals, BackupMeta
├── data/BackupStore.kt    Backups in app storage (.dat as received + .json metadata)
├── util/                  AppLog (recorder), LogExporter (Downloads), AppSettings, PhoneLocation
├── CameraSession.kt       Scan → connect → pair → register → live status / features / transfers
├── App.kt                 Holds the session, starts the recorder, records crashes
└── ui/                    Deco (design system), Kit (view builders), MainActivity, one class per screen
```

---

## References

- FUJIFILM XApp 2.7.6 for Android: static analysis for interoperability of the restore, vital-data
  and file-transfer sequences, the state values and the identification-number handling. Only
  identifiers, byte values and the order of operations were taken. No code or interface was
  copied, and nothing from the app is redistributed.
- [`sarmadjari/GeoShutter`](https://github.com/sarmadjari/GeoShutter): secure pairing, session
  setup, time and location packets, tested on an X100VI (`docs/fujifilm-protocol.md`)
- [`Th0rgal/fuji-bridge`](https://github.com/Th0rgal/fuji-bridge): the settings-backup download
- [`tiredboffin/fffw`](https://github.com/tiredboffin/fffw): per-model GATT tables and the shape of
  the vital-data JSON
- [`MaxRink/furble`](https://github.com/MaxRink/furble): firmware-checked UUID table and shutter values
- [`gkoh/furble`](https://github.com/gkoh/furble) and [`petabyt/libfuji`](https://github.com/petabyt/libfuji):
  the original Fujifilm BLE reverse-engineering
- FUJIFILM Camera Remote 4.10.2: characteristic names and value formats for camera information

## License

MIT. See [LICENSE](LICENSE).

This project is not affiliated with or endorsed by Fujifilm. "Fujifilm", "X100VI", and film
simulation names are trademarks of FUJIFILM Corporation, used here only to describe interoperability.
