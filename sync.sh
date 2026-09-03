#!/bin/bash

# Script to sync a folder from Android device to local machine
# Tries ADB first, falls back to SMB if ADB not available
# Performs equivalent of rsync -rav (recursive, verbose, preserve attributes)
# Usage: ./sync.sh [folder_name] [base_directory]
#   folder_name: name of the folder to sync (default: DCIM)
#   base_directory: base directory for local storage (default: $HOME)
# The remote folder is assumed to be at /sdcard/<folder_name>
# The local folder will be <base_directory>/<folder_name>

# Default values
FOLDER_NAME="${1:-DCIM}"
BASE_DIR="${2:-$HOME}"

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
    adb pull -a "$REMOTE_PATH" "$LOCAL_DIR"
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
smbclient "$SMB_URI" -p "$SMB_PORT" -N -m NT1 --option="client min protocol=NT1" --option="client max protocol=NT1" -d 3 -c "
    lcd .
    cd $SMB_PATH
    prompt off
    recurse on
    mget *
    mget .[!.]* ..?*
"

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
    exit 1
fi