# CLAUDE.md

Guidance for working on **Intare**, an Android app that turns a phone/tablet into an
SMB **server** (shares the device's storage over the LAN, guest access, no root).

## Build, install, verify

```bash
./configure                    # one-time env check (JDK 17+, Android SDK, adb device)
./gradlew :app:assembleDebug   # ~3s incremental
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.intare/.MainActivity
```

- Target device is `RFCR707KNAH` (SM-G781B, Android 13-ish), on the same LAN as the
  dev Mac. The Mac verifies discovery with `dns-sd -B _smb._tcp local.` /
  `dns-sd -L INTARE _smb._tcp local.`.
- To drive the UI without eyes: `adb shell uiautomator dump /sdcard/ui.xml && adb pull`,
  grep for the widget's `text=` + `bounds=`, tap its center with `adb shell input tap X Y`.
  The START/STOP button is full-width; its vertical position moved when `minLines="2"`
  was added to the status TextView — always re-dump, don't trust stale coordinates.
- App logs: `adb logcat -s SmbService MdnsAdvertiser` (TAGs `SmbServer`, `MainActivity` too).

## Architecture

- **`SmbService.java`** — **background** `Service` (`specialUse` type, `START_STICKY`)
  hosting the server. Static `isRunning()` flag. `onStartCommand` runs the server on a
  background thread and persists the share path to SharedPreferences so a `START_STICKY`
  restart (null intent after a system kill) re-shares the same directory. The notification
  has a **Stop** action (`ACTION_STOP` → `stopForeground` + `stopSelf`) and a
  tap-to-open `MainActivity` content intent. `onDestroy` tears down mDNS then the server.
  `DEFAULT_SERVER_NAME = "INTARE"`. `onStartCommand` also bails out if the server thread
  is still starting (`mServerThread.isAlive()`) so rapid onResume auto-starts can't spawn
  a second server. On SMB session events (via `SmbServer.SessionEventListener`) it plays a
  beep with `ToneGenerator` on **`STREAM_ALARM`** — see the beep constraint below.
- **`SmbServer.java`** — wraps JFileServer (`org.filesys`). One `DiskSharedDevice`
  share `SHARE_NAME = "Intare"` (guest), SMB1-over-TCP on `SMB_PORT = 4450`,
  `setHostAnnouncer(false)` (see mDNS below). Uses `app/libs/jfileserver.jar` — a
  locally-built jar with patches tracked in `third_party/jfileserver/PATCHES.md`.
  Exposes SMB session lifecycle via the `SessionEventListener` interface: `sessionLoggedOn`
  = activated, `sessionClosed` = deactivated, forwarded per session id (a bare TCP probe
  that never logs on is ignored). `getConnectedClientAddresses()` lists **only** activated
  (logged-on) sessions.
- **`MdnsAdvertiser.java`** — jmdns (`org.jmdns:jmdns:3.5.12`, package `javax.jmdns`)
  advertises `_smb._tcp.local.` instance `INTARE` → `INTARE.local.:4450`. Best-effort:
  on failure it logs `"SMB will run without discovery"` and SMB is unaffected.
- **`NetworkUtils.java`** — the LAN IPv4 lookup (shared by UI and mDNS).
- **`MainActivity.java`** — start/stop control, status line (`smb://<ip>:4450/Intare`),
  share-path field, storage-permission flow. `onResume` auto-starts the server
  (`autoStartServer()` — skipped if already running or without storage permission); the
  activity never stops it, so the server persists as a background service after the app
  is closed. A shared `startServer(showResultToast)` helper does the start; the button and
  the auto-start both use it.

## Constraints that bit before — read before touching

- **No root on the device** → cannot bind port 445; the app uses **4450**. Never
  "fix" the port back to 445.
- **JFileServer is SMB1 only.** macOS Finder still speaks SMB1; Linux needs `-m NT1`;
  Windows needs SMB1 enabled or `net use Z: \\ip\Intare /TCPPORT:4450`.
- **mDNS must use jmdns + a MulticastLock, not NsdManager.** `NsdManager.registerService`
  rejects `_smb._tcp` (whitelist: _http, _ipp, _dns-sd, _afpovertcp). The Wi-Fi stack
  silently drops inbound multicast unless a `WifiManager.MulticastLock` is held — acquire
  it before `JmDNS.create`, release on stop. Bind `JmDNS.create(inet4Addr, name)` to IPv4
  only (IPv6 multicast is unreliable on Android); jmdns then advertises hostname
  `INTARE.local.` + A record automatically. `JmDNS.close()` can block ~5s → close on a
  background thread. Requires `CHANGE_WIFI_MULTICAST_STATE` (normal, install-time).
- **`smbutil lookup INTARE` will fail** ("no route to host") — that's NetBIOS
  (UDP 137/138), which needs root and is deliberately off. Finder uses Bonjour/mDNS,
  so it still works. Not a bug.
- **`stopService()` is async**: `onDestroy` (which clears `isRunning()`) is queued on the
  main looper. The stop branch of `MainActivity.onStartStopClicked` optimistically flips
  the UI to stopped then reconciles with `postDelayed(refreshUi, 300)`. The start branch
  reconciles at 1200ms. Keep that pattern when touching the button.
- **Session beeps must use the ALARM stream, not MUSIC.** `ToneGenerator(STREAM_MUSIC, 80)`
  was effectively inaudible on the Samsung device (media volume/processing). Play
  `ToneGenerator(STREAM_ALARM, 100)` so the owner hears connect/disconnect even in
  silent/DND or when media volume is muted. The beep is debounced ~2.5 s per IP because
  macOS/Windows open several SMB sessions per mount.
- **Server runs as a background service**: auto-starts on app open, keeps serving after
  the app is closed (persistent notification), and stops only via the notification's
  **Stop** action or the in-app STOP button. `START_STICKY` + persisted share path mean a
  system kill restarts it. Do not add "stop on activity destroy" back — that contradicts
  the product model.
- **jmdns needs slf4j** — satisfied by the existing `org.slf4j:slf4j-nop:2.0.9`.
- **Battery optimization**: the server is a background service, so Doze/app standby can throttle
  or kill it. `MainActivity` shows an "Allow unrestricted battery" banner (hidden once
  `PowerManager.isIgnoringBatteryOptimizations()` is true) and opens
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (falls back to the settings screen if the direct
  intent isn't available). Declared via `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. That permission is
  restricted on Google Play, but this app ships via GitHub releases so it's fine.
- **Adaptive icon**: 108dp canvas, 66dp safe circle (corners must be ≤33dp from center).
  Current icon is a dark hard-drive glyph on a white rounded tile built with ImageMagick
  (see git history for the exact composite pipeline). Verify pixel output with
  ImageMagick pixel sampling — the Read tool cannot display images in this session.
- **jfileserver jar** is committed; it contains a FindInfoPacker/DataBuffer patch. If
  you rebuild the jar from the fork, re-apply `third_party/jfileserver/PATCHES.md`.

## Verification checklist

1. `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
2. Install, launch, tap START (uiautomator dump for coords). Confirm `*:4450` listening:
   `adb shell "ss -tln" | grep 4450`.
3. From the Mac: `dns-sd -B _smb._tcp local.` lists INTARE; `dns-sd -L INTARE _smb._tcp
   local.` shows `INTARE.local.:4450`; `nc -vz <device-ip> 4450` connects over the LAN.
4. **Session events:** connect a real SMB client (a mount — a bare `nc` won't do) →
   logcat shows `Session activated: <ip>`, the device beeps, the readout lists the IP.
   Disconnect → `Session closed: <ip>`, beep, readout clears. Raw `nc` probe → neither.
5. Tap STOP: advertisement withdrawn (browse empty), port closed, UI flips back.
6. `adb logcat -b crash` stays clean across start/stop cycles.

## Releases

Public releases are tagged `v0.1`, `v0.2`, … with the signed APK attached (GitHub
Release). The repo is public: github.com/ntilau/Intare.

1. Bump `versionCode`/`versionName` in `app/build.gradle` `defaultConfig`.
2. Build + sign (SDK build-tools matching `compileSdk`, currently 34.0.0):
   ```bash
   ./gradlew :app:assembleRelease
   BT="$ANDROID_HOME/build-tools/34.0.0"
   "$BT/zipalign" -p 4 app/build/outputs/apk/release/app-release-unsigned.apk /tmp/aligned.apk
   set -a; source ~/.claude/intare/keystore.properties; set +a   # secrets live outside the repo
   "$BT/apksigner" sign --ks "$storeFile" --ks-key-alias "$keyAlias" \
     --ks-pass env:storePassword --key-pass env:keyPassword \
     --out app/build/outputs/apk/release/Intare-v$VERSION.apk /tmp/aligned.apk
   "$BT/apksigner" verify --print-certs app/build/outputs/apk/release/Intare-v$VERSION.apk
   ```
3. Commit, tag, publish:
   ```bash
   git tag v$VERSION && git push origin v$VERSION
   gh release create v$VERSION app/build/outputs/apk/release/Intare-v$VERSION.apk \
     --title "Intare v$VERSION" --notes "…"
   ```

- The signing keystore lives at `~/.claude/intare/release.keystore`, with its password
  in `~/.claude/intare/keystore.properties` — **outside the repo entirely** (chmod 600),
  never committed or shared. Keep a backup: a new signing key forces every installed
  user to uninstall before updating (the release APK is signed with this key, not the
  debug key).

## Commit hygiene

Keep build artifacts out (`local.properties` is gitignored and written by `configure`).
Version bumps: `versionCode`/`versionName` live in `app/build.gradle` `defaultConfig`.
