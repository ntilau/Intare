#!/bin/bash

# Script to sync a folder from Android device to local machine
# Tries ADB first, falls back to SMB if ADB not available
# Performs equivalent of rsync -rav (recursive, verbose, preserve attributes)
# Usage: ./sync.sh [folder_name] [base_directory]
#   folder_name: name of the folder to sync (default: DCIM)
#   base_directory: base directory for local storage (default: $HOME)
# The remote folder is assumed to be at /sdcard/<folder_name>
# The local folder will be <base_directory>/<folder_name>
#
# Example: To sync /sdcard/DCIM/Camera to ~/DCIM/Camera:
#   ./sync.sh DCIM/Camera
#
# Common mistake to avoid:
#   DO NOT run: ./sync.sh DCIM/Camera $HOME/DCIM
#   This will create ~/DCIM/DCIM/Camera instead of ~/DCIM/Camera

# Default values
FOLDER_NAME="${1:-DCIM}"
BASE_DIR="${2:-$HOME}"

# Trim whitespace from arguments (helps prevent issues with accidental spaces)
FOLDER_NAME="$(echo "$FOLDER_NAME" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
BASE_DIR="$(echo "$BASE_DIR" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"

# Detect and correct a common mistake: if BASE_DIR ends with the first path component
# of FOLDER_NAME, it likely means the user incorrectly included part of the path
# in the base directory (e.g., running ./sync.sh DCIM/Camera $HOME/DCIM)
# In this case, we correct BASE_DIR to prevent nested directory creation.
if [[ "$FOLDER_NAME" == */* ]]; then
    # FOLDER_NAME has at least one slash, extract first component
    FIRST_COMPONENT="${FOLDER_NAME%%/*}"
    # Check if BASE_DIR ends with /FIRST_COMPONENT or just FIRST_COMPONENT (no trailing slash)
    if [[ "$BASE_DIR" == *"/$FIRST_COMPONENT" ]] || [[ "$BASE_DIR" == *"$FIRST_COMPONENT" && ! "$BASE_DIR" == */ ]]; then
        # Remove the duplicated first component from BASE_DIR
        if [[ "$BASE_DIR" == *"/$FIRST_COMPONENT" ]]; then
            CORRECTED_BASE_DIR="${BASE_DIR%"/$FIRST_COMPONENT"}"
        else
            # Handle case where BASE_DIR is exactly the first component (unlikely but possible)
            CORRECTED_BASE_DIR="$(dirname "$BASE_DIR")"
        fi

        # Only apply correction if it results in a non-empty path
        if [[ -n "$CORRECTED_BASE_DIR" ]]; then
            echo "Warning: Detected likely incorrect base directory '$BASE_DIR'"
            echo "         Correcting to '$CORRECTED_BASE_DIR' to prevent nested directories"
            BASE_DIR="$CORRECTED_BASE_DIR"
        fi
    fi
fi

# Define remote path (relative to /sdcard on Android)
REMOTE_PATH="/sdcard/$FOLDER_NAME"

# Define local directory as base directory + folder name
LOCAL_DIR="$BASE_DIR/$FOLDER_NAME"

# Create local directory if it doesn't exist
mkdir -p "$LOCAL_DIR"

echo "Syncing '$REMOTE_PATH' from device to '$LOCAL_DIR'"
echo "Using rsync -rav equivalent behavior"

# Try ADB first
if command -v adb &> /dev/null && adb get-state &> /dev/null; then
    echo "Using ADB connection (archive mode)..."
    # adb pull -a is equivalent to rsync -av (archive: recursive, preserve perms, times, etc.)
    # IMPORTANT: We want to copy the CONTENTS of the remote directory to the local directory
    # So we use "$REMOTE_PATH/" (with trailing slash) to indicate "contents of"
    # And "$LOCAL_DIR/" (with trailing slash) to indicate "into this directory"
    adb pull -a "$REMOTE_PATH/" "$LOCAL_DIR/"
    if [ $? -eq 0 ]; then
        echo "Sync completed successfully via ADB (archive mode)!"
        echo "Folder synced to: $LOCAL_DIR"
        exit 0
    else
        echo "ADB sync failed, falling back to SMB..."
    fi
fi

# Fallback to SMB
echo "Attempting SMB connection..."

