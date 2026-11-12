# Fuji Remote

An open-source Android app that connects to a Fujifilm camera (built and tested against the
**X100VI**) over its own WiFi, to:

- **Live view** — stream the camera's live feed to your phone
- **Remote control** — trigger the shutter and step shutter speed / aperture / exposure compensation
- **Recipe backup & restore** — read the camera's current "look" (film simulation + grain, dynamic
  range, tone curves, white balance, etc.), save it to a file, and write it back later or onto
  another body — your custom film-simulation **recipes**, backed up and transferable

It talks to the camera using the **standard PTP/IP protocol** (the same Picture Transfer Protocol
over WiFi that Fujifilm's own *Camera Remote / XApp* uses). This is interoperability work based on
**public reverse-engineering of the network protocol** — the same approach the `libgphoto2`
project uses. **No camera firmware is decompiled, modified, flashed, or redistributed by this
project.** Nothing here voids your warranty or risks bricking the camera.

---

## What "custom film simulations" means here (please read)

There is a hard limit worth understanding up front:

- The X100VI has **no way to load a brand-new film-simulation LUT** (a new baked-in "look" like
  Velvia) through any supported interface. That genuinely only exists via firmware modification,
  which this project deliberately does **not** do.
- What you *can* do — and what the photography community actually means by "custom film sims" — is
  work with **recipes**: the full set of parameters that define a look (base film simulation, grain,
  Color Chrome, white balance + shift, highlight/shadow tone, color, sharpness, clarity, noise
  reduction, …). This app reads those parameters off the camera, stores them as a portable JSON
  file, and writes them back. That is a legal, firmware-free backup/restore and transfer of your
  custom looks.

---

## Status — honest summary

This is a **complete, buildable foundation** with a faithful implementation of the Fuji PTP/IP
protocol reconstructed from the best public references (see below). The byte-level framing, the
connect handshake, the live-view frame format, and the recipe model are all implemented and the
pure-protocol layer is covered by unit tests.

**It has not yet been validated against real X100VI hardware by this project.** The X100VI (2024)
is newer than every public reference, so some vendor specifics may need small adjustments once you
test against your camera:

- The exact connect handshake values and whether the camera accepts the client on the first `OK`.
- A few Fuji vendor **property codes** (e.g. shutter-speed is `0xD219` vs `0xD240` across sources;
  Color Chrome / Clarity codes are undocumented). To handle this the app **discovers properties at
  runtime** via `GetDeviceInfo` instead of hardcoding a list — so recipe backup/restore adapts to
  whatever your body actually exposes, and unknown codes are still saved (shown by hex).
- The live-view packet header (18-byte header, JPEG at offset 18) is confirmed by two references but
  worth a sanity check.

If something doesn't work on-device, a 2-minute `tcpdump`/Wireshark capture of the official app
talking to the camera (filter the three ports below) will show the exact bytes to adjust — and all
the constants live in one file: [`PtpConstants.kt`](app/src/main/java/com/fujiapp/remote/net/PtpConstants.kt).

---

## Using the app

1. On the **camera**, start the wireless connection / remote-control mode
   (Fujifilm's *Camera Remote* / "PC/Phone" connection).
2. On your **phone**, join the camera's WiFi network (Settings ▸ WiFi — the SSID looks like
   `FUJIFILM-X100VI-xxxx`). The camera's network has no internet; that's expected.
3. Open **Fuji Remote**, tap **Connect**, and press **OK on the camera** when it asks to allow the
   connection. (The app binds its sockets to the camera's WiFi so this works even though your phone
   keeps cellular for internet.)
4. Use **Live view & remote** to see the feed and control the camera, or **Film recipes** to back up
   and restore looks.

### Network details (for the curious / for debugging)

Once joined to the camera's access point:

| Channel          | TCP port | Purpose                                   |
|------------------|----------|-------------------------------------------|
| Command / data   | `55740`  | PTP operations (get/set properties, capture) |
| Event            | `55741`  | Camera events                             |
| Live-view stream | `55742`  | Length-prefixed JPEG frames (image at offset 18) |

Camera IP: `192.168.0.1`.

---

## Building

The APK is built in CI (GitHub Actions) exactly like the reference toolchain — **AGP 8.13.2,
Gradle 8.14.3, compileSdk 36, Java 17** — and every push uploads `FujiRemote.apk` as a build
artifact.

- **Via GitHub Actions:** push to a branch (or run the *Build APK* workflow manually). Download the
  `FujiRemote.apk` artifact from the run, then `adb install -r FujiRemote.apk`.
- **Locally** (needs the Android SDK): `./gradlew assembleRelease` →
  `app/build/outputs/apk/release/app-release.apk`.
- **Tests:** `./gradlew testReleaseUnitTest`.

### Stable signing (optional)

To publish to the `app-latest` release and keep a stable upgrade key, add these repository secrets:
`FUJI_KEYSTORE_BASE64`, `FUJI_KEYSTORE_PASSWORD`, `FUJI_KEY_ALIAS`, `FUJI_KEY_PASSWORD`. Without
them the APK is signed with a throwaway debug key (fine for sideloading, but it won't upgrade a
differently-signed install).

---

## Architecture

```
app/src/main/java/com/fujiapp/remote/
├── net/
│   ├── PtpConstants.kt   All protocol constants in one place (ports, opcodes, property codes)
│   ├── PtpBuffer.kt      Little-endian packet reader/writer
│   ├── FujiTransport.kt  Socket + Fuji PTP/IP framing (init handshake, operation request/response)
│   ├── FujiClient.kt     Handshake, get/set properties, device-info enumeration, capture, live view
│   ├── LiveView.kt       Live-view JPEG frame reader (port 55742)
│   ├── CameraNetwork.kt  Binds sockets to the camera's no-internet WiFi
│   └── PropDesc.kt       A device property description
├── model/Recipe.kt       Recipe + property model, JSON (de)serialisation
├── data/RecipeStore.kt   Saves recipes as JSON files in app storage
├── CameraSession.kt      Orchestrates connection, live-view loop and recipe ops (coroutines)
├── App.kt                Holds the process-wide CameraSession
└── ui/                   MainActivity (connect), LiveViewActivity, RecipeActivity
```

Design choices that make this robust on an unvalidated body:

- **Runtime property discovery.** The app asks the camera which properties it supports rather than
  assuming a fixed list, so recipe backup/restore works even where vendor codes are undocumented.
- **Isolated constants.** Every magic number lives in `PtpConstants.kt`, documented with its source.
- **Separate live-view socket.** Live view streams on its own connection, so remote-control commands
  work while the feed is running.

---

## References (public protocol reverse-engineering)

- [`petabyt/libfuji`](https://github.com/petabyt/libfuji) + [`petabyt/fudge`](https://github.com/petabyt/fudge) — reference Android Fuji client and its C library
- [`malc0mn/ptp-ip`](https://github.com/malc0mn/ptp-ip) — Go PTP/IP implementation with Fuji support
- [`hkr/fuji-cam-wifi-tool`](https://github.com/hkr/fuji-cam-wifi-tool) — early Fuji WiFi protocol RE
- [`gphoto/libgphoto2`](https://github.com/gphoto/libgphoto2) — the ptp2 camera library

## License

MIT — see [LICENSE](LICENSE).

This project is not affiliated with or endorsed by Fujifilm. "Fujifilm", "X100VI", and film
simulation names are trademarks of FUJIFILM Corporation, used here only to describe interoperability.
