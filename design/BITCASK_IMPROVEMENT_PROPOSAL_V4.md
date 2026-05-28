# Bitcask — Improvement Proposal v4 (Final)
> Apache-style design specification for `com.bitcask`
> Java 21 · Gradle · Single-module library
> **Design approach: plain classes first — feel the pain, earn the abstraction**

---

## Decisions made in this version

| Topic | Decision |
|---|---|
| Record type byte | **Removed** — no type byte on disk. Follows original paper exactly. |
| Tombstone sentinel | `value_size == 0` means DELETE. Empty `byte[]` value. |
| Timestamp width | **8 bytes** (long, millis). Deviation from paper's 4 bytes — post-2038 safe. |
| Header size | **18 bytes**: crc(4) + timestamp(8) + key_size(2) + value_size(4) |
| Class hierarchy | **Single flat `LogRecord` class**. No `PutRecord`, `DeleteRecord`, `RecordType`. |
| `LogRecord` fields | `timestamp`, `key`, `value` only. No `crc`, `keySize`, `valueSize` as fields. |
| `encode()` signature | No parameters — uses object's own fields. |
| `decode()` signature | `public static` — creates instances, not called on existing ones. |
| `totalSize()` visibility | `private` — internal helper only. |
| `HEADER_SIZE`, `CRC_SIZE` | `public static final` — needed by `DataFile`. |
| Validation location | Constructor only — not in `encode()`. |
| Exception style | Static factory methods on `BitcaskException`. |
| Key type on disk | 2 bytes (unsigned short). Max key size 65535 bytes. |
| `toString()` format | `LogRecord{type=PUT/DELETE, ts=..., key="...", keyLen=..., valueLen=...}` |

---