# First, try using a pre-mounted share at /Volumes/Intare (common on macOS)
MOUNT_POINT="/Volumes/Intare"
if [ -d "$MOUNT_POINT/$FOLDER_NAME" ]; then
    echo "Using pre-mounted SMB share at $MOUNT_POINT"

    # Use rsync if available for best attribute preservation, otherwise use cp
    if command -v rsync &> /dev/null; then
        echo "Syncing via rsync (preserves timestamps, permissions, etc.)..."
        rsync -av -- "$MOUNT_POINT/$FOLDER_NAME/" "$LOCAL_DIR/"
        RSYNC_EXIT=$?
        if [ $RSYNC_EXIT -eq 0 ]; then
            FILE_COUNT=$(find "$LOCAL_DIR" -type f | wc -l)
            echo "Sync completed successfully via mounted share (rsync)!"
            echo "Folder synced to: $LOCAL_DIR"
            echo "Transferred $FILE_COUNT files"
            exit 0
        else
            echo "Rsync failed with exit code $RSYNC_EXIT, trying cp fallback..."
        fi
    fi

    # Fallback to cp if rsync not available or failed
    echo "Syncing via cp (may not preserve all attributes)..."
    # Copy all files including hidden ones (excluding . and ..)
    # We copy contents, not the directory itself, so we use wildcards
    cp -R "$MOUNT_POINT/$FOLDER_NAME"/* "$LOCAL_DIR/" 2>/dev/null || true
    cp -R "$MOUNT_POINT/$FOLDER_NAME"/.[!.]* "$LOCAL_DIR/" 2>/dev/null || true
    cp -R "$MOUNT_POINT/$FOLDER_NAME"/..?* "$LOCAL_DIR/" 2>/dev/null || true

    FILE_COUNT=$(find "$LOCAL_DIR" -type f | wc -l)
    if [ "$FILE_COUNT" -gt 0 ]; then
        echo "Sync completed successfully via mounted share (cp)!"
        echo "Folder synced to: $LOCAL_DIR"
        echo "Transferred $FILE_COUNT files"
        echo "Note: cp may not preserve all file attributes like timestamps and permissions."
        exit 0
    else
        echo "Warning: No files found in mounted share at $MOUNT_POINT/$FOLDER_NAME"
        echo "Falling back to smbclient..."
    fi
else
    echo "Pre-mounted share not found at $MOUNT_POINT/$FOLDER_NAME"
    echo "Trying smbclient approach..."
fi

# Check if we're on macOS - if so, skip smbclient fallback
if [[ "$(uname -s)" == "Darwin" ]]; then
    echo "On macOS, skipping smbclient fallback."
    echo "If you need to sync files, please:"
    echo "  1. Ensure ADB is working, or"
    echo "  2. Manually mount the SMB share at /Volumes/Intare"
    exit 1
fi

# Fallback to smbclient (original approach)
if ! command -v smbclient &> /dev/null; then
    echo "Error: smbclient not found. Please install samba client utilities."
    echo "On Ubuntu/Debian: sudo apt install smbclient"
    exit 1
fi

# SMB server details (from Intare app)
SMB_HOST="INTARE.local"
SMB_PORT="4450"
SMB_SHARE="Intare"
SMB_PATH="$SMB_SHARE/$FOLDER_NAME"  # Folder within the share

# Build the SMB URI
SMB_URI="//${SMB_HOST}/${SMB_SHARE}"

echo "Connecting to SMB share: $SMB_URI (port $SMB_PORT)"
echo "Accessing path: $SMB_PATH"

# Use smbclient to copy the folder recursively with verbose output
# We'll change to the local directory and use smbclient to get files
cd "$LOCAL_DIR" || { echo "Failed to change to local directory"; exit 1; }

# Enable verbose mode via debug level 1 and preserve timestamps where possible
# smbclient's 'get' preserves modification time; we rely on that.
# We'll use mget with prompt off and recurse on for recursive copy.
# Using -N for no password (guest access) and -m NT1 for SMB1 protocol
# Additionally, set client min and max protocol to NT1 to ensure SMB1 only
# We get all files (including hidden) by doing two mget passes: first for non-hidden, then for hidden (excluding . and ..)
echo "Retrieving files from SMB share..."

# Store the smbclient commands in a variable for better compatibility
SMB_COMMANDS="
lcd .
cd $SMB_PATH
prompt off
recurse on
mget *
mget .[!.]* ..?*
"

# Run smbclient with the commands
smbclient "$SMB_URI" -p "$SMB_PORT" -N -m NT1 --option="client min protocol=NT1" --option="client max protocol=NT1" -d 3 -c "$SMB_COMMANDS"

# Check smbclient exit status more reliably by checking if we got any files
FILE_COUNT=$(find . -type f | wc -l)
if [ "$FILE_COUNT" -gt 0 ]; then
    echo "Sync completed successfully via SMB!"
    echo "Folder synced to: $LOCAL_DIR"
    echo "Transferred $FILE_COUNT files"
    echo "Note: SMB transfer preserves timestamps but may not preserve all permissions."
else
    echo "Warning: No files were transferred via SMB."
    echo "This could mean:"
    echo "  1. The remote folder '$SMB_PATH' is empty"
    echo "  2. There are no files matching the retrieval patterns"
    echo "  3. The share exists but access to the folder is restricted"
    echo ""
    echo "To troubleshoot:"
    echo "  1. Verify the folder exists on the device: check Intare app settings"
    echo "  2. Test manual access: smbclient //$SMB_HOST/$SMB_SHARE -U \"\" -p $SMB_PORT -c \"cd $SMB_PATH; ls\""
    echo "  3. Check that the Intare SMB server is running and showing as active"
    echo ""
    echo "Tip: On macOS, you can mount the share manually:"
    echo "     mkdir -p /Volumes/Intare && mount_smbfs //guest@$SMB_HOST/$SMB_SHARE /Volumes/Intare"
    exit 1
fi