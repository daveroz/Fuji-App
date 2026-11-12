# Fuji Remote

An open-source Android companion for Fujifilm cameras. It's built for the **X100VI** (firmware
1.30 and later, with Fujifilm's "secure" Bluetooth pairing). Everyday use is **Bluetooth LE**, and it
covers what Fujifilm's XApp does over Bluetooth, without the clutter. Loading recipes into the
camera's custom slots uses a **USB-C cable**, the only way the camera accepts them one slot at a time.
**Photos** and **remote shooting** use the camera's own **Wi-Fi**, started from Bluetooth as XApp does.

- **Camera**: live status of everything the camera reports, a remote shutter release, and
  **Photos** (browse the card, import) and **Remote** (live view, exposure, shutter) over Wi-Fi
- **Recipes**: the C1–C7 custom settings on the camera; a library of 30 recipes after film stocks
  and films; a **Lab** to try settings on a picture; and loading any recipe into a slot (over USB,
  after a backup)
- **Equipment**: shutter count and every usage counter the camera keeps
- **Backups**: back up the camera's settings (your **C1–C7 recipes** included) and restore them
- **Settings**: clock sync, optional location sync (geotagging), the camera's Bluetooth settings,
  and the **log**

No account, no ads, no cloud. **No camera firmware is decompiled, modified, flashed,
or redistributed.**

---

## The app

| Tab | What's on it |
|---|---|
| **Camera** | Before connecting: find and pair a camera, or reconnect to a paired one. Connected: a drawing of the camera, the **Photos** and **Remote** tiles (Wi-Fi, see below), a readout strip (shots, power, Wi-Fi, firmware), the **remote release** (hold to focus, let go to shoot), and every value the camera reports, decoded and kept live. **State:** power switch, Wi-Fi access point, pictures waiting, why it's busy, card, firmware update. **Camera:** model, name, serial, firmware, model code. **Connection:** paired phones, connect while off, location sync and interval, transfer settings, activity log. **Lens**, and **Technical** details. |
| **Recipes** | Three sections. **Camera**: the C1–C7 names, read over Bluetooth from the camera's settings file when you open it (about 2 seconds; offline, the newest backup stands in), and, over USB, every setting of every slot. **Library**: 30 recipes after popular film stocks (Portra, Ektar, Gold, Kodachrome, Superia, Pro 400H, Velvia, CineStill, Tri-X, HP5…) and films (Blade Runner 2049, Skyfall, The Matrix, Fury Road, Drive, Amélie, In the Mood for Love, Dune…), each with what the look is, how the settings get there, and how to shoot it. **Lab**: the preview stays on screen while the settings, in five tabs (Film, Color, Light, Tone, Detail), change it; the sample is a color chart (the 24 color-checker patches, eight skin tones, a grey ramp) or one of your photos, and a **vectorscope** in the manner of DaVinci Resolve's shows where every color lands, with the **skin-tone line** to check skin against; save the result to My recipes. Any recipe can be loaded into a slot. |
| **Equipment** | Total shots, shutter actuations against the rated shutter life, and all of the camera's usage counters: power-ons, operating, shooting and viewfinder time, viewfinder switches, flash, half-presses, OK and command-dial use, sensor cleanings, heat warnings and shutdowns, time spent with the sensor hot, battery, USB and lens counts. |
| **Backups** | Back up the camera's settings file, restore it to the same model, share it, delete it. |
| **Settings** | **Set the camera clock** (on by default, as XApp does). The camera's **Location sync** (see below), **location interval** and **Connect while power off** settings, written straight to the camera. **Log**: save, share, clear. |

The **LOG** button at the top of every screen saves the app's recording as a `.txt` file in
**Downloads** (on Android 9 and lower you pick where). The recording runs all the time and
survives restarts and crashes. It holds every Bluetooth exchange, what the app did, every error,
and the app, Android and phone versions. It holds no locations or passwords. If something looks
off, save it and send it.

### Loading a recipe into a slot

Pick a recipe (in the Library, the Lab or My recipes), tap **Load** and choose C1–C7. The app then
**always asks to back up first**: with the camera connected by Bluetooth it takes a normal backup;
otherwise it downloads the backup over USB right before writing. Either lands in **Backups** and
restores every setting, all seven slots included. You can also choose to load without one.

