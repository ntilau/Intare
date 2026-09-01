# JFileServer patches

`app/libs/jfileserver.jar` is built from
[`buttercookie42/jfileserver@ff550a7`](https://github.com/buttercookie42/jfileserver)
(the fork SimbaDroid uses), with five small patches applied. The modified
source files are checked in here (their license headers are intact, LGPL-3.0).

## Why

macOS Finder / Photos browse the share with SMB1 Trans2 FindFirst2/FindNext
using info level 260 (`SMB_FIND_FILE_BOTH_DIRECTORY_INFO`) and a maximum
return data length of 65535 bytes.

When a directory is large enough that a find response fills to that limit,
`FindInfoPacker.packInfoDirectoryBoth` (and the other directory/full-info
packers) finish each entry with `longwordAlign()`, which can push the buffer
write position up to 7 bytes **past** the nominal entry length. The packing
loop's capacity check uses `FindInfoPacker.calcInfoSize()`, which does not
count that alignment padding, so a response can overrun its backing array by
a few bytes. The buffer position then lands past the end of the array
(e.g. position 65536 in a 65535-byte buffer), and
`SMBSrvTransPacket.doTransactionResponse()` -> `DataBuffer.copyData()` throws

```
ArrayIndexOutOfBoundsException: src.length=65535 srcPos=0 dst.length=66000 dstPos=72 length=65536
```

The SMB session is closed ("Closing session due to exception"), the client
sees "Broken pipe", and Finder fails to mount the share / browse the folder.

Only large directories trigger it (the camera folder with ~16k photos does;
a directory with a handful of files never fills a response).

---

After that crash was fixed, macOS's kernel smbfs still failed to enumerate
the ~16k-file camera folder to the end: it paged a few FindNext responses
then went silent (the listing never completed). Three further patches address
that, plus a latency win (below).

## Patches

### 1. `org/filesys/util/DataBuffer.java` — `copyData()` defensive clamp

Clamp the copy length to the backing array bounds so `System.arraycopy`
never reads past the array, whatever put the logical end position past it:

```java
int siz = m_endpos - m_pos;

if (siz > cnt)
    siz = cnt;

// Guard against reading past the backing array (a packing overrun can leave
// m_endpos past the end of m_data by the trailing alignment gap). Clamp the
// copy length to the array bounds so the server never throws here.
if (m_pos + siz > m_data.length)
    siz = m_data.length - m_pos;
```

The truncated tail is only the empty alignment gap after the last entry, so
the response data block stays valid.

### 2. `org/filesys/smb/server/FindInfoPacker.java` — `calcInfoSize()` reserve alignment

Reserve the worst-case alignment padding (7 bytes) for the info levels whose
packers call `longwordAlign()` after each entry, so the packing loop stops
before it can overrun:

```java
//  The directory/full-info packers (packInfoDirectory, packInfoDirectoryBoth,
//  packInfoFileName, ...) finish each entry with a longwordAlign() that can pad
//  the buffer position up to 7 bytes past the nominal entry length. calcInfoSize
//  must reserve that padding, otherwise a find response that fills to the
//  client's maximum buffer size can overrun the reply buffer by a few bytes and
//  crash the SMB session (ArrayIndexOutOfBoundsException in DataBuffer.copyData).
switch (infoLev) {

    //  Information levels that longword-align after each entry
    case InfoNames:
    case InfoDirectory:
    case InfoFullDirectory:
    case InfoDirectoryBoth:
    case InfoFullDirectoryId:
    case InfoDirectoryBothId:
        len += 7;
        break;
}
```

### 3. `org/filesys/smb/server/NTProtocolHandler.java` — cap find-response data size

macOS kernel smbfs asks for find responses up to its full 65535-byte limit.
Filling that much makes it stall partway through a very large directory
(observed: ~3 paged responses then silence). Capping the response data well
below the client's limit keeps the paging flowing — the client just issues
more FindNext requests. Both find handlers were patched:

```java
int maxLen = replyBuf.getReturnDataLimit();
// Cap find-response data well below the client's full 65535-byte limit...
if (maxLen > FindResponseDataCap)
    maxLen = FindResponseDataCap;
```

with `public static final int FindResponseDataCap = 24000;`. Each response
then holds ~176 entries instead of ~480; the directory enumerates to the end.

### 4. `org/filesys/smb/server/FindInfoPacker.java` — pack file ids into FileIndex

macOS requests `SMB_FIND_RETURN_RESUME_KEYS` with info level 0x104
(`SMB_FIND_FILE_BOTH_DIRECTORY_INFO`). Per MS-CIFS, the `FileIndex` field of
each entry must then be a valid resume key. JFileServer gated the file-id
field behind a hardcoded `EnableFileIdPacking = false`, so every entry came
back with `FileIndex = 0`, which macOS's smbfs treats as "no id" — it refuses
to key its vnode cache on the entry and falls back to per-file lookups.
Enabling the flag writes the driver-provided file id (a stable per-path hash
from `JavaNIOSearchContext.setFileId`) into the slot instead:

```java
private static final boolean EnableFileIdPacking = true;
```

### 5. `org/filesys/smb/server/nio/NIOSMBConnectionsHandler.java` — TCP_NODELAY

The accepted SMB socket kept Nagle enabled (Java default), so every small
SMB1 request/response pair stalled on the delayed-ACK timer (~40ms). The
protocol is chatty (macOS issues a per-file lookup for each directory entry),
so this halved the effective per-file latency:

```java
// Disable Nagle's algorithm. The SMB protocol is very chatty with lots of
// small request/response pairs (macOS kernel smbfs issues a per-file lookup
// for every directory entry on large listings), and with Nagle enabled each
// exchange stalls on the delayed-ACK timer (~40ms), making listings crawl.
sockChannel.socket().setTcpNoDelay(true);
```

## Reproduce

With the upstream jar and the real device filenames
(`adb shell ls -1 /storage/emulated/0/DCIM/Camera`), simulating the FindNext
packing loop over rotated resume points yields:

- **unpatched:** `overflows=2 maxPos=65536` (matches the crash exactly)
- **patched:**   `overflows=0 maxPos=65528`

## Rebuilding the jar

The source files checked in here are the current patched versions; the other
upstream classes live in `app/libs/jfileserver.jar`. Rebuild from the jar:

```sh
# from a checkout of buttercookie42/jfileserver@ff550a7
cd build/ && unzip -q app/libs/jfileserver.jar && cd -
javac -nowarn -d build/ -cp build/ \
    third_party/jfileserver/org/filesys/util/DataBuffer.java \
    third_party/jfileserver/org/filesys/smb/server/FindInfoPacker.java
# NTProtocolHandler + NIOSMBConnectionsHandler are patched in-tree only;
# their sources live upstream at ff550a7 — apply the diffs above and compile:
javac -nowarn -d build/ -cp build/ \
    <workdir>/NTProtocolHandler.java <workdir>/NIOSMBConnectionsHandler.java
jar cf app/libs/jfileserver.jar -C build/ .
```

Then `./gradlew :app:assembleDebug`.

## Known macOS SMB1 limitation (documented, not patched)

Even with all of the above, macOS kernel smbfs issues a **separate single-file
`FindFirst2` lookup for ~95% of the entries in a directory listing** (its
vnode-population path for SMB1). Each costs ~41ms (RTT + client-side
processing), so a listing of N files takes ~0.04s × N — about 11 minutes for
the ~16k-file camera folder. This is inherent macOS SMB1 client behavior
(verified proportional to N, and independent of the server's find response);
no SMB1 server-side change removes it. The practical workaround is to keep
per-directory file counts low (e.g. organise `DCIM/Camera` into year/month
subfolders), or to switch to an SMB2/3 server (not available for this app —
JFileServer is SMB1-only).
