# Applying recipes over Bluetooth: viability

*Research for Fuji Remote, November 2026. X100VI, firmware 1.31–1.32.*

## Short answer

**Viable.** Another app already does it on the X100VI: Filmsim's wireless send writes a recipe into
a chosen C1–C7 slot over Bluetooth, reading the camera first and "changing only the slot you picked
and sending everything else back untouched"; a set of seven takes about a minute, and a release
note fixes it on X100VI firmware 1.32 ([Filmsim](https://filmsimrecipes.com/send-recipes-over-bluetooth/),
[App Store](https://apps.apple.com/us/app/filmsim-recipes/id6775952554)). Fuji Recipes says it writes
to C1–C7 over Bluetooth or cable as well ([fujirecipes.co](https://fujirecipes.co/)).

That behaviour, a minute for seven slots and everything else sent back, is the **settings-file round
trip**: download the camera's settings backup, patch the slot records, restore the whole file.
Fuji Remote already does both ends over Bluetooth. What's missing is the **layout of a slot record**.

## What Bluetooth offers

- No characteristic for a custom slot or a single image setting. The full GATT table (XApp 2.7.6,
  fffw's per-model tables, GeoShutter, fuji-bridge) has the shutter release, time, location, a few
  camera settings, and the file service.
- The file service moves three files: the **settings backup** (download), a **restore** of it
  (upload) and the vital data (download). Fuji Remote implements all three.
- So a recipe over Bluetooth = backup → patch → restore. Over USB the camera has per-setting slot
  properties instead (`0xD18C`…`0xD1A4`), which Fuji Remote uses today.

## What's known about the settings file (X100VI 1.31)

- Sent zlib-compressed (about 1.9 KB); 70,524 bytes unpacked.
- Header: `FUJIFILMX-BACKUP`, format `0100`, the model at `0x14`, a table of sizes from `0x98`.
- The **seven slots are the last seven 1 KB records**. Each starts with `0x15C` twice and holds the
  slot's name at `0x269` (and a second copy at `0x283`), up to 25 characters.
- In seven slots with different recipes, 96 bytes before the names differ: the 23 image settings,
  plus the other things a slot stores (AF, drive, display…). Values are single bytes, and `09`
  appears wherever a ±9 white-balance shift is zero, so at least some fields are offset codes.
- Older bodies use **their own codes** in this file, not the USB ones (grawji's X100F and X-T3
  layouts: film simulations in menu order, 256-byte records). The X100VI's records are larger, so
  those layouts don't carry over.

## Integrity checks

- On the X-T3, a 16-bit byte sum at offset 176 guards the file and the camera refuses a restore
  whose sum doesn't match ([grawji](https://github.com/p5k369/grawji)).
- On the X100VI, two of the user's backups taken 8 hours apart differ in 13 bytes: the last file
  name (`SRGC2120` → `SRGC2123`), two frame counters, a few timestamps. Offset 176 kept the same
  value (`0x0335`), so it isn't a sum of the file there. A 16-bit value at `0x108` moved by +2 while
  the bytes' sum moved by −648: a counter, not an additive checksum. No CRC-32, Adler-32 or byte sum
  over the obvious ranges matches any header field.
- Together with Filmsim's working send, the likely reading: **no file-level checksum that blocks an
  edited file on the X100VI**, beyond the transfer's own 16-bit chunk sum and zlib's Adler-32, both
  of which Fuji Remote already produces.

## The plan

1. **Map the record.** In one session: *Read all settings over USB* (0.7.0 logs every value of
   every slot) and a Bluetooth read of the recipes or a backup (the log already carries the whole
   download). Each slot then gives 23 known values against its 1 KB record. Seven slots with
   different recipes pin most fields; a few single-setting edits (change one setting in a slot, read
   again) pin the rest and their codes.
2. **First write: the name only.** Patch C7's name (harmless), restore, read the names back. That
   proves the camera takes an edited file, with nothing at stake.
3. **Then the settings,** one slot at a time, read back after each restore, the original backup kept
   (the app already asks for a backup first) and restored if anything reads back wrong.
4. **Ship it** as the Bluetooth path for Load into a slot, keeping USB as the exact, per-setting path.

## Risks

- A restore replaces every setting, not just one slot: an error in the patcher can change more than
  the recipe. Mitigation: patch only the mapped bytes of the chosen record, compare the rest of the
  file byte for byte before sending, back up first, and verify by reading back.
- Firmware updates can move the layout. Mitigation: check the file size and the record markers, and
  refuse to patch an unknown layout (as grawji's `blob_size` guard does).
- Fujifilm says using unlicensed software to connect to or control a camera voids its warranty
  ([Fuji X Weekly](https://fujixweekly.com/2026/06/08/your-cameras-warranty-might-be-voided/)).
