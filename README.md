# Intare

Intare is an Android application that turns your device into an **SMB file server**.
It shares a directory on your phone or tablet over the local network, so you can
browse the device's files from a computer using the standard SMB protocol —
no USB cable, no root required.

## Download

The latest signed APK is attached to the [Releases](https://github.com/ntilau/Intare/releases)
page. Download `Intare-v0.1.apk` and sideload it (you'll need to allow installs from
unknown sources for the browser/file manager). The app is also easy to build from
source — see [Building](#building).

> If you previously sideloaded a **debug** build from source, uninstall it first:
> the release APK is signed with the project's own release key, so the two can't
> be installed over one another.

## How it works

- The app runs a JFileServer-based SMB server in a foreground service.
- It shares one directory (default: external storage `/storage/emulated/0`) as a
  guest-accessible share named **`Intare`**.
- The server listens on TCP port **4450**. Port 445 (the standard SMB port) is
  reserved for root on Android, so a non-standard port is required.
- The device advertises itself over **mDNS/Bonjour** (`_smb._tcp`, hostname
  `INTARE.local`), so it appears automatically as a shared computer in the macOS
  Finder sidebar and on Windows networks — no need to type an IP address.
- The app shows the **IPs of connected devices** (devices whose SMB session is
  actually active) on the screen, and **beeps** when a device connects or
  disconnects — even while the app is closed, because the server runs as a
  background service.

### Protocol note: SMB1 only

JFileServer implements the **SMB1** dialect. Most modern systems support it but
hide it behind a flag:

- **macOS**: `smb://INTARE.local:4450/Intare` (or `smb://<ip>:4450/Intare`) —
  Finder still speaks SMB1, no setup needed, and the device shows up in the
  Finder sidebar under **Network** automatically.
- **Linux**: `smbclient -p 4450 -m NT1 //<ip>/Intare`
- **Windows**: enable the SMB1 client feature, or on Windows 11 24H2+ use
  `net use Z: \\<ip>\Intare /TCPPORT:4450`

No password is required (guest access). Keep it on a trusted network.

## Requirements

- Android 7.0+ (API 24), targeting API 34
- Java Development Kit (JDK) 17 or higher to build
- Gradle 8.5 (or use the Gradle wrapper)
- "All files access" permission, granted from the app's settings screen

## Building

To build the debug APK:

```bash
./gradlew assembleDebug
```

The debug APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

## Installation

Connect an Android device or start an emulator, then run:

```bash
./gradlew installDebug
```

Alternatively, install manually with ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. Open the app — the SMB server starts automatically (once "All files access" is
   granted) and a persistent notification confirms it is running.
2. Optionally change the share directory in the field above the button before it
   starts (default is the whole of external storage).
3. The server keeps running as a **background service** after you close the app
   (back / swipe from Recents), so the share stays available. To stop it, open the
   app and tap **Stop SMB server**, or use the **Stop** action on the notification.
   If the system kills the service it restarts automatically, re-sharing the same
   directory.
4. From your computer, connect with one of the commands shown on screen — or, on
   a Mac, just click **INTARE** in the Finder sidebar under **Network**.
5. Watch the **Connected devices** line on the screen: it lists the IPs of devices
   with an active session. The phone beeps once when a device connects and once
   when it disconnects, so you notice activity without looking at the screen.

To verify the server is listening and advertised:

```bash
# port 4450 (0x1162) is listening on the device
adb shell "ss -tln" | grep 4450

# the mDNS advertisement is reachable from a computer on the same network
dns-sd -B _smb._tcp local.                 # should list INTARE
dns-sd -L INTARE _smb._tcp local.          # INTARE.local.:4450
```

Note: `smbutil lookup INTARE` will *not* resolve — that command uses NetBIOS
name service (UDP 137/138), which needs root on Android and is deliberately not
advertised. Finder's network browsing uses Bonjour/mDNS instead.

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Acknowledgments

- JFileServer (`org.filesys`, LGPL-3.0): the SMB server engine.
  https://github.com/fanosta/JFileServer
- Server wiring modeled on SimbaDroid (MPL-2.0) by Jan Henning.
  https://github.com/buttercookie42/SimbaDroid
- jmdns (Apache-2.0): mDNS/Bonjour advertisement of the SMB service.
  https://github.com/openhab/jmdns
- AndroidX libraries
