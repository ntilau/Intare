name: sync-android-folder
description: Sync a folder from an Android device running Intare SMB server to local machine via ADB or SMB.

# Sync Android Folder Skill

This skill provides a way to sync folders (like DCIM, Pictures, etc.) from an Android device running the Intare SMB server to your local machine. It tries ADB first (when the device is connected via USB) and falls back to SMB when ADB is not available. It preserves file attributes equivalent to `rsync -rav`.

The skill includes a driver script (`.claude/skills/sync-android-folder/driver.sh`) that performs the sync when invoked with the appropriate arguments.

## Prerequisites

- An Android device running the Intare SMB server (app must be running and sharing a folder).
- For ADB method: device connected via USB with USB debugging enabled, and `adb` command available in PATH.
- For SMB fallback: `smbclient` installed (part of Samba client utilities).
- On macOS, the skill can also use a pre-mounted SMB share at `/Volumes/Intare`.

## Usage

When using the Skill tool, invoke:

```
/skill sync-android-folder [folder_name] [base_directory]
```

- `folder_name`: name of the folder to sync on the device (relative to `/sdcard`). Default: `DCIM`.
- `base_directory`: base directory for local storage. Default: `$HOME`.

The remote folder is assumed to be at `/sdcard/<folder_name>` on the device.
The local folder will be `<base_directory>/<folder_name>`.

### Examples

```bash
# Sync DCIM folder to ~/DCIM (default)
/skill sync-android-folder

# Sync Pictures folder to ~/Pictures
/skill sync-android-folder Pictures

# Sync Documents folder to custom location
/skill sync-android-folder Documents /backup/storage
```

## How it works

The driver script executes the following logic:

1. **ADB first**: If `adb` is available and a device is connected, it uses `adb pull -a` to copy the folder (archive mode, preserves timestamps and permissions).
2. **Fallback to SMB**: If ADB fails or is not available, it attempts to connect via SMB:
   - First, checks for a pre-mounted share at `/Volumes/Intare` (common on macOS).
   - If found, uses `rsync` (if available) or `cp` to copy the folder.
   - Otherwise, uses `smbclient` to retrieve the folder over the network (requires SMB1 support).
3. On macOS, if neither ADB nor a pre-mounted share is available, the skill will suggest manually mounting the SMB share.

## Manual execution

You can also run the driver script directly:

```bash
.claude/skills/sync-android-folder/driver.sh [folder_name] [base_directory]
```

The script accepts the same arguments as described above.

## Notes

- The skill preserves file attributes (timestamps, permissions) as best as possible given the transfer method.
- If you encounter issues, ensure the Intare SMB server is running on the device and the folder you're trying to sync exists at `/sdcard/<folder_name>`.
- On Linux/Windows, make sure `smbclient` is installed and that your network allows SMB traffic to the device on port 4450.