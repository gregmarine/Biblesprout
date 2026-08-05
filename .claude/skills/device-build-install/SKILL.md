---
name: device-build-install
description: Build, sign, and install the Biblesprout Android app (debug/release) to the BOOX Go 6; includes the gradle/apksigner commands, device serials, the BOOX launch/enable quirks, and the per-application-id state that does NOT travel between builds. Use whenever asked to build, install, or sideload the app.
---

## Build variants & install

- **Debug** (`com.symmetricalpalmtree.biblesprout.dev`, launcher name **"Biblesprout Dev"**) —
  active dev; installs alongside stable. **Default — always build/install debug unless told
  otherwise.**
- **Release** (`com.symmetricalpalmtree.biblesprout`, launcher name **"Biblesprout"**) —
  stable; release installs are always explicit.

```sh
export PATH="$PATH:/Users/gregmarine/development/android-sdk/platform-tools"

# Debug → app/build/outputs/apk/debug/app-debug.apk
cd apps/biblesprout_android && ./gradlew assembleDebug

# Release (unsigned — must sign before sideloading)
cd apps/biblesprout_android && ./gradlew assembleRelease
~/development/android-sdk/build-tools/35.0.0/apksigner sign \
  --ks ~/.android/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --out app/build/outputs/apk/release/app-release-signed.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk

adb -s DAF86F61 install -r <apk-path>
```

Same arrangement as Notesprout and Paintsprout: no `signingConfig` in Gradle, and release is
signed after the fact with the **debug keystore**. Good enough for sideloading, and not a Play
Store identity — a real upload key is a separate decision nobody has made yet.

Because both variants carry that one key, a release build can `install -r` over an earlier
release install with no signature mismatch and no uninstall.

**Both APKs are ~230 MB** — the content DBs (`bsb.bible` 55 MB, `mhc` 52 MB, `jfb` 21 MB,
`mhcc` 6.4 MB, `strongs.lexicon` 2.6 MB) are bundled uncompressed. Expect a slow install and
budget the disk: see the per-id storage note below.

## Devices

| Device | Serial |
|---|---|
| BOOX Go 6 Gen II (G6) | `DAF86F61` |

**Only the Go 6.** The BOOX Go 10.3 Gen 2 (`b7a46e13`) is frequently attached and belongs to
**another project** — never install to it without an explicit request. Always `adb -s <serial>`;
**never `./gradlew installDebug`**, which pushes to every eligible device.

Verify what landed where:

```sh
for s in $(adb devices | awk 'NR>1 && $2=="device" {print $1}'); do
  echo -n "$s: "; adb -s $s shell pm list packages | grep biblesprout
done
```

## BOOX quirks (device, not framework)

- **A fresh install lands disabled/stopped** on this BOOX (`enabled=3`). Enable it **once** per
  application id, or `monkey`/`am` report "No activities found to run":
  `adb -s DAF86F61 shell pm enable com.symmetricalpalmtree.biblesprout.dev`
  Component-level `pm enable <id>/<cls>` throws SecurityException (shell can't set component
  state) — the **package-level** enable is what's needed.
- **Launch with `monkey`, never `am start`:**
  `adb -s DAF86F61 shell monkey -p <application-id> 1` (without `-c LAUNCHER`).
  `am start -n <id>/<cls>` fails with a bogus **"class does not exist"** here — the same quirk
  the Flutter app hit. Note also that only the *application id* is suffixed, not the namespace,
  so the `.MainActivity` shorthand would expand to `…biblesprout.dev.MainActivity`, which does
  not exist; the class is always
  `com.symmetricalpalmtree.biblesprout.MainActivity` in both variants.
- **Screenshot** — capture to a file then pull; piping `screencap -p` over `exec-out` can emit
  an error string on BOOX:
  `adb -s DAF86F61 shell screencap -p /sdcard/s.png && adb -s DAF86F61 pull /sdcard/s.png s.png`
- **font_scale = 0.85** — the BOOX applies a system text scale; any manual text layout math must
  account for it. (The reader sizes in `sp`, so it honours this automatically.)

## What does NOT travel between the two ids

Each application id has its own `/data/data`, so a debug and a release install share nothing.

**The user's data.** `biblesprout.db` — reading position, bookmarks, highlights, handwritten
notes, commentary preference. A `.dev` install starts empty; nothing written on one variant is
visible to the other. Between *debuggable* builds it can be copied with `run-as`; `run-as` **does
not work on a release build** (it is not debuggable), so there is no way to pull data out of a
stable install short of rebuilding it debuggable.

**The installed content.** `ContentInstaller` copies the bundled DBs to
`files/content/` on first run, so **each id keeps its own ~137 MB copy** on top of its ~230 MB
APK. Both variants installed is roughly **0.75 GB** of device storage — worth checking before
sideloading stable onto a Go 6 that already holds dev.

Inspect what a debuggable install actually has:
`adb -s DAF86F61 shell run-as <id> ls -l files/content/`

**Stale content is a known trap.** Each copy is keyed by a `<name>.stamp` holding the APK's
`lastUpdateTime` + asset size. **Size alone cannot detect a content change** — SQLite pads to
whole pages, so rebuilding a DB in `data/` with a small fix can land on the identical byte
count. If a `data/` rebuild seems not to reach the device, that is the first thing to suspect;
the `ls -l` above is the check.

## The release id collides with the frozen Flutter app

`applicationId` for release is `com.symmetricalpalmtree.biblesprout` — deliberately the **same
id the Flutter app used**, so the native build replaces it. On a device where the Flutter APK is
still installed, `install -r` fails with a **signature mismatch** (different signing key);
`adb -s <serial> uninstall com.symmetricalpalmtree.biblesprout` first, which **destroys that
app's data**. The debug `.dev` id never collides, which is another reason debug is the default.

Related: root `CLAUDE.md` (data model, BOOX gotchas) and `docs/eink-constraints.md`.