Then connect the camera to the phone with a **USB-C cable**, with the camera's USB connection mode
set to **USB RAW CONV./BACKUP RESTORE** (in its network/USB settings). While it's connected this way
the camera's menus are off. The app reads the slot first (kept for **Undo**), writes only the
settings that change, in the order the camera needs, writes the name, and reads everything back.
The report says what was written and what the camera refused and why. The usual one: **DR200 and
DR400 need ISO 250 / 500 or higher in that slot** (set the slot's ISO to Auto). **Undo** puts the
slot back as it was.

What's possible, and how:

| | Bluetooth | USB-C cable |
|--|--|--|
| Read the slot names | yes, from the settings file (no backup is saved unless you ask) | yes |
| Read every setting of a slot | in the settings file, but its layout isn't mapped yet | **yes**, one setting at a time |
| Change one slot, or load a recipe into a chosen slot | only by editing the settings file and restoring it, which needs that layout and a test on the camera | **yes**, documented and reported working on an X100VI |
| See a recipe on a picture | the Lab (an approximation on the phone) | the camera can also develop a RAW with a recipe over USB; not built yet |

> Fujifilm says that using any program it hasn't licensed to connect to or control a camera voids
> the camera's limited warranty ([Fuji X Weekly, 2026-06-08](https://fujixweekly.com/2026/06/08/your-cameras-warranty-might-be-voided/)).
> That covers this app, XApp alternatives and every recipe uploader. Laws differ by country.

### Photos and remote over Wi-Fi

**Photos** and **Remote** on the Camera tab work like XApp's: the app asks the camera over
Bluetooth to start its Wi-Fi, reads the network's name and password, and joins it for this app
only (Android asks you to confirm; the phone keeps its normal connection for everything else).
Then press **OK** on the camera to allow the connection.

- **Photos** lists the card, newest first, with the camera's own thumbnails. Select pictures and
  import them, or import the newest ten; they land in **Pictures/Fuji Remote** (RAW and video in
  Downloads). A picture already imported isn't copied again.
- **Remote** shows the live view and takes pictures, with shutter speed, aperture and exposure
  compensation one step at a time and ISO from the camera's own list. On the X100VI the shutter
  speed and aperture follow the dials and the aperture ring: set them to **A** to change them here.

What Bluetooth alone can do: the shutter release (half press to focus, full press to shoot), the
clock, location, and the settings this app already writes. Fujifilm's Bluetooth service has no
ISO, shutter speed or aperture; those, and the live view, need the Wi-Fi remote.

### Location sync

Location sync is the camera's own setting. While it's on, the camera asks the phone for its
location every few seconds, and the app answers each request with the phone's GPS or network
location; that's how pictures get geotagged. Turning it on in Settings asks for location access
first. While it's off, the camera doesn't ask.

The app always listens for the requests, as XApp does: an X100VI only finishes its side of the
connection once a phone has subscribed to them (see *How it works*). Requests left unanswered
during a transfer made the camera drop the link in field logs, so without location access the app
says so once and suggests turning Location sync off.

### Look

The app uses the Deco "light in fog" design language from Worldly and the Deco launcher: a night
stage in haze, glass panels with hairline edges, one amber key light ("lit" means on), and spaced
bold capital labels, set in **Carbon+** like Worldly, SAB Monitor and the Deco launcher
(`app/src/main/assets/fonts/`). The layout borrows the restraint of medium-format camera software:
generous margins, big numerals for the readings that matter, small labels and hairline rules.

The launcher icon is Mount Fuji in the same family as Worldly's and the Deco launcher's icons
(rendered from `assets/icon-*.svg` by `assets/render-icons.js`).

Behind every screen is Worldly's stage: two slow washes of amber light, fog banks drifting
through, a row of ceiling lamps running a slow chase, and fine grain. It animates only while the
app is in front.

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
- **Subscriptions** follow XApp's order: AP state and transfer state (indications), then date sync,
  **location sync**, camera name and logging setting. All of them matter. Without the location-sync
  subscription an X100VI 1.31 read its power switch as `00 00`, never asked for the time and left
  every shutter-count request unanswered; the moment it was subscribed, the power switch read
  `01 02` (on) and the camera asked for the time and the location. Transfers also wait (up to 5 s)
  for the camera to report its power switch.
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
- **Recipes**: the backup file is zlib data. Unpacked (70,524 bytes on an X100VI 1.31) it starts
  with `FUJIFILMX-BACKUP`, the format version (`0100`) and the model, and ends with the seven
  custom settings C1–C7, one 1 KB record each. A record starts with `0x15C` twice and holds the
  slot's name at `0x269` (25 characters). The other values in a record aren't mapped yet, so only
  the names are shown.
- **Shutter count**: the same download, started with `LOG_TRANSFER_REQUEST = 2`. It's read
  automatically once per connection. The file is JSON
  (`total_shot_count`, `rear_curtain_shutter_count`, `shutter_upper_limit_count`, …; 30 fields on
  an X100VI).
- **Restore**: `RESTORE_REQUEST = 1` → `RESTORE_STATE = 1` → chunk size 120, index 0,
  `FILE_INFORMATION` (`"backup.dat"` padded to 40 bytes + `u32 size`) → state 1 → write each chunk
  and wait for state 2 → state 3 means the camera has it all; write `FILE_TRANSFER_RESULT = 3`.
- **Custom setting slots over USB** (PTP, camera in USB RAW CONV./BACKUP RESTORE, vendor `0x04CB`):
  `0xD18C` selects the slot, `0xD18D` is its name (a PTP string, up to 25 characters) and
  `0xD18E`–`0xD1A4` its settings (16-bit; tones, color, sharpness and clarity signed ×10; NR a lookup;
  grain Off written as `1`, read back as `6`/`7`; DR Auto `0xFFFF`). Writes go D-Range Priority off
  before Dynamic Range, film simulation before toning, tones and color, Kelvin right after the white
  balance mode and the shifts after it, the name last; then everything is read back and compared by
  meaning. The backup over USB is `GetObjectInfo(0)` / `GetObject(0)`; it's stored compressed like a
  Bluetooth backup. Facts from [`zgrimz/fujifilm-ptp-reference`](https://github.com/zgrimz/fujifilm-ptp-reference)
  (CC BY 4.0), implemented independently.
- **Wi-Fi**: `600655e6` (function launch) takes `03 00` to browse and import or `04 00` for remote
  shooting; the camera answers on AP state (`a68e3f66`), 1 up, 0 busy. The network name is the
  camera name (`bf6dc9cf`), the password `e809256a` (never logged). Over TCP `192.168.0.1:55740` the
  same PTP containers as over USB follow an 82-byte init; after OpenSession the camera waits for OK
  (`0xDF00` in the event list `0xD212`). Import: client state `0xDF01 = 20`, the handle list
  `0xD621`, name/size/date through `GetObjectPropValue` (`0xDC07`/`0xDC04`/`0xDC08`), thumbnails
  `GetThumb`, files through `GetPartialObject` in 1 MB windows. Remote: `0xDF00 = 6`, `0xDF01 = 22`,
  `InitiateOpenCapture`, the live view on port 55742 (18-byte header + JPEG per frame), the step
  operations `0x902C`/`0x902D`/`0x902E`. After fuji-bridge (import, tested on an X100VI) and libfuji
  (remote); both MIT.
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
├── model/                 CameraSnapshot (all raw values, decoded), CameraInfo, CameraVitals, BackupMeta,
│                          SettingsFile (the backup file: header and C1–C7 names, pure, tested)
├── recipe/                Recipe (every slot setting), BankCodec (USB values and write order, tested),
│                          RecipeLibrary (the 30 recipes), MyRecipes (saved from the Lab)
├── usb/                   Ptp (containers, tested), UsbCamera (USB host PTP: slots, backup), RecipeTransfer
├── wifi/                  FujiWifi (Fujifilm PTP over TCP: init, OK, gallery, import, remote, live view),
│                          WifiJoin (the camera's network, for this app only), WifiSession
├── preview/               RecipePreview (the Lab's approximate rendering), SampleScene (the color chart),
│                          Vectorscope (with the skin-tone line)
├── data/BackupStore.kt    Backups in app storage (.dat as received + .json metadata)
├── util/                  AppLog (recorder), LogExporter (Downloads), AppSettings, PhoneLocation
├── CameraSession.kt       Scan → connect → pair → register → live status / features / transfers
├── App.kt                 Holds the session, starts the recorder, records crashes
└── ui/                    Deco (design system), Kit (view builders), StageView (the hazy stage),
                           MainActivity, RecipeFlow (load into a slot), one class per screen
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
- [`zgrimz/fujifilm-ptp-reference`](https://github.com/zgrimz/fujifilm-ptp-reference) (CC BY 4.0): the
  USB custom-slot properties, their encodings, the write order and refusal rules, and the USB backup
- [`p5k369/grawji`](https://github.com/p5k369/grawji): that older bodies' settings backups carry a
  16-bit checksum the camera checks on restore (not found in the X100VI's; nothing copied)
- The recipes are this app's own, written after studying each film stock's and film's color
  (published analyses of the films' cinematography, film-stock comparisons); none is copied from a
  published recipe

## License

MIT. See [LICENSE](LICENSE).

This project is not affiliated with or endorsed by Fujifilm. "Fujifilm", "X100VI", and film
simulation names are trademarks of FUJIFILM Corporation, used here only to describe interoperability.