## Table of Contents
1. [Project Philosophy](#1-project-philosophy)
2. [Package Layout](#2-package-layout)
3. [Implementation Order](#3-implementation-order)
4. [Phase 1 — Core Storage](#4-phase-1--core-storage)
5. [Phase 2 — In-Memory Index](#5-phase-2--in-memory-index)
6. [Phase 3 — Public API](#6-phase-3--public-api)
7. [Phase 4 — Compaction & Merge](#7-phase-4--compaction--merge)
8. [Phase 5 — Crash Recovery](#8-phase-5--crash-recovery)
9. [Phase 6 — Concurrency](#9-phase-6--concurrency)
10. [Phase 7 — Config & Lifecycle](#10-phase-7--config--lifecycle)
11. [Phase 8 — Observability](#11-phase-8--observability)
12. [Design Patterns Map](#12-design-patterns-map)
13. [Java Concepts Map](#13-java-concepts-map)
14. [Known Pain Points](#14-known-pain-points)
15. [Javadoc Standards](#15-javadoc-standards)
16. [Testing Strategy](#16-testing-strategy)
17. [What This Unlocks Next](#17-what-this-unlocks-next)

---

## 1. Project Philosophy

| Principle | What it means here |
|---|---|
| **Single responsibility** | Every class finishes the sentence "this class is responsible for ___" with one noun phrase |
| **Plain classes first** | Start with straightforward classes. Feel the friction. Refactor when the pain is real, not imagined |
| **final fields always** | Even plain classes use `final` fields — immutability does not require records |
| **Explicit over magic** | No reflection, no annotation processors, no frameworks — just plain Java |
| **Test at boundaries** | Unit-test `LogRecord` encoding, `KeyDir` updates, `DataFile` appends — not `BitcaskStore` wiring |
| **Write for the reader** | Every public method has a Javadoc. Every non-obvious line has an inline comment |

---

## 2. Package Layout

```
com.bitcask/
│
├── Bitcask.java                  ← public interface (the only API surface)
├── BitcaskStore.java             ← implements Bitcask, wires all subsystems
├── BitcaskException.java         ← single unchecked exception + static factories
│
├── storage/                      ← raw disk I/O — knows nothing about the index
│   ├── LogRecord.java            ← single concrete class: fields, encode, decode
│   ├── DataFile.java             ← one .data file: append, readAt, sync, close
│   ├── DataFileSet.java          ← manages active + immutable file collection
│   └── DataFileFactory.java      ← creates / opens DataFile instances, naming rules
│
├── index/                        ← in-memory KeyDir — knows nothing about disk
│   ├── KeyDir.java               ← HashMap-backed index, thread-safe
│   ├── KeyEntry.java             ← plain class: fileId, offset, valueSize, timestamp
│   └── ByteArrayKey.java         ← byte[] wrapper with correct equals/hashCode
│
├── merge/                        ← compaction, hint files, background scheduling
│   ├── MergePolicy.java          ← Strategy interface
│   ├── SizeTieredMergePolicy.java← default Strategy impl: trigger on dead-byte ratio
│   ├── Merger.java               ← executes compaction, writes merged files + hints
│   └── HintFile.java             ← writes and reads the .hint index-snapshot format
│
├── recovery/                     ← startup KeyDir rebuild
│   └── StartupLoader.java        ← hint-first replay, data-file fallback
│
└── config/
    └── BitcaskConfig.java        ← all tunables, constructed via inner Builder
```

---

## 3. Implementation Order

Build bottom-up. Each step produces something you can test before moving forward.

```
Phase 1 ──► BitcaskException ──► LogRecord ──► DataFile ──► DataFileFactory

Phase 2 ──► ByteArrayKey ──► KeyEntry ──► KeyDir

Phase 3 ──► BitcaskConfig
        ──► Bitcask (interface) ──► BitcaskStore (wire phases 1 + 2)

Phase 4 ──► HintFile ──► MergePolicy ──► SizeTieredMergePolicy ──► Merger

Phase 5 ──► StartupLoader

Phase 6 ──► ReadWriteLock in BitcaskStore + ScheduledExecutorService for merge

Phase 7 ──► DataFileSet (manage rotation cleanly)

Phase 8 ──► StoreStats
```

---

## 4. Phase 1 — Core Storage

---

### `BitcaskException.java`

**Responsibility:** Single unchecked exception for all errors that originate
inside the store. Provides static factory methods for all validation and
runtime error cases so that error messages are standardised across the codebase.

**Why unchecked:** Wraps `IOException` in most cases. Callers cannot
meaningfully recover from disk failures or corrupt data — they can only log
and stop. Checked exceptions would clutter every call site with `try/catch`
that does nothing useful.

**Why static factory methods instead of `new BitcaskException("...")`:**
- Messages are standardised — no variation or typos across callers
- Context is baked in automatically (`keyTooLarge(70000, 65535)` includes both values)
- Self-documenting at the call site — `BitcaskException.nullOrEmptyKey()` is
  instantly clear without reading a string literal

**Static factories — all return `BitcaskException`:**

```
nullOrEmptyKey()
→ "Key must not be null or empty."
→ thrown in LogRecord constructor when key == null || key.length == 0

keyTooLarge(int actualSize, int maximumSize)
→ "Key too large: N bytes. Maximum allowed size is 65535 bytes (unsigned short limit)."
→ thrown in LogRecord constructor when key.length > 65535

nullValue()
→ "Value must not be null. To delete a key, use BitcaskStore.delete() instead."
→ thrown in LogRecord constructor when value == null

crcMismatch(int position)
→ "CRC mismatch at buffer position N — corrupt or partial record."
→ thrown in LogRecord.decode() when stored CRC != computed CRC

readOnlyFile(Path path)
→ "Cannot append to read-only data file: <path>"
→ thrown in DataFile.append() when readOnly == true

general(String message)
→ passes message through directly
→ used for IOException wrapping and other cases

general(String message, Throwable cause)
→ wraps an underlying IOException with context
→ used in DataFile, DataFileFactory, StartupLoader
```

---

### `LogRecord.java`

**Responsibility:** Represent a single entry in the Bitcask log. Own the
binary on-disk format — encode a record to bytes, decode bytes back to a
record, validate integrity via CRC.

**Why a single flat class and not a hierarchy:**
The original Bitcask paper has no type byte on disk. PUT and DELETE are not
structurally different records — a DELETE is simply a PUT with `value_size = 0`
and no value bytes. A single class with `isTombstone()` checking `value.length == 0`
is the direct implementation of the paper's design. The class hierarchy
(`PutRecord`, `DeleteRecord`) was a design we considered and rejected.

**Binary layout on disk (big-endian, matches paper with 8-byte timestamp):**
```
┌─────────┬───────────┬──────────┬────────────┬─────────┬─────────┐
│   crc   │ timestamp │ key_size │ value_size │   key   │  value  │
│ 4 bytes │  8 bytes  │  2 bytes │  4 bytes   │ N bytes │ M bytes │
└─────────┴───────────┴──────────┴────────────┴─────────┴─────────┘
  HEADER_SIZE = 18 bytes
  CRC_SIZE    = 4 bytes
  CRC covers all bytes from offset 4 onward (everything except itself)
  value_size = 0 → tombstone (DELETE). No value bytes follow.
  value_size > 0 → live record (PUT).
```

**Deviation from paper:** Paper uses 4-byte timestamp (Unix seconds, overflows
2038). We use 8-byte timestamp (Unix epoch millis) for post-2038 correctness
and millisecond precision for merge conflict resolution.

**Fields — all `private final`:**

```
public static final int HEADER_SIZE = 18   ← used by DataFile.readAt()
public static final int CRC_SIZE    = 4    ← used by DataFile.readAt()

private final long    timestamp   ← Unix epoch millis
private final byte[]  key         ← raw key bytes
private final byte[]  value       ← raw value bytes; empty array = tombstone
```

**Why `crc`, `keySize`, `valueSize` are NOT fields:**
- `crc` is computed from the other fields at encode time and validated at
  decode time. It has no meaning in memory — it is a disk-level integrity field.
- `keySize` is always `key.length` — storing it separately creates a risk of
  inconsistency between the field and the actual array length.
- `valueSize` is always `value.length` — same reason.

**Constructor:**

```
LogRecord(long timestamp, byte[] key, byte[] value)
```
- Validates `key == null || key.length == 0` → `BitcaskException.nullOrEmptyKey()`
- Validates `key.length > 65535` → `BitcaskException.keyTooLarge(key.length, 65535)`
- Validates `value == null` → `BitcaskException.nullValue()`
- Stores all three as `final` fields.
- Validation is here and nowhere else. By the time any method is called on
  the object, the fields are guaranteed valid.

**Methods:**

```
getTimestamp()
```
- Returns `timestamp`. Pure accessor.
- Used by `Merger` for conflict resolution — when the same key appears in
  two candidate files, the record with the larger timestamp is live.

```
getKey()
```
- Returns `key`. Pure accessor.
- Used by `BitcaskStore` to update the `KeyDir` after an append.

```
getValue()
```
- Returns `value`. Pure accessor.
- Returns empty `byte[]` for tombstone records.
- Used by `BitcaskStore.get()` to return the value to the caller.

```
isTombstone()
```
- Returns `value.length == 0`.
- The only way to distinguish a DELETE from a PUT at runtime.
- Called in `StartupLoader` (remove from KeyDir vs put into KeyDir),
  in `Merger` (skip dead records), and in `BitcaskStore.get()` as a
  defensive check.
- **Pain point:** callers must remember to check this everywhere. The
  compiler does not enforce it. This is intentional — it is the lesson
  that leads to sealed types.

```
encode()  — no parameters
```
- Allocates `ByteBuffer` of `totalSize()` bytes.
- Positions buffer at offset `CRC_SIZE` (4) — leaving the CRC slot empty.
- Writes in order: `timestamp` (8 bytes, `putLong`), `key.length` cast to
  `short` (2 bytes, `putShort`), `value.length` (4 bytes, `putInt`),
  `key` bytes, `value` bytes.
- Computes `CRC32` over `buffer.array()` from offset `CRC_SIZE` to end.
- Writes CRC at position 0 using absolute `putInt(0, (int) checksum.getValue())`.
- Returns `buffer.array()`.
- Guard: `key.length > 65535` → `BitcaskException.keyTooLarge()`. Although
  the constructor already validates this, `encode()` checks before the
  `(short)` cast to make the contract explicit.

```
decode(ByteBuffer buffer)  — public static
```
- Records `startPosition = buffer.position()`.
- Reads `storedCrc` (4 bytes, `getInt()`).
- Reads `timestamp` (8 bytes, `getLong()`).
- Reads `keySize` as unsigned: `Short.toUnsignedInt(buffer.getShort())`.
  Why unsigned: a key of length 40000 would be negative as a signed short.
  `Short.toUnsignedInt()` gives the correct 0–65535 range.
- Reads `valueSize` (4 bytes, `getInt()`).
- Allocates `byte[] key = new byte[keySize]`, reads into it.
- Allocates `byte[] value = new byte[valueSize]`, reads into it.
  For tombstones: `valueSize = 0`, allocates empty array, reads nothing.
- Computes `CRC32` over `buffer.array()` from `startPosition + CRC_SIZE`
  for `totalRecordSize - CRC_SIZE` bytes.
  Where `totalRecordSize = HEADER_SIZE + keySize + valueSize`.
- Compares: `storedCrc != (int) checksum.getValue()`.
  The `(int)` cast is required — `CRC32.getValue()` returns `long`
  (unsigned 32-bit), `storedCrc` is `int` (signed 32-bit). Both must be
  cast to `int` for the bit-pattern comparison to be correct.
- If mismatch → `BitcaskException.crcMismatch(startPosition)`.
- Returns `new LogRecord(timestamp, key, value)`.

```
totalSize()  — private
```
- Returns `HEADER_SIZE + key.length + value.length`.
- Used only by `encode()`. Private because no outside class should need it —
  `DataFile.readAt()` computes size from header fields after reading the
  header, not from the object.

```
toString()
```
- Format: `LogRecord{type=PUT, ts=1714000000000, key="user:123", keyLen=8, valueLen=42}`
- `type` is derived: `isTombstone() ? "DELETE" : "PUT"`.
- `key` displayed as UTF-8 string if all bytes are printable ASCII;
  otherwise as `hex:` prefixed hex string.
- Value bytes are never printed — may be large or sensitive.
- Closing `}` is always present.

---

### `DataFile.java`

**Responsibility:** Manage a single `.data` file on disk. Append records,
read a record at a known byte offset, force sync to disk, close cleanly.
One instance = one file.

**Why one class per file and not one class managing all files:**
`DataFile` models exactly one file channel. The question of "which files
exist and which is active" belongs to `DataFileSet`. Separating them means
`DataFile` is testable in complete isolation.

**Thread safety contract:**
- `append()` — NOT thread-safe. `BitcaskStore` serialises all writes via
  write lock. Only one thread ever calls `append()` at a time.
- `readAt()` — IS thread-safe. Uses `FileChannel.read(buffer, position)`
  with an explicit position argument — does not move a shared channel
  position pointer. Multiple threads may call `readAt()` concurrently.

**Fields — all `private final`:**

```
fileId       long         ← timestamp-based ID, matches filename
path         Path         ← full filesystem path, used in exception messages
channel      FileChannel  ← open NIO channel, all I/O goes through this
writeOffset  AtomicLong   ← current end-of-file position, lock-free
readOnly     boolean      ← true for immutable files after rotation
```

**Why `AtomicLong` for `writeOffset` and not a plain `long`:**
`writeOffset` is read by `append()` to determine the start offset to return,
and may be read by `size()` called from a different thread (e.g. the
background merge checking file sizes). `AtomicLong` provides a volatile
read/write with happens-before guarantee — safe without a lock for this
single incrementing counter.

**Constructor — package-private:**
All fields taken as parameters. Callers must go through `DataFileFactory`.
This enforces that file creation and naming conventions are always applied.

**Methods:**

```
append(LogRecord record)
```
- Guard: if `readOnly == true` → `BitcaskException.readOnlyFile(path)`.
- Calls `record.encode()` to get the full byte array.
- Captures `offset = writeOffset.get()` — this is the start offset of
  this record, returned to the caller to store in `KeyEntry`.
- Writes encoded bytes to channel at `offset` using
  `FileChannel.write(ByteBuffer, position)` with explicit position.
  This is non-sequential but safe — the write lock in `BitcaskStore`
  ensures only one thread ever appends at a time.
- Advances `writeOffset` by `encoded.length` using `AtomicLong.addAndGet()`.
- Returns `offset` (the start position, captured before writing).
- Wraps `IOException` in `BitcaskException.general(message, cause)`.

```
readAt(long offset)
```
- Reads `HEADER_SIZE` (18) bytes from channel at `offset` into a header buffer.
- Does NOT use or advance a shared channel position — thread-safe by design.
- Peeks into header buffer to extract `keySize` and `valueSize`:
  - `keySize`   at header offset 12: `Short.toUnsignedInt(header.getShort(12))`
  - `valueSize` at header offset 14: `header.getInt(14)`
  - Why these offsets: crc(4) + timestamp(8) = 12 bytes before key_size.
- Computes `totalSize = HEADER_SIZE + keySize + valueSize`.
- Allocates a fresh `ByteBuffer` of `totalSize` bytes.
- Reads the full record from channel at `offset` into this buffer.
  Each call allocates its own buffer — intentional, avoids shared state
  between concurrent reads.
- Calls `LogRecord.decode(buffer)` — CRC validation happens inside decode.
- Returns the decoded `LogRecord`.
- Wraps `IOException` and `BufferUnderflowException` in `BitcaskException`.

```
sync()
```
- Calls `channel.force(true)`.
- `true` means flush both file data AND metadata (size, timestamps) to the
  physical storage device.
- `force(false)` flushes data only — metadata may be stale after a crash,
  making recovery harder. Always use `force(true)`.
- Called by `BitcaskStore` after `append()` when `config.isSyncOnWrite()`
  is true.
- Called unconditionally inside `close()`.

```
getFileId()
```
- Returns `fileId`. Used by `BitcaskStore` when constructing a `KeyEntry`
  after an `append()` — the KeyEntry must record which file holds the value.

```
getPath()
```
- Returns `path`. Used in exception messages and by `DataFileFactory`
  when deriving the companion `.hint` file path.

```
size()
```
- Returns `channel.size()` — current number of bytes in the file.
- Called by `BitcaskStore` after every `append()` to check whether the
  active file has exceeded `config.getMaxFileSize()`.
- Also used by `StoreStats` to compute total disk usage.

```
isReadOnly()
```
- Returns `readOnly`. Used by `DataFileSet` to distinguish the single
  active file from all immutable files.

```
close()  [implements Closeable]
```
- If not read-only: calls `channel.force(true)` as best-effort final sync.
  Swallows `IOException` here — the channel is being closed regardless,
  and throwing from `close()` would mask the original exception.
- Calls `channel.close()` unconditionally — releases the OS file descriptor.
- After close, any further `FileChannel` operation throws
  `ClosedChannelException`. No additional guard needed.

---

### `DataFileFactory.java`

**Responsibility:** Create new writable data files and open existing ones.
Centralise all file naming conventions and directory listing logic.

**Why a factory:**
File creation involves naming rules, `StandardOpenOption` choices, and path
construction that are unrelated to per-record operations. If constructors
were public, every caller would need to know the naming scheme. One factory,
one place to change naming rules.

**File naming convention:**
`System.currentTimeMillis()` zero-padded to 20 digits + `.data`.
Example: `00000001714000000000.data`.
20 digits ensures lexicographic sort = chronological order for any `long` timestamp.
Hint files use the same fileId with `.hint` extension.

**Methods:**

```
createActive(Path directory)  — public static
```
- Generates `fileId = System.currentTimeMillis()`.
- Constructs filename: `String.format("%020d.data", fileId)`.
- Opens `FileChannel` with `CREATE_NEW, WRITE, READ`.
  `CREATE_NEW` fails if file exists — safety net against duplicate IDs.
- Returns new `DataFile` with `readOnly = false`.

```
openReadOnly(Path path)  — public static
```
- Extracts `fileId` from filename via `fileIdFromPath()`.
- Opens `FileChannel` with `READ` only.
- Returns new `DataFile` with `readOnly = true`.
- Called by `StartupLoader` for all files except the newest, and by
  `Merger` for candidate files.

```
listDataFiles(Path directory)  — public static
```
- Lists all files ending in `.data` using `Files.list()`.
- Sorts by fileId ascending (oldest first) — parsed via `fileIdFromPath()`.
- Returns `List<Path>`.
- The newest file in the sorted list is the active file. All others are immutable.

```
fileIdFromPath(Path path)  — public static
```
- Strips `.data` extension, parses remainder as `long`.
- Throws `BitcaskException` if parsing fails — indicates a foreign file
  in the directory.

---

## 5. Phase 2 — In-Memory Index

---

### `ByteArrayKey.java`

**Responsibility:** Wrap `byte[]` with value-based `equals()` and `hashCode()`
so it can be used correctly as a `HashMap` key.

**Why this class must exist:**
Java's `byte[]` inherits `Object.equals()` (reference equality) and
`Object.hashCode()` (identity hash). Two arrays with identical bytes are
different objects → different keys in `HashMap`. `get()` after `put()` with
a different array instance always returns null. Silent, hard-to-debug bug.

`Arrays.equals()` and `Arrays.hashCode()` provide content-based semantics.
This wrapper applies them so `HashMap` sees byte arrays correctly.

**Fields:**
- `bytes` — `byte[]`, `final`. Never exposed by reference — returned via `getBytes()`.

**Methods:**

```
getBytes()
```
- Returns the underlying `byte[]`.
- Used when raw key bytes are needed — e.g. iterating `KeyDir.snapshot()`
  to return keys from `Bitcask.listKeys()`.

```
equals(Object other)
```
- Returns true if `other` is a `ByteArrayKey` and
  `Arrays.equals(this.bytes, other.bytes)`.
- This is the fix. Without it, HashMap lookup is broken.

```
hashCode()
```
- Returns `Arrays.hashCode(bytes)`.
- Must be consistent with `equals()` — Java contract: equal objects must
  have equal hash codes.

```
toString()
```
- Attempts UTF-8 decode for display. Used in test failure messages.

---

### `KeyEntry.java`

**Responsibility:** Immutable pointer from a key to its exact on-disk location.
Maps 1:1 to the paper's keydir entry structure.

**Fields — all `private final`:**

```
fileId     long   ← which .data file contains this value
offset     long   ← byte offset within that file where the record starts
valueSize  int    ← size of value in bytes (for future direct-value reads)
timestamp  long   ← write timestamp; used in merge conflict resolution
```

**Why all fields are final:**
A `KeyEntry` is never mutated. When a key is overwritten, the old entry is
discarded and a new one is inserted. Mutability here would imply a single
entry evolves over time — that is not the Bitcask model.

**Constructor:** Takes all four fields. No validation.

**Methods:** Getters for all four fields.

```
toString()
```
- Returns all four fields in a readable format.
- Written by hand — you will feel how repetitive this is compared to a record.
  That is intentional.

---

### `KeyDir.java`

**Responsibility:** Thread-safe in-memory hash index mapping every live key
to its `KeyEntry`. The structure that makes Bitcask reads O(1).

**Why not expose the HashMap directly:**
The raw map uses `ByteArrayKey` — an implementation detail callers should not
need to construct themselves. `KeyDir` accepts raw `byte[]` and wraps internally.

**Fields:**
- `map` — `HashMap<ByteArrayKey, KeyEntry>`. Not `ConcurrentHashMap` — the
  external `ReentrantReadWriteLock` provides control over compound operations.
- `lock` — `ReentrantReadWriteLock`.

**Methods:**

```
put(byte[] key, KeyEntry entry)
```
- Acquires write lock.
- Wraps `key` in `ByteArrayKey`, calls `map.put()`.
- Releases write lock in `finally`.
- Called by `BitcaskStore.put()` after successful disk append and by
  `StartupLoader` during recovery.

```
get(byte[] key)
```
- Acquires read lock.
- Returns `KeyEntry` or `null` if absent.
- Returns `null` not `Optional` — the hot read path allocates nothing.
  **Pain point:** callers null-check everywhere. Intentional lesson.

```
remove(byte[] key)
```
- Acquires write lock.
- Called by `BitcaskStore.delete()` after writing tombstone to disk.
- Also called during recovery when a `DeleteRecord` (tombstone) is encountered.

```
size()
```
- Acquires read lock. Returns `map.size()`.

```
snapshot()
```
- Acquires read lock.
- Copies entire map into a new `HashMap`, wraps in `Collections.unmodifiableMap()`.
- Returns the unmodifiable copy — safe to iterate outside the lock.
- Used by `Merger` and `Bitcask.listKeys()`.

---

## 6. Phase 3 — Public API

---

### `BitcaskConfig.java`

**Responsibility:** All store tunables in one immutable object. Constructed
via inner `Builder`.

**Why Builder:**
Four or more fields make a plain constructor unreadable — `new BitcaskConfig(1073741824L, false, 0.5, true)`
tells you nothing. Builder gives every field a name and sensible defaults.

**Fields — all `private final`:**

```
maxFileSize               long      default: 1 GB
                          ← active file rotated when DataFile.size() exceeds this

syncOnWrite               boolean   default: false
                          ← if true, DataFile.sync() called after every append

mergeDeadRatioThreshold   double    default: 0.5
                          ← SizeTieredMergePolicy triggers when deadByteRatio >= this

backgroundMergeEnabled    boolean   default: true
                          ← if true, ScheduledExecutorService runs periodic merge check
```

**Builder methods:** `maxFileSize()`, `syncOnWrite()`, `mergeDeadRatioThreshold()`,
`backgroundMergeEnabled()` — all return `this` for chaining.

```
build()
```
- Validates `maxFileSize > 0`, `mergeDeadRatioThreshold` in [0.0, 1.0].
- Returns immutable `BitcaskConfig`.

---

### `Bitcask.java` (interface)

**Responsibility:** The public contract. The only type users of the library
import and depend on. All other classes are implementation details.

**Extends `AutoCloseable`:** Forces use of try-with-resources. Without it,
callers would forget `close()` — leaking file descriptors and the lock file.

**Methods:**

```
put(byte[] key, byte[] value)
```
- Appends a `LogRecord` to the active data file.
- Updates `KeyDir` with new `KeyEntry` pointing to the written offset.
- Constraints: key non-null, non-empty. Value non-null.

```
get(byte[] key)
```
- Looks up `KeyDir`. If absent → returns null.
- Uses `KeyEntry` to call `DataFile.readAt(offset)` on the correct file.
- Returns `record.getValue()`. At most one disk seek.

```
delete(byte[] key)
```
- Appends a tombstone `LogRecord` (value = empty bytes) to active file.
- Immediately calls `KeyDir.remove(key)`.
- Tombstone cleaned up from disk during next `merge()`.

```
merge()
```
- Compacts all immutable data files. Rewrites live keys only.
- Writes hint files alongside merged files.
- Updates KeyDir to point to new locations. Deletes old files.
- Safe during concurrent reads and writes.

```
sync()
```
- Explicit fsync on active file. Paper API equivalent.
- Useful when `syncOnWrite = false` and caller wants to checkpoint durability.

```
listKeys()
```
- Returns snapshot of all live key byte arrays from `KeyDir.snapshot()`.

```
stats()
```
- Returns `StoreStats` snapshot. Used by `MergePolicy` and for monitoring.

```
close()
```
- Flushes and syncs active file. Shuts down background executor.
- Releases lock file. After close, all method calls throw `BitcaskException`.

---

### `BitcaskStore.java`

**Responsibility:** Wire all subsystems together. Implement `Bitcask`.
Coordinate writes, reads, rotation, locking, and lifecycle.

**Fields:**

```
config          BitcaskConfig
keyDir          KeyDir
activeFile      DataFile
immutableFiles  Map<Long, DataFile>      fileId → read-only DataFile
writeLock       ReentrantReadWriteLock
mergeExecutor   ScheduledExecutorService  null if backgroundMergeEnabled=false
directory       Path
```

**Static factory:**

```
open(Path directory, BitcaskConfig config)
```
- Creates directory if absent.
- Acquires lock file (`bitcask.lock`) via `FileChannel.tryLock()`.
  Throws if another process holds it.
- Runs `StartupLoader.load(directory)` → populated `KeyDir`.
- Opens all existing data files: newest as active, rest as read-only immutable.
  Creates new active file if none exist.
- Starts `ScheduledExecutorService` if `backgroundMergeEnabled`.
- Returns ready store.

**Method descriptions:**

```
put(byte[] key, byte[] value)
```
- Acquires write lock.
- Constructs `LogRecord(System.currentTimeMillis(), key, value)`.
- Calls `activeFile.append(record)` → gets start offset.
- Constructs `KeyEntry(activeFile.getFileId(), offset, value.length, timestamp)`.
- Calls `keyDir.put(key, entry)`.
- If `syncOnWrite`: calls `activeFile.sync()`.
- Checks `activeFile.size() > config.getMaxFileSize()` → calls `rotateActiveFile()`.
- Releases write lock.

```
get(byte[] key)
```
- Acquires read lock on KeyDir.
- Calls `keyDir.get(key)`. If null → release lock, return null.
- Finds correct `DataFile` from `activeFile` or `immutableFiles` by fileId.
- Releases read lock (FileChannel positional read is thread-safe without lock).
- Calls `dataFile.readAt(entry.getOffset())`.
- Returns `record.getValue()`.

```
delete(byte[] key)
```
- Acquires write lock.
- Constructs tombstone: `new LogRecord(System.currentTimeMillis(), key, new byte[0])`.
- Calls `activeFile.append(record)`.
- Calls `keyDir.remove(key)`.
- If `syncOnWrite`: calls `activeFile.sync()`.
- Checks size, rotates if needed.
- Releases write lock.

```
rotateActiveFile()  — private
```
- Called when `activeFile.size() > config.getMaxFileSize()`.
- Closes current `activeFile` (triggers internal sync in `DataFile.close()`).
- Moves it to `immutableFiles` map.
- Creates new active file via `DataFileFactory.createActive(directory)`.
- Entire operation inside the write lock already held by caller.

```
merge()
```
- Snapshots `immutableFiles` and `KeyDir` under brief write lock.
- Releases lock — merge does not block reads or writes during execution.
- Passes to `Merger.merge()`.
- After `Merger` returns: acquires write lock to swap new files into
  `immutableFiles` and update KeyDir entries.
- Deletes old data files from disk.

```
close()
```
- Shuts down `mergeExecutor` with `awaitTermination()` timeout.
- Calls `activeFile.close()`.
- Closes all `immutableFiles`.
- Releases lock file.

---

## 7. Phase 4 — Compaction & Merge

---

### `HintFile.java`

**Responsibility:** Write and read `.hint` files — stripped-down index files
written alongside each merged data file for fast startup KeyDir reconstruction.

**Why hint files exist:**
Without them, startup must replay every data file in full — reading all value
bytes — just to rebuild the index. Hint files contain only the fields needed
for `KeyEntry` construction: timestamp, key_size, value_size, offset, key.
No value bytes. Startup reads hint files for all merged segments — much faster.

**Hint record format (no CRC — hint corruption is non-fatal):**
```
┌───────────┬──────────┬────────────┬──────────┬─────────┐
│ timestamp │ key_size │ value_size │  offset  │   key   │
│  8 bytes  │  2 bytes │  4 bytes   │  8 bytes │ N bytes │
└───────────┴──────────┴────────────┴──────────┴─────────┘
  No type byte. Only live keys written — tombstones excluded.
  If hint file is corrupt, StartupLoader falls back to data file replay.
```

**Methods:**

```
write(Path hintPath, Map<ByteArrayKey, KeyEntry> entries)
```
- Opens new file at `hintPath`.
- For each entry: encodes and appends a hint record.
- Does NOT write tombstone entries — hint files represent live state only.

```
readInto(Path hintPath, KeyDir keyDir, long fileId)
```
- Reads hint records sequentially.
- For each: constructs `KeyEntry` using `fileId` (implicit from which
  data file this hint belongs to) and calls `keyDir.put()`.
- On truncated/corrupt read: stops and returns what was loaded.
  Non-fatal — `StartupLoader` falls back to data file.

---

### `MergePolicy.java`

**Responsibility:** Contract for deciding whether a merge should run.
**Pattern:** Strategy

```
shouldMerge(StoreStats stats)
```
- Returns true if store should merge now.
- Called by `BitcaskStore` when `merge()` is invoked and by the background
  executor on its schedule.

---

### `SizeTieredMergePolicy.java`

**Responsibility:** Default `MergePolicy`. Triggers when dead-byte ratio
exceeds a configured threshold.

**Fields:** `deadRatioThreshold` — `double`, `final`. Validated in [0.0, 1.0].

```
shouldMerge(StoreStats stats)
```
- Returns `stats.getDeadByteRatio() >= deadRatioThreshold`.

---

### `Merger.java`

**Responsibility:** Execute a compaction run. Read live records from
immutable data files, write to new compact files, write hint files,
return results for atomic KeyDir update.

**Why merge only touches immutable files:**
Active file is still being written. Including it would require pausing writes.

**Why Merger receives a KeyDir snapshot:**
Merge takes time. New writes may arrive during merge. A snapshot at merge-start
gives a consistent view. `BitcaskStore` does a final atomic KeyDir update
after merge completes.

```
merge(List<DataFile> candidates, Map<ByteArrayKey, KeyEntry> snapshot, Path directory)
```
- Iterates all records in each candidate file sequentially.
- A record is live if: its key exists in the snapshot AND the snapshot
  entry points to THIS file and THIS offset.
- Live records appended to output files. Dead records skipped.
- Writes `HintFile` alongside each output file.
- Returns `MergeResult` — new files + updated KeyEntry map.

---

## 8. Phase 5 — Crash Recovery

---

### `StartupLoader.java`

**Responsibility:** Rebuild `KeyDir` on store open. Handle intact files,
partially-written files (crash during write), and missing hint files.

**Recovery strategy:**
1. `DataFileFactory.listDataFiles(directory)` — sorted oldest-first.
2. For each file except the newest:
   - Check for companion `.hint` file.
   - If hint exists → `HintFile.readInto()` (fast path).
   - If no hint → replay raw data file record by record (slow path).
3. Newest file (was active before crash/shutdown):
   - Always replay from raw — never has a hint file.
   - Partial records (crash tail) appear here.
4. During raw replay:
   - `LogRecord.decode()` in a loop from offset 0.
   - CRC mismatch → stop reading this file (expected crash tail behaviour).
   - `isTombstone()` true → `keyDir.remove(key)`.
   - `isTombstone()` false → `keyDir.put(key, entry)`.
5. Return populated `KeyDir`.

```
load(Path directory)
```
- Orchestrates the above strategy.
- Returns populated `KeyDir` to `BitcaskStore.open()`.

---

## 9. Phase 6 — Concurrency

No new classes. This phase hardens `BitcaskStore` and `KeyDir`.

| Operation | Lock | Reason |
|---|---|---|
| `get()` KeyDir lookup | Read lock | Concurrent reads safe |
| `get()` DataFile.readAt() | No lock | Positional FileChannel read is thread-safe |
| `put()` disk append + KeyDir update | Write lock | Atomic: disk write + index update |
| `delete()` disk append + KeyDir remove | Write lock | Same reason |
| `rotateActiveFile()` | Write lock (already held) | No reader sees file mid-swap |
| `merge()` file iteration | No lock (snapshot) | Reads immutable files safely |
| `merge()` KeyDir update | Write lock (brief) | Atomic file reference swap |
| Background merge check | No lock | Reads StoreStats only |

**Background merge thread:**
`ScheduledExecutorService`, single daemon thread, fixed-rate (e.g. 60 seconds).
On each tick: `stats()` → `mergePolicy.shouldMerge()` → `merge()` if true.
Shutdown in `close()` via `shutdown()` + `awaitTermination()`.

---

## 10. Phase 7 — Config & Lifecycle

### `DataFileSet.java`

**Responsibility:** Manage the collection of all open `DataFile` instances.
Exactly one active (writable) + zero or more immutable (read-only).

**Why extract from `BitcaskStore`:**
In Phase 3, `BitcaskStore` manages files directly. By Phase 7 this is
unwieldy — rotation logic, file lookup, and close-all are mixed into an
already large class. `DataFileSet` extracts that concern cleanly.

**Methods:**

```
getActive()         ← returns current active DataFile
getById(long fileId)← finds file by ID across active + immutable
rotate()            ← closes active, moves to immutable, creates new active
allImmutable()      ← unmodifiable view of immutable files for Merger
replaceImmutable()  ← swaps old merged files for new compact files
closeAll()          ← closes all files, called by BitcaskStore.close()
```

---

## 11. Phase 8 — Observability

### `StoreStats.java`

**Responsibility:** Point-in-time snapshot of store health. Consumed by
`MergePolicy.shouldMerge()` and returned by `Bitcask.stats()`.

**Why a snapshot:**
All fields computed at the same instant under the read lock — consistent view.
Individual live getters on the store could change between calls.

**Fields — all `private final`:**

```
liveKeyCount    int     ← KeyDir.size()
totalFileCount  int     ← active + all immutable files
deadKeyCount    long    ← estimated: total records on disk minus liveKeyCount
deadByteRatio   double  ← fraction of disk bytes occupied by dead records (0.0–1.0)
totalDiskBytes  long    ← sum of DataFile.size() across all files
```

**Constructor:** Takes all five fields.

**Methods:** Getters for all five fields.

```
toString()
```
- One-line human-readable summary. Written by hand — another instance of
  the boilerplate pain that teaches you why records exist.

---

## 12. Design Patterns Map

| Pattern | Where | Why it appears naturally |
|---|---|---|
| **Facade** | `Bitcask` interface + `BitcaskStore` | Six subsystems hidden behind five methods |
| **Factory** | `DataFileFactory` | Naming rules and open options centralised in one place |
| **Strategy** | `MergePolicy` + `SizeTieredMergePolicy` | Merge trigger logic varies by deployment |
| **Builder** | `BitcaskConfig.Builder` | Four fields make a plain constructor unreadable |
| **Value Object** | `LogRecord`, `KeyEntry`, `StoreStats`, `BitcaskConfig` | Immutable, data-carrying, final fields |
| **Iterator** (implicit) | Record scan in `Merger`, `StartupLoader` | Walk a file record by record without exposing internals |

---

## 13. Java Concepts Map

| Concept | Where used | What you learn |
|---|---|---|
| `ByteBuffer` | `LogRecord.encode/decode`, `DataFile` | Position, limit, capacity, flip. Absolute vs relative puts/gets. |
| `FileChannel` | `DataFile` | Positional reads (thread-safe). `force(true)` vs `force(false)`. |
| `CRC32` | `LogRecord.encode/decode` | Data integrity without crypto. `getValue()` returns unsigned long. Cast to int for comparison. |
| `Short.toUnsignedInt()` | `LogRecord.decode()` | Why signed/unsigned matters when reading binary formats. |
| `AtomicLong` | `DataFile.writeOffset` | Lock-free counter. Happens-before guarantee. |
| `HashMap` + `ByteArrayKey` | `KeyDir` | Why `byte[]` breaks HashMap. Content vs reference equality. |
| `ReentrantReadWriteLock` | `KeyDir`, `BitcaskStore` | Read-heavy concurrency. Multiple readers, single writer. |
| `ScheduledExecutorService` | `BitcaskStore` background merge | Daemon threads. `shutdown()` + `awaitTermination()`. |
| `FileChannel.tryLock()` | `BitcaskStore.open()` | OS-level file locks. Single-writer enforcement across processes. |
| `Path` / `Files` NIO2 | `DataFileFactory`, `StartupLoader` | Modern file I/O. Never use `java.io.File`. |
| `AutoCloseable` | `DataFile`, `BitcaskStore` | Resource leak prevention. try-with-resources. |
| Builder pattern (manual) | `BitcaskConfig.Builder` | Telescoping constructor problem. Named parameters in Java. |

---

## 14. Known Pain Points

| Pain you will feel | What it teaches |
|---|---|
| Writing `equals()`, `hashCode()`, `toString()` manually in `KeyEntry`, `StoreStats` | Why `record` exists |
| Checking `isTombstone()` in every path that processes a record | Why sealed types + exhaustive switch exist |
| Null-checking `KeyDir.get()` return value everywhere | Why `Optional<T>` exists |
| Getters on every field of every class | Why records eliminate boilerplate |
| `(int) checksum.getValue()` cast every time you compare CRC | Why type systems should make this impossible |

When you have felt all of these, the refactor to modern Java is justified
by lived experience — not by reading about it.

---

## 15. Javadoc Standards

```java
/**
 * One-line summary sentence ending with a period.
 *
 * <p>Deeper explanation. Use {@code inline code} for identifiers.
 * Use {@link ClassName#method()} for cross-references.
 *
 * @param paramName  description + constraints (null? empty? range?)
 * @return           what is returned; when it can be null
 * @throws BitcaskException  conditions under which this is thrown
 * @see RelatedClass
 */
```

Rules:
- Every `public` class has a class-level Javadoc
- Every `public` and `protected` method has a Javadoc
- `@param` includes constraints — "non-null", "must be positive", "may be empty"
- `@return` always present on non-void methods
- `@throws` for every thrown exception including unchecked
- `<pre>{@code ... }</pre>` for multi-line code examples

---

## 16. Testing Strategy

| Phase | Type | What to verify | Tool |
|---|---|---|---|
| 1 | Unit | `LogRecord` encode → decode roundtrip: all fields preserved | JUnit 5 |
| 1 | Unit | `LogRecord` tombstone: encode → decode, `isTombstone()` true | JUnit 5 |
| 1 | Unit | `LogRecord.decode()` throws on CRC corruption | JUnit 5 |
| 1 | Unit | `LogRecord.decode()` throws on unknown/zeroed type byte area | JUnit 5 |
| 1 | Unit | Constructor rejects null key, empty key, key > 65535, null value | JUnit 5 |
| 1 | Unit | `DataFile.append()` returns offset 0 for first record | JUnit 5 + `@TempDir` |
| 1 | Unit | `DataFile.append()` second record starts at offset = first record size | JUnit 5 + `@TempDir` |
| 1 | Unit | `DataFile.readAt(offset)` returns exact record appended at that offset | JUnit 5 + `@TempDir` |
| 1 | Unit | `DataFile.append()` on read-only file throws `BitcaskException` | JUnit 5 + `@TempDir` |
| 1 | Unit | `DataFile.size()` matches total bytes appended | JUnit 5 + `@TempDir` |
| 2 | Unit | `ByteArrayKey.equals()`: same bytes, different array instances → equal | JUnit 5 |
| 2 | Unit | `ByteArrayKey.hashCode()`: same bytes → same hash | JUnit 5 |
| 2 | Unit | `KeyDir.get()` returns null for unknown key | JUnit 5 |
| 2 | Unit | `KeyDir.get()` returns correct entry after `put()` | JUnit 5 |
| 2 | Unit | `KeyDir.get()` returns null after `remove()` | JUnit 5 |
| 2 | Unit | `KeyDir.put()` overwrites previous entry for same key | JUnit 5 |
| 3 | Integration | `put()` → `get()` returns correct value | JUnit 5 + `@TempDir` |
| 3 | Integration | `delete()` → `get()` returns null | JUnit 5 + `@TempDir` |
| 3 | Integration | `put()` twice → `get()` returns latest value | JUnit 5 + `@TempDir` |
| 5 | Integration | Close + reopen: all keys readable | JUnit 5 + `@TempDir` |
| 5 | Integration | Simulated crash (no close): reopen rebuilds correct KeyDir | JUnit 5 + `@TempDir` |
| 4 | Integration | `merge()` after overwrites: `get()` returns latest value | JUnit 5 + `@TempDir` |
| 4 | Integration | `merge()` after deletes: deleted keys not readable after reopen | JUnit 5 + `@TempDir` |
| 4 | Integration | Total disk bytes after merge < before merge | JUnit 5 + `@TempDir` |
| 8 | Benchmark | Write throughput: ops/sec for sequential puts | JMH |
| 8 | Benchmark | Read latency: p50, p99 for random gets | JMH |

**Rule:** Never use hardcoded paths. Always `@TempDir Path dir`.

---

## 17. What This Unlocks Next

```
Bitcask — plain class implementation
    │
    ├──► Refactor to Java 21 idioms
    │       sealed interface LogRecord permits PutRecord, DeleteRecord
    │       record KeyEntry(...), record StoreStats(...)
    │       pattern matching switch in StartupLoader, Merger, BitcaskStore
    │       → understand WHY each Java 21 feature exists from lived experience
    │
    ├──► LSM-Tree storage engine
    │       MemTable (sorted map) + SSTable (sorted file) + level compaction
    │       → understand LevelDB, RocksDB, Cassandra, HBase
    │
    ├──► Raft consensus layer (NileDB Phase 2)
    │       BitcaskStore as the replicated state machine storage
    │       → understand etcd, CockroachDB, TiKV
    │
    ├──► Distributed KV store (NileDB Phase 3)
    │       Consistent hashing across Bitcask nodes + replication factor
    │       → understand DynamoDB, Riak, Cassandra
    │
    └──► Mini SQL engine
            Bitcask as heap file + B-Tree index on top
            → understand PostgreSQL heap files and index pages
```

Every concept in this project — append-only log, in-memory index, compaction,
crash recovery, single-writer concurrency — appears verbatim in Kafka,
Cassandra, RocksDB, and Apache Iceberg. You are not building a toy.
You are building the foundation.
