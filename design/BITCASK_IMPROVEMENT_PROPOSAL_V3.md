# Bitcask — Improvement Proposal v3
> Apache-style design specification for `com.bitcask`
> Java 21 · Gradle · Single-module library
> **Design approach: plain classes first — feel the pain, earn the abstraction**

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
├── BitcaskException.java         ← single unchecked exception for all store errors
│
├── storage/                      ← raw disk I/O — knows nothing about the index
│   ├── LogRecord.java            ← abstract base: shared fields, encode/decode
│   ├── PutRecord.java            ← extends LogRecord: carries value bytes
│   ├── DeleteRecord.java         ← extends LogRecord: tombstone, no value bytes
│   ├── RecordType.java           ← enum: PUT(0x01) / DELETE(0x02)
│   ├── DataFile.java             ← one .data file: append, readAt, sync, close
│   ├── DataFileSet.java          ← manages active + immutable file collection
│   └── DataFileFactory.java      ← creates / opens DataFile instances, file naming
│
├── index/                        ← in-memory KeyDir — knows nothing about disk
│   ├── KeyDir.java               ← HashMap-backed index, thread-safe
│   ├── KeyEntry.java             ← plain value class: fileId, offset, valueSize, timestamp
│   └── ByteArrayKey.java         ← byte[] wrapper with correct equals/hashCode
│
├── merge/                        ← compaction, hint files, background scheduling
│   ├── MergePolicy.java          ← Strategy interface: should we merge right now?
│   ├── SizeTieredMergePolicy.java← Strategy impl: trigger on dead-byte ratio
│   ├── Merger.java               ← executes compaction, writes merged files + hint files
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
Phase 1 ──► RecordType ──► LogRecord ──► PutRecord ──► DeleteRecord
        ──► DataFile   ──► DataFileFactory

Phase 2 ──► ByteArrayKey ──► KeyEntry ──► KeyDir

Phase 3 ──► BitcaskConfig ──► BitcaskException
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

### `RecordType.java`

**Responsibility:** Represent the two legal record types as named, typed constants
and own the mapping between those constants and the single byte written to disk.

**Why this class exists as a separate enum:**
Every encode path needs to write a type byte. Every decode path needs to
interpret one. Without this enum you end up with raw `0x01` and `0x02` literals
scattered across `LogRecord`, `HintFile`, and `StartupLoader` — magic numbers
with no names, no documentation, and no protection against typos. The enum
gives each type a name, a value, and a single place to add a new type in
the future. It also gives you `fromCode()` — a single, validated entry point
for the decode direction.

**Fields:**
- `code` — the single byte value written to disk for this type.
  `PUT = 0x01`, `DELETE = 0x02`. Starting at `0x01` (not `0x00`) makes a
  zeroed-out or uninitialised byte distinguishable from a real type byte during
  debugging.

**Methods:**

```
getCode()
```
- Returns the `byte` constant that this type maps to on disk.
- Called by `LogRecord.encode()` when writing the type byte into the header.
- No logic — pure accessor.

```
fromCode(byte code)
```
- Static factory. Takes the raw byte read from a record header and returns
  the matching `RecordType`.
- Iterates the enum values and compares `code`. If no match is found, throws
  `BitcaskException` with the unrecognised byte value included in the message.
- This is the only place in the codebase where an unknown type byte is
  detected — all decode paths go through here.
- Why not a `switch` on an `int`? Because the byte range of valid types is
  small and sparse. A loop over `values()` is clear, safe, and requires no
  casting.

---

### `LogRecord.java`

**Responsibility:** Own the binary on-disk format for all record types. Hold
the fields shared by every record (`type`, `timestamp`, `key`). Provide the
only `encode()` and `decode()` methods in the codebase.

**Why abstract and not instantiable directly:**
A `LogRecord` with no concrete type is meaningless at the call site. You
cannot make a decision — update the KeyDir, skip the record, remove a key —
without knowing whether it is a PUT or a DELETE. Making the class `abstract`
with a `protected` constructor forces all construction through `PutRecord` or
`DeleteRecord`, which are the only two forms that carry meaning. This is the
Template Method pattern: the structure of encode/decode is fixed here, but the
variable part (`getValue()`) is delegated to subclasses.

**Binary layout on disk (big-endian):**
```
┌─────────┬────────┬───────────┬──────────┬────────────┬─────────┬─────────┐
│   crc   │  type  │ timestamp │ key_size │ value_size │   key   │  value  │
│ 4 bytes │ 1 byte │  8 bytes  │  2 bytes │  4 bytes   │ N bytes │ M bytes │
└─────────┴────────┴───────────┴──────────┴────────────┴─────────┴─────────┘
  HEADER_SIZE = 19 bytes fixed.
  CRC covers all bytes from `type` onward (i.e. everything except the CRC itself).
  DELETE records: value_size = 0, no value bytes written.
```

**Fields:**
- `HEADER_SIZE` — public static final int, value 19. Used by `DataFile.readAt()`
  to know how many bytes to read before it can determine the full record size.
- `type` — `RecordType` enum value. Written as one byte in the header.
- `timestamp` — `long`, Unix epoch millis. 8 bytes. Determines record ordering
  during merge conflict resolution: if the same key appears in two files, the
  record with the larger timestamp wins.
- `key` — `byte[]`. Variable length. key_size in the header tells the decoder
  how many bytes to read.

**Constructor (protected):**
Takes `RecordType`, `long timestamp`, `byte[] key`. Validates that key is
non-null and non-empty — an empty key is meaningless and would produce a
corrupt record. Stores all three as `final` fields.

**Abstract method — `getValue()`:**
Returns the value bytes for this record. `PutRecord` returns its stored value.
`DeleteRecord` returns an empty `byte[]`. This is the only part of the record
that differs between PUT and DELETE — everything else, including `encode()`,
is identical and lives here in the base class.

**Methods:**

```
getType()
```
- Returns the `RecordType` of this record.
- Used in `StartupLoader` and `Merger` to decide how to handle each record
  without an `instanceof` check.

```
getTimestamp()
```
- Returns the write timestamp in Unix epoch millis.
- Used in `Merger` to resolve conflicts: when two records for the same key
  exist in files being merged, the one with the larger timestamp is the live value.

```
getKey()
```
- Returns the raw key bytes.
- Used by every caller that needs to interact with the KeyDir.

```
isTombstone()
```
- Returns `true` if `type == RecordType.DELETE`, `false` otherwise.
- A convenience method. Callers in `StartupLoader` and `Merger` use this
  to decide whether to call `KeyDir.put()` or `KeyDir.remove()`.
- Note: this is the source of the first pain point you will feel. You must
  remember to call this in every place that processes a decoded record.
  The compiler will not remind you.

```
encode()
```
- Allocates a `ByteBuffer` large enough to hold the full record:
  `HEADER_SIZE + key.length + getValue().length` bytes.
- Positions the buffer 4 bytes in (leaving space for the CRC, which cannot
  be computed until all other bytes are written).
- Writes: type byte, timestamp (8 bytes), key_size (2 bytes, `short`),
  value_size (4 bytes, `int`), key bytes, value bytes.
- Computes `CRC32` over the bytes from position 4 to end of buffer.
- Writes the 4-byte CRC integer at position 0.
- Returns the full backing byte array.
- This is the only method in the codebase that writes the binary format.
  All appends go through here.

```
decode(ByteBuffer buffer) [static]
```
- Reads one complete record from `buffer` starting at its current position.
- Advances the buffer position past the entire record so that the caller
  can call `decode()` again in a loop to read subsequent records.
- Reads CRC (4 bytes), type byte, timestamp (8 bytes), key_size (2 bytes),
  value_size (4 bytes), then reads key_size bytes for the key and value_size
  bytes for the value.
- Recomputes `CRC32` over the same range (type byte onward) and compares
  to the stored CRC. If they differ: throws `BitcaskException` with the
  buffer position — this is how partial writes (crash at write time) are detected.
- Calls `RecordType.fromCode()` on the type byte — if the byte is not a
  known type, `fromCode()` throws, which propagates up.
- Constructs and returns either a `PutRecord` or a `DeleteRecord` based on
  the resolved type. `decode()` is the only place in the codebase where
  `PutRecord` and `DeleteRecord` are constructed from disk bytes.

```
toString()
```
- Returns a human-readable representation: class name, type, timestamp,
  key length, value length. Intentionally does NOT print key or value bytes
  since they may be large or binary. Used in log output and test failure messages.

---

### `PutRecord.java`

**Responsibility:** A concrete log record that stores a key-value pair.
Carries the `value` field that base `LogRecord` does not have.

**Why a separate class and not a flag in LogRecord:**
A PUT record has a field (`value`) that a DELETE record must never have.
Putting `value` in the base class would mean DELETE records carry a field that
is meaningless to them — and callers would need to know "this field is only
valid when `isTombstone()` is false." A separate class makes the contract
physical: if you have a `PutRecord`, you have a value. No checking required.

**Fields:**
- `value` — `byte[]`, `final`. The raw value bytes. May be empty (an empty
  value is a legitimate put — it is distinct from a tombstone because the
  `type` byte is `PUT`, not `DELETE`).

**Constructor:**
Takes `long timestamp`, `byte[] key`, `byte[] value`. Calls `super(RecordType.PUT, timestamp, key)`.
Validates that `value` is non-null — a null value in a PutRecord is a
programming error, not a delete. Stores value as `final`.

**Methods:**

```
getValue()  [override]
```
- Returns the stored `value` byte array.
- Called by `LogRecord.encode()` when writing value bytes to disk.
- Called by `BitcaskStore.get()` after reading the record from disk —
  the bytes returned here are what the user receives.

---

### `DeleteRecord.java`

**Responsibility:** A concrete log record that marks a key as deleted.
Carries no value — the tombstone is expressed by the `type` byte alone.

**Why DELETE records have no value field at all:**
A tombstone exists only to mark a key as gone. Giving it a value field —
even one that is always empty — would imply that sometimes it might have a
value. The separate class says: there is no value here, ever. Callers that
receive a `DeleteRecord` cannot accidentally try to read a value from it
(they would have to call `getValue()` which always returns an empty array —
you will feel the awkwardness of this and understand why sealed + pattern
matching is better).

**Fields:**
- `EMPTY_VALUE` — private static final `byte[0]`. Returned by `getValue()`.
  A single shared constant so that every call to `getValue()` on a
  `DeleteRecord` returns the same zero-length array without allocating.

**Constructor:**
Takes `long timestamp`, `byte[] key`. Calls `super(RecordType.DELETE, timestamp, key)`.
No `value` parameter — the absence of a parameter is the contract.

**Methods:**

```
getValue()  [override]
```
- Returns `EMPTY_VALUE` — always an empty byte array.
- `LogRecord.encode()` uses this to write `value_size = 0` and no value bytes.
- Callers who receive a `LogRecord` from `decode()` and call `getValue()`
  without checking `isTombstone()` first will get an empty array and may
  silently treat a deleted key as having an empty value. This is the pain
  point that sealed types eliminate later.

---

### `DataFile.java`

**Responsibility:** Manage a single `.data` file on disk. Write records by
appending. Read records by offset. Sync to disk. Close cleanly.

**Why one class per file and not one class managing all files:**
A `DataFile` models exactly one thing — a single file channel with a current
write position. The question "which files exist and which is active" belongs
to `DataFileSet`. Separating these means `DataFile` is testable in complete
isolation: open a temp file, append a record, read it back, close. No other
class involved.

**Thread safety contract (critical):**
`append()` is NOT thread-safe. `readAt()` IS thread-safe. This asymmetry is
intentional and important. There is always exactly one writer (enforced by
`BitcaskStore` via a write lock), so `append()` does not need to be safe for
concurrent calls. Reads happen concurrently from many threads, so `readAt()`
must be safe. `FileChannel.read(ByteBuffer, position)` with an explicit
position argument is thread-safe in Java — it does not move a shared channel
position pointer, it reads directly from the given offset.

**Fields:**
- `fileId` — `long`, `final`. The timestamp-based ID. Used by `KeyEntry` to
  record which file holds a value, and by `DataFileSet` to map IDs to open
  file handles.
- `path` — `Path`, `final`. The full filesystem path. Used in exception
  messages and by `DataFileFactory` when listing files.
- `channel` — `FileChannel`, `final`. The open NIO channel. All reads and
  writes go through this.
- `writeOffset` — `AtomicLong`. Tracks the current end-of-file write position.
  Atomic because it is read and updated without holding the write lock —
  the `AtomicLong` provides the memory visibility guarantee needed to let
  `readAt()` see the latest written offset safely.
- `readOnly` — `boolean`, `final`. True for immutable files that have been
  rotated. `append()` checks this and throws immediately if true.

**Constructor (package-private):**
Takes all five fields. Callers must go through `DataFileFactory`. This ensures
that file creation and naming conventions are always applied consistently.

**Methods:**

```
append(LogRecord record)
```
- Guard: if `readOnly` is true, throw `BitcaskException` immediately. A
  read-only file should never receive an append call — this is a programming
  error, not a recoverable condition.
- Calls `record.encode()` to get the full byte representation.
- Reads the current `writeOffset` value — this is the offset that will be
  returned to the caller and stored in the `KeyEntry`. The KeyDir entry
  must point to where the record STARTS, not where it ends.
- Writes the encoded bytes to the channel at the current offset. Uses
  `FileChannel.write(ByteBuffer, position)` with an explicit position to
  avoid relying on a shared channel file-pointer (which would not be
  thread-safe if reads and writes overlapped).
- Advances `writeOffset` by the number of bytes written using
  `AtomicLong.addAndGet()`.
- Returns the starting offset (captured before writing). This value is what
  `BitcaskStore` stores in the `KeyEntry`.
- Wraps any `IOException` in `BitcaskException`.

```
readAt(long offset)
```
- Reads `HEADER_SIZE` (19) bytes from the channel at the given offset to
  get the fixed header. Does NOT advance any shared position pointer —
  this is why it is thread-safe.
- Peeks at bytes 13–18 of the header (after crc=4, type=1, timestamp=8)
  to extract `key_size` (2 bytes as unsigned short) and `value_size` (4 bytes).
- Computes total record size: `HEADER_SIZE + key_size + value_size`.
- Allocates a fresh `ByteBuffer` of that size and reads the full record from
  the channel at the same offset. Each call to `readAt()` allocates its own
  buffer — this is intentional because sharing a buffer across threads would
  require locking, eliminating the concurrency benefit.
- Passes the buffer to `LogRecord.decode()`, which validates CRC and constructs
  the appropriate subtype. CRC failure propagates up as `BitcaskException`.
- Returns the decoded `LogRecord` — either a `PutRecord` or `DeleteRecord`.

```
sync()
```
- Calls `channel.force(true)`. The `true` argument means both file data AND
  file metadata (timestamps, size) are flushed to the physical storage device.
  This is what "fsync" means at the OS level.
- The difference between `force(true)` and `force(false)`: `false` flushes
  data but not metadata. For durability you always want `true` — a crash after
  `force(false)` may leave the file in a state where the OS-level size is
  wrong, which makes recovery harder.
- Called by `BitcaskStore` after every `append()` when `BitcaskConfig.isSyncOnWrite()`
  is true. Also called unconditionally in `close()`.
- Wraps `IOException` in `BitcaskException`.

```
getFileId()
```
- Returns the `fileId`. Pure accessor. Used by `KeyEntry` construction in
  `BitcaskStore.put()` and by `DataFileSet` to manage the file map.

```
getPath()
```
- Returns the `Path`. Used in exception messages and by `DataFileFactory`
  when constructing hint file paths alongside a data file.

```
size()
```
- Returns `channel.size()` — the current number of bytes in the file.
- Called by `BitcaskStore` after every `append()` to check whether the file
  has exceeded `BitcaskConfig.getMaxFileSize()`. If so, the active file is
  rotated.
- Also used by `StoreStats` to compute total disk usage.

```
isReadOnly()
```
- Returns the `readOnly` field. Used by `DataFileSet` to distinguish the
  single active file from all immutable files.

```
close()
```
- If not read-only: calls `channel.force(true)` as a best-effort final sync.
  Swallows the IOException if it fails — the channel is being closed anyway,
  and throwing from `close()` would mask the original exception.
- Calls `channel.close()` unconditionally. Releases the OS file descriptor.
- After close, any further use of this `DataFile` instance will throw
  `ClosedChannelException` from the channel — there is no additional guard
  needed here.

---

### `DataFileFactory.java`

**Responsibility:** Create new writable data files and open existing ones.
Centralise all file naming rules and directory listing logic.

**Why a factory and not constructors on DataFile:**
File creation involves naming conventions, `StandardOpenOption` choices, and
path construction that have nothing to do with the per-record operations
`DataFile` owns. If constructors were public, every caller would need to know
the naming scheme. The factory encapsulates: "a data file is named
`<20-digit-zero-padded-millis>.data`, created with `CREATE_NEW + WRITE + READ`
options, opened read-only with just `READ`." Change that rule in one place.

**File naming convention:**
`System.currentTimeMillis()` zero-padded to 20 digits + `.data`.
Example: `00000001714000000000.data`.
20 digits ensures lexicographic sort order equals chronological order for
any timestamp that fits in a `long`. This matters in `listDataFiles()` —
sorting by filename is equivalent to sorting by creation time, which is the
correct order for recovery (oldest file first).

**Methods:**

```
createActive(Path directory)
```
- Generates a new fileId as `System.currentTimeMillis()`.
- Constructs the filename: `String.format("%020d.data", fileId)`.
- Opens a `FileChannel` with `CREATE_NEW, WRITE, READ`. `CREATE_NEW` fails
  if the file already exists — which would only happen if two files were
  created within the same millisecond. In practice this is prevented by the
  single-writer lock, but `CREATE_NEW` adds a safety net.
- Constructs and returns a new `DataFile` with `readOnly = false`.
- Wraps `IOException` in `BitcaskException`.

```
openReadOnly(Path path)
```
- Extracts the fileId from the filename using `fileIdFromPath()`.
- Opens the `FileChannel` with `READ` only — the OS enforces that no writes
  can happen through this channel.
- Constructs and returns a new `DataFile` with `readOnly = true`.
- Called during startup (`StartupLoader`) for all files except the newest one,
  and during merge for candidate files.

```
listDataFiles(Path directory)
```
- Lists all files in `directory` whose names end in `.data` using `Files.list()`.
- Sorts by fileId ascending (oldest first) by parsing each filename via
  `fileIdFromPath()`.
- Returns a `List<Path>`. The list is used by `StartupLoader` to determine
  the recovery order and by `Merger` to identify merge candidates.
- The newest file in the sorted list is the active file. All others are immutable.

```
fileIdFromPath(Path path)  [static, package-visible]
```
- Strips the `.data` extension from the filename and parses the remainder as
  a `long`. Throws `BitcaskException` if parsing fails — this would indicate
  a file in the directory that was not created by this factory (e.g. a lock
  file or a hint file accidentally listed).

---

## 5. Phase 2 — In-Memory Index

---

### `ByteArrayKey.java`

**Responsibility:** Wrap a `byte[]` so that it behaves correctly as a
`HashMap` key — specifically, so that two arrays with identical byte contents
are considered equal.

**Why this class must exist:**
Java's `byte[]` inherits `equals()` and `hashCode()` from `Object`. `Object.equals()`
compares references, not contents. `Object.hashCode()` returns the identity
hash code of the array object in memory. Consequence: two `byte[]` arrays
created from the same string — `"user:1".getBytes()` called twice — produce
two different objects that `HashMap` treats as different keys. Your `put("user:1", value)`
and your `get("user:1")` use different array instances, so `get()` always
returns `null`. The bug is silent — no exception, wrong result.

This class fixes that by implementing `equals()` with `Arrays.equals()` (content
comparison) and `hashCode()` with `Arrays.hashCode()` (content-based hash).

**Fields:**
- `bytes` — `byte[]`, `final`. The wrapped key bytes. Never exposed directly —
  callers get it via `getBytes()`.

**Constructor:**
Takes `byte[]`. Validates non-null. Stores as `final`.

**Methods:**

```
getBytes()
```
- Returns the underlying `byte[]`. Used when the raw key is needed — for
  example, when `KeyDir.snapshot()` is iterated and the caller needs the
  actual key bytes to construct a `LogRecord`.

```
equals(Object other)
```
- Returns `true` if and only if `other` is a `ByteArrayKey` and
  `Arrays.equals(this.bytes, other.bytes)` is true.
- This is the core fix. Without this, HashMap lookup is broken.

```
hashCode()
```
- Returns `Arrays.hashCode(bytes)`. Content-based, consistent with `equals()`.
- A requirement in Java: if two objects are `equals()`, they must have the
  same `hashCode()`. Violating this silently breaks all hash-based collections.

```
toString()
```
- Returns a readable representation. Attempts to decode bytes as UTF-8 for
  display. Used in test failure messages and `KeyDir` debug output.

---

### `KeyEntry.java`

**Responsibility:** An immutable value object pointing from a key to its
exact location on disk. The four fields map 1:1 to the paper's keydir structure.

**Why all fields are final:**
A `KeyEntry` is never updated in place. When a key is overwritten, the old
`KeyEntry` is discarded and a new one is inserted into the `KeyDir`. Mutability
here would be a design mistake — it would imply that a single entry evolves
over time, which is not the Bitcask model.

**Fields:**
- `fileId` — `long`. Which `.data` file holds this value. Combined with
  `offset`, this is an exact pointer to bytes on disk.
- `offset` — `long`. Byte offset within the file where the record starts.
  This is the value returned by `DataFile.append()` and stored directly here.
- `valueSize` — `int`. Number of bytes in the value. Stored here to allow
  a future optimisation: reading only value bytes instead of the full record
  (skipping the header and key re-read). Not used in the initial implementation
  but present because the paper specifies it.
- `timestamp` — `long`. Write timestamp from the record header. Used during
  merge to resolve conflicts between two records for the same key across
  different files. The entry with the larger timestamp is the live one.

**Constructor:**
Takes all four fields. No validation beyond what the types enforce — a
negative offset is technically invalid but the cost of checking everywhere is
not worth it in the initial implementation. You will add validation if you
find a bug caused by a corrupt entry.

**Methods:**
Getters for all four fields. No setters — the object is immutable by design.

```
toString()
```
- Returns a human-readable summary of all four fields. Used in test
  failure output and in `KeyDir` debug logging. You will write this by
  hand and feel how repetitive it is — which is the lesson that leads
  to records.

---

### `KeyDir.java`

**Responsibility:** The in-memory hash index. Maps every live key in the store
to its `KeyEntry`. This is the structure that makes Bitcask fast — every read
consults this first and avoids a full scan of data files.

**Why not expose the HashMap directly:**
The raw `HashMap` uses `ByteArrayKey` as keys — an implementation detail.
Callers should not need to construct `ByteArrayKey` wrappers themselves.
`KeyDir` accepts raw `byte[]` and wraps them internally. This also means the
locking strategy can be changed without touching any caller.

**Thread safety:**
Uses `ReentrantReadWriteLock`. Multiple threads may call `get()` concurrently —
they all take the read lock and proceed in parallel. `put()` and `remove()`
take the write lock and are exclusive. `snapshot()` takes the read lock and
copies the entire map under the lock — the copy is safe to iterate outside
the lock without holding it.

**Fields:**
- `map` — `HashMap<ByteArrayKey, KeyEntry>`. Not `ConcurrentHashMap` — the
  external `ReentrantReadWriteLock` provides finer-grained control. `ConcurrentHashMap`
  would not protect the compound check-then-act operations in `BitcaskStore`
  (read KeyEntry → read from file → update KeyEntry) from races. The lock
  here coordinates with the lock in `BitcaskStore`.
- `lock` — `ReentrantReadWriteLock`. Shared between read and write operations.

**Methods:**

```
put(byte[] key, KeyEntry entry)
```
- Acquires the write lock.
- Wraps `key` in a `ByteArrayKey` and calls `map.put()`.
- Releases the write lock in `finally`.
- Called by `BitcaskStore.put()` after a successful disk append, and by
  `StartupLoader` during recovery after replaying each live record.

```
get(byte[] key)
```
- Acquires the read lock.
- Wraps `key` in `ByteArrayKey` and calls `map.get()`.
- Returns the `KeyEntry` or `null` if absent.
- Releases the read lock in `finally`.
- Returns `null` (not Optional) intentionally — the hot read path allocates
  nothing. Callers must null-check. You will feel the pain of null-checking
  everywhere and understand why `Optional` exists.

```
remove(byte[] key)
```
- Acquires the write lock.
- Removes the entry for the wrapped key.
- Releases the write lock in `finally`.
- Called by `BitcaskStore.delete()` after writing the tombstone to disk.
- Also called during recovery when a `DeleteRecord` is encountered — the
  tombstone means the key was deleted before the crash.

```
size()
```
- Acquires the read lock.
- Returns `map.size()`.
- Used by `StoreStats` to report live key count.

```
snapshot()
```
- Acquires the read lock.
- Copies the entire map into a new `HashMap` and wraps it in
  `Collections.unmodifiableMap()`.
- Returns the unmodifiable copy.
- Used by `Merger` (to identify which records are live) and by
  `Bitcask.listKeys()` (to return a set of all current keys without
  holding the lock during iteration).

---

## 6. Phase 3 — Public API

---

### `BitcaskException.java`

**Responsibility:** A single unchecked exception type for all errors that
originate inside the Bitcask store — I/O failures, CRC mismatches, invalid
state, configuration errors.

**Why unchecked (extends RuntimeException):**
`BitcaskException` wraps `IOException` in most cases. If it were checked,
every call to `put()`, `get()`, `delete()` in user code would require a
`try/catch` or a `throws` declaration. This makes the API painful to use and
clutters call sites. Checked exceptions are appropriate when the caller can
meaningfully recover from the error. In most Bitcask error cases — disk full,
corrupt file, channel closed — the caller cannot recover; it can only log and
stop. An unchecked exception communicates this contract.

**Why a single exception type and not one per error kind:**
`CrcMismatchException`, `ReadFailureException`, `WriteFailureException` would
be over-engineering. Callers catch `BitcaskException` and inspect the message.
If a specific subtype is needed later (e.g. to distinguish "corrupt file, skip"
from "disk full, stop"), a subclass hierarchy can be added then. Start simple.

**Constructor (String message):**
For errors with no underlying cause — e.g. `append()` called on a read-only file.

**Constructor (String message, Throwable cause):**
For errors wrapping an `IOException`. The cause is preserved so that stack
traces show both the Bitcask context and the underlying OS error.

---

### `BitcaskConfig.java`

**Responsibility:** Hold all configuration values for a store instance.
Constructed once at `open()` time and never mutated.

**Why a Builder and not a plain constructor:**
With four or more configuration fields, a telescoping constructor becomes
unreadable: `new BitcaskConfig(1073741824L, false, 0.5, true)` — what does
`false` mean? What does `0.5` refer to? A `Builder` gives every field a name
at the call site, makes all fields optional with sensible defaults, and adds
the ability to validate combinations in `build()` before the config object
is created.

**Fields (all final, all set by Builder):**
- `maxFileSize` — `long`. Bytes. The active data file is rotated when
  `DataFile.size()` exceeds this value after an append. Default: 1 GB.
  Riak's production default is 2 GB.
- `syncOnWrite` — `boolean`. If true, `DataFile.sync()` is called after every
  `append()`. Safe — you will not lose the last write on a crash. Slow —
  every write waits for the disk. Default: false (batch sync instead).
- `mergeDeadRatioThreshold` — `double`, 0.0–1.0. The fraction of disk space
  occupied by dead records above which `SizeTieredMergePolicy.shouldMerge()`
  returns true. Default: 0.5 (merge when more than half the disk is dead data).
- `backgroundMergeEnabled` — `boolean`. If true, `BitcaskStore` starts a
  `ScheduledExecutorService` on open that calls `MergePolicy.shouldMerge()`
  every 60 seconds and runs `merge()` if it returns true. Default: true.

**Builder methods:** `maxFileSize()`, `syncOnWrite()`, `mergeDeadRatioThreshold()`,
`backgroundMergeEnabled()`, each returning `this` for chaining.

```
build()
```
- Validates that `maxFileSize > 0`, `mergeDeadRatioThreshold` is in [0.0, 1.0].
- Constructs and returns the immutable `BitcaskConfig`.

---

### `Bitcask.java` (interface)

**Responsibility:** The public contract for the store. The only type that
users of the library import and depend on. All other classes are implementation
details.

**Why an interface and not an abstract class:**
An interface with no default methods is a pure contract — it says what the
store can do, not how. This makes it easy to create a test double
(`FakeBitcask`, `InMemoryBitcask`) for unit testing code that depends on a
store without touching the disk. An abstract class would carry implementation
details that constrain subclasses.

**Extends `AutoCloseable`:**
Forces users to close the store. `try-with-resources` is the idiomatic Java
pattern for resources that hold OS handles (file descriptors, lock files).
Without `AutoCloseable`, users would need to remember to call `close()` — and
they often wouldn't.

**Methods:**

```
put(byte[] key, byte[] value)
```
- Stores a key-value pair. If the key already exists, the previous value is
  superseded — the old record remains on disk until the next merge, but the
  KeyDir now points to the new record.
- Constraints: key non-null, non-empty. Value non-null. Throws `BitcaskException`
  on write failure.

```
get(byte[] key)
```
- Looks up the key in the KeyDir. If absent, returns null. If present,
  uses the `KeyEntry` to locate and read the record from the correct data file.
- Returns the raw value bytes, or null if the key does not exist.
- At most one disk seek. The KeyDir lookup is in-memory.

```
delete(byte[] key)
```
- Appends a `DeleteRecord` (tombstone) to the active data file.
- Immediately removes the key from the KeyDir so that subsequent `get()` calls
  return null.
- The tombstone record on disk is cleaned up during the next `merge()`.

```
merge()
```
- Triggers compaction of all immutable data files. Reads only live records
  (those still referenced by the KeyDir), writes them into new compact files,
  writes hint files alongside, updates the KeyDir to point to new locations,
  deletes old files.
- Safe to call while reads and writes are active. Does not block `get()` calls.

```
sync()
```
- Forces an explicit fsync on the active data file.
- Paper API equivalent. Useful when `syncOnWrite` is false and the caller
  wants to checkpoint durability at a specific point — e.g. after a batch
  of writes that must survive a crash.

```
listKeys()
```
- Returns a snapshot of all live key byte arrays.
- Calls `KeyDir.snapshot()` and extracts the raw byte arrays from each
  `ByteArrayKey`. The returned set is a point-in-time snapshot — concurrent
  writes after this call are not reflected.

```
stats()
```
- Returns a `StoreStats` snapshot: live key count, file count, dead key
  estimate, dead byte ratio, total disk bytes. Used by `MergePolicy` to
  decide whether to merge and by callers for monitoring.

```
close()
```
- Flushes and syncs the active data file. Shuts down the background executor
  if running. Releases the lock file so another process can open the store.
- After `close()`, all subsequent method calls throw `BitcaskException`.

---

### `BitcaskStore.java`

**Responsibility:** Wire all subsystems together. Implement the `Bitcask`
interface. Coordinate writes, reads, rotation, locking, and lifecycle.

**Fields:**
- `config` — `BitcaskConfig`. Immutable. Read throughout the lifecycle.
- `keyDir` — `KeyDir`. The in-memory index. Populated by `StartupLoader` on
  open, updated on every write and delete.
- `activeFile` — `DataFile`. The currently writable file. Replaced when its
  size exceeds `config.getMaxFileSize()`.
- `immutableFiles` — `Map<Long, DataFile>`. FileId → DataFile for all
  read-only files. Used by `get()` to locate records not in the active file.
  Will be moved into `DataFileSet` in Phase 7.
- `writeLock` — `ReentrantReadWriteLock`. Coordinates write exclusivity and
  KeyDir consistency. The same lock instance is shared with `KeyDir` to make
  the "append to disk + update index" operation atomic from the perspective
  of readers.
- `mergeExecutor` — `ScheduledExecutorService`. Background thread that
  periodically checks `MergePolicy` and calls `merge()` if needed. Null if
  `backgroundMergeEnabled` is false.
- `directory` — `Path`. The store directory. Passed to `Merger` and
  `StartupLoader`.

**Static factory (not a public constructor):**

```
open(Path directory, BitcaskConfig config)
```
- Creates the directory if it does not exist.
- Acquires the lock file (`bitcask.lock`) using `FileChannel.tryLock()`. If
  another process holds the lock, throws `BitcaskException` immediately.
- Runs `StartupLoader.load(directory)` to rebuild the KeyDir from existing
  data files and hint files.
- Opens all existing data files: the newest as writable active, all others as
  read-only immutable. If no data files exist (fresh store), creates a new
  active file with `DataFileFactory.createActive()`.
- If `config.isBackgroundMergeEnabled()`, starts the `ScheduledExecutorService`
  with a fixed-rate schedule.
- Returns the fully initialised `BitcaskStore`.

**Method descriptions:**

```
put(byte[] key, byte[] value)
```
- Acquires write lock.
- Constructs a `PutRecord` with `System.currentTimeMillis()` as the timestamp.
- Calls `activeFile.append(record)` — gets back the starting offset.
- Constructs a `KeyEntry` from `activeFile.getFileId()`, the offset, the value
  length, and the timestamp.
- Calls `keyDir.put(key, entry)` — the KeyDir now points to the new record.
- If `config.isSyncOnWrite()`, calls `activeFile.sync()`.
- Checks `activeFile.size()` against `config.getMaxFileSize()`. If exceeded,
  calls `rotateActiveFile()`.
- Releases write lock.

```
get(byte[] key)
```
- Acquires read lock on KeyDir.
- Calls `keyDir.get(key)`. If null, releases lock and returns null.
- Uses `entry.getFileId()` to find the right `DataFile` — either `activeFile`
  or an entry in `immutableFiles`.
- Releases read lock (the DataFile channel read is thread-safe without the lock).
- Calls `dataFile.readAt(entry.getOffset())` to get the `LogRecord`.
- If the decoded record is a tombstone (should not happen since tombstones
  remove from KeyDir, but defensive check), returns null.
- Returns `record.getValue()`.

```
delete(byte[] key)
```
- Acquires write lock.
- Constructs a `DeleteRecord` with the current timestamp.
- Calls `activeFile.append(record)` — tombstone written to disk.
- Calls `keyDir.remove(key)` — key is immediately gone from the index.
- If `config.isSyncOnWrite()`, calls `activeFile.sync()`.
- Checks file size and rotates if needed.
- Releases write lock.

```
rotateActiveFile()  [private]
```
- Called when `activeFile.size() > config.getMaxFileSize()`.
- Closes the current `activeFile` (triggers final sync inside `DataFile.close()`).
- Moves it to `immutableFiles` map — it is now read-only from this point.
- Calls `DataFileFactory.createActive(directory)` to open a new active file.
- This entire operation happens inside the write lock that `put()` or
  `delete()` already holds — no additional locking needed.

```
merge()
```
- Acquires write lock briefly to snapshot `immutableFiles` and the current
  KeyDir state.
- Releases write lock — merge does not block reads or writes for its duration.
- Passes immutable files and KeyDir snapshot to `Merger.merge()`.
- After `Merger` returns, acquires write lock again to swap the new files
  into `immutableFiles` and update KeyDir entries to point to merged locations.
- Deletes old data files from disk.

```
close()
```
- If `mergeExecutor` is running, calls `shutdown()` and `awaitTermination()`
  with a reasonable timeout (e.g. 5 seconds).
- Calls `activeFile.close()` — flushes, syncs, releases file descriptor.
- Closes all `immutableFiles`.
- Releases the lock file.

---

## 7. Phase 4 — Compaction & Merge

---

### `HintFile.java`

**Responsibility:** Write and read the `.hint` file format. A hint file is a
stripped-down index stored alongside a merged data file — it contains all the
information needed to rebuild `KeyEntry` objects without reading value bytes.

**Why hint files exist:**
On startup, the KeyDir must be rebuilt from disk. Without hint files, every
data file must be fully replayed — reading every record including its value
bytes — just to reconstruct the index. For large stores this is slow. A hint
file contains only the header fields needed to build a `KeyEntry`: timestamp,
key_size, key, value_size, and offset within the companion data file. No value
bytes. Startup reads hint files instead of full data files for all merged
segments — much faster.

**Hint record format on disk:**
```
┌───────────┬──────────┬────────────┬──────────┬─────────┐
│ timestamp │ key_size │ value_size │  offset  │   key   │
│  8 bytes  │  2 bytes │  4 bytes   │  8 bytes │ N bytes │
└───────────┴──────────┴────────────┴──────────┴─────────┘
  No CRC — hint files are not crash-safety critical.
  If a hint file is corrupt, StartupLoader falls back to replaying the data file.
  No type byte — hint files only record live keys (tombstones are not written).
```

**Methods:**

```
write(Path hintPath, Map<ByteArrayKey, KeyEntry> entries)
```
- Opens a new file at `hintPath` for writing.
- For each entry in `entries`: encodes a hint record (timestamp + key_size +
  value_size + offset + key bytes) and appends it to the file.
- Does NOT write tombstone entries — hint files represent the live state only.
- Closes the file when done. Called by `Merger` after writing each merged data file.

```
readInto(Path hintPath, KeyDir keyDir, long fileId)
```
- Opens the hint file at `hintPath` for reading.
- Reads hint records sequentially until end of file. For each record:
  constructs a `KeyEntry` from the stored fields (using `fileId` for the
  `fileId` field — the hint file does not repeat this, it is implicit from
  which data file the hint belongs to) and calls `keyDir.put(key, entry)`.
- If any read fails mid-file (truncated hint), stops reading and returns
  what was loaded so far. Hint file corruption is non-fatal — `StartupLoader`
  can fall back to the data file.
- Called by `StartupLoader` during recovery.

---

### `MergePolicy.java`

**Responsibility:** Define the contract for deciding whether a merge should run.
**Pattern:** Strategy

**Why an interface and not a hardcoded threshold check in `BitcaskStore`:**
Merge trigger logic is a policy decision that may change. A production system
might want: "merge when dead ratio > 0.5 AND total files > 3 AND it is between
2am and 4am." Hardcoding one threshold in `BitcaskStore` locks that decision in.
An interface lets the policy be swapped at construction time — different
deployments, different policies, same `Merger` logic.

**Method:**

```
shouldMerge(StoreStats stats)
```
- Returns `true` if the store should run a merge right now.
- Receives a `StoreStats` snapshot containing dead byte ratio, file count,
  live key count, and total disk bytes — all the data any reasonable policy
  needs.
- Called by `BitcaskStore` on two occasions: when `merge()` is called directly
  by the user (to decide whether to actually run), and by the background
  executor on its schedule.

---

### `SizeTieredMergePolicy.java`

**Responsibility:** The default `MergePolicy` implementation. Triggers merge
when the estimated fraction of dead bytes exceeds a configured threshold.

**Fields:**
- `deadRatioThreshold` — `double`, `final`. Set at construction. Validated
  to be in [0.0, 1.0].

**Constructor:**
Takes `double deadRatioThreshold`. Validates range. Stores as final.

```
shouldMerge(StoreStats stats)
```
- Returns `stats.getDeadByteRatio() >= deadRatioThreshold`.
- That is the entire implementation. The richness is in the `StoreStats`
  that is passed in — computing the dead byte ratio accurately is where the
  complexity lives.

---

### `Merger.java`

**Responsibility:** Execute a compaction run. Read live records from immutable
data files, write them into new compact data files, write hint files, update
the KeyDir, delete old files.

**Why merge only touches immutable files:**
The active file is still being written. Including it in a merge would require
pausing writes — which defeats the purpose of "online merge." All immutable
files are safe to read concurrently because they are never written to after
rotation.

**Why the Merger receives a KeyDir snapshot and not the live KeyDir:**
Merge takes time. During merge, new writes may arrive — overwriting keys that
the Merger is currently processing. If the Merger consulted the live KeyDir, a
key it reads might already be superseded by a newer write to a new file. By
working from a snapshot taken at merge-start, the Merger has a consistent view.
After merge completes, `BitcaskStore` does a final pass to update the KeyDir
entries for merged keys — under the write lock, atomically.

**Method:**

```
merge(List<DataFile> candidates, Map<ByteArrayKey, KeyEntry> keyDirSnapshot, Path directory)
```
- For each candidate data file, iterates all records sequentially using
  `DataFile.readAt()` in a loop from offset 0.
- For each decoded record: checks the KeyDir snapshot to determine if this
  record is live. A record is live if: (1) its key exists in the snapshot,
  AND (2) the snapshot's `KeyEntry` for that key points to THIS file and THIS
  offset. If either condition is false, the record is dead — skip it.
- For live records: appends them to the current output data file (created by
  `DataFileFactory.createActive()`). Captures the new offset. Builds a new
  `KeyEntry` pointing to the output file.
- When the output file exceeds `config.getMaxFileSize()`, rotates to a new
  output file — merge can produce multiple output files.
- After all candidates are processed: calls `HintFile.write()` alongside each
  output data file.
- Returns a `MergeResult` (a simple class holding: new data files + updated
  KeyEntry map). `BitcaskStore` uses this to atomically update the live KeyDir
  and swap the file references.

---

## 8. Phase 5 — Crash Recovery

---

### `StartupLoader.java`

**Responsibility:** Rebuild the `KeyDir` when the store opens. Must handle
any combination of intact files, partially-written files (crash during write),
and missing hint files.

**Why this is a separate class and not a method in `BitcaskStore`:**
Startup loading has its own logic — ordering files, choosing between hint and
data replay, handling CRC failures — that is conceptually distinct from the
ongoing operation of the store. Isolating it makes it independently testable:
"given these files on disk, does the loader produce the correct KeyDir?"

**Recovery strategy (executed in strict order):**

1. Call `DataFileFactory.listDataFiles(directory)` — sorted oldest-first.
2. For each data file path (all except the newest):
   - Derive the hint file path: same directory, same fileId, `.hint` extension.
   - If the hint file exists: call `HintFile.readInto()`. Fast — no value bytes.
   - If no hint file: open the data file read-only and replay it record by record.
3. For the newest data file (the one that was active before shutdown or crash):
   - Always replay from raw data file — it never has a hint file.
   - This is where partial records may appear (crash during write).
4. During raw replay of any file:
   - Read records using `LogRecord.decode()` in a loop.
   - If decode throws `BitcaskException` (CRC mismatch): stop reading this file.
     This is the expected behaviour for a partial write at the tail of the file.
     Everything before the failure position is valid.
   - If the decoded record `isTombstone()`: call `keyDir.remove(key)`.
   - Otherwise: call `keyDir.put(key, entry)`.
5. Return the populated `KeyDir`.

**Method:**

```
load(Path directory)
```
- Orchestrates the above strategy.
- Opens a new empty `KeyDir`.
- Calls `DataFileFactory.listDataFiles()` to get ordered file list.
- Processes each file as described above.
- Returns the populated `KeyDir` to `BitcaskStore.open()`.

---

## 9. Phase 6 — Concurrency

No new classes — this phase hardens `BitcaskStore` and `KeyDir` for safe
concurrent use.

**The two conflicting requirements:**
Bitcask is read-heavy in production. Many threads call `get()` simultaneously.
A naive `synchronized` on the whole store would serialise all reads — terrible
for throughput. `ReentrantReadWriteLock` solves this: any number of readers
proceed simultaneously as long as no writer holds the lock. A writer blocks
until all readers finish, then gets exclusive access.

| Operation | Lock held | Why |
|---|---|---|
| `get()` — KeyDir lookup | Read lock | Concurrent reads are safe |
| `get()` — DataFile.readAt() | No lock | FileChannel positional read is thread-safe |
| `put()` — disk append + KeyDir update | Write lock | Disk write + index update must be atomic |
| `delete()` — disk append + KeyDir remove | Write lock | Same reason |
| `rotateActiveFile()` | Write lock (already held) | No reader should see a file mid-swap |
| `merge()` — file iteration | No lock (works on snapshot) | Merge reads immutable files safely |
| `merge()` — KeyDir update after merge | Write lock (brief) | Swap file references atomically |
| Background merge check | No lock | Only reads `StoreStats`; actual merge acquires its own lock |

**Background merge thread:**
`ScheduledExecutorService` with a single daemon thread. Scheduled at a fixed
rate (e.g. every 60 seconds). On each tick: calls `stats()` to get current
`StoreStats`, passes to `mergePolicy.shouldMerge()`, and calls `merge()` if
true. The executor is shut down in `close()` with `awaitTermination()`.

**`AtomicLong` for write offset in `DataFile`:**
The write offset in `DataFile` is read by `append()` (to determine the start
offset to return) and may be read by `size()` (called from `put()` for
rotation check). `AtomicLong` provides a volatile read/write with
happens-before guarantee — no lock needed for this single field.

---

## 10. Phase 7 — Config & Lifecycle

### `DataFileSet.java`

**Responsibility:** Manage the collection of all open `DataFile` instances —
exactly one active (writable) file and zero or more immutable (read-only) files.
Provide a clean API for looking up a file by ID and rotating the active file.

**Why extract this from `BitcaskStore`:**
In Phase 3, `BitcaskStore` manages `activeFile` and `immutableFiles` directly
as fields. By Phase 7 this becomes unwieldy — rotation logic, file lookup,
and close-all logic are mixed into `BitcaskStore`'s already-large surface area.
`DataFileSet` extracts that concern into its own class with its own responsibility:
"manage the set of open files."

**Fields:**
- `activeFile` — `DataFile`. The single writable file. Replaced on rotation.
- `immutableFiles` — `Map<Long, DataFile>`. FileId → read-only DataFile. All
  files except the active one live here.

**Methods:**

```
getActive()
```
- Returns the current active `DataFile`. Called by `BitcaskStore.put()` and
  `delete()` to append records.

```
getById(long fileId)
```
- Returns the `DataFile` for the given ID — checks active file first, then
  immutable map. Called by `BitcaskStore.get()` after resolving a `KeyEntry`.
- Returns null if no file with that ID is open. This would indicate corruption
  — a KeyEntry pointing to a file that has been deleted. Should never happen
  in correct operation.

```
rotate()
```
- Closes the current active file (triggering its internal sync).
- Moves it to `immutableFiles`.
- Creates a new active file via `DataFileFactory.createActive()`.
- Returns the new active `DataFile`.

```
allImmutable()
```
- Returns an unmodifiable view of all immutable files. Used by `Merger` to
  get the list of merge candidates.

```
replaceImmutable(long oldFileId, DataFile newFile)
```
- Removes `oldFileId` from the immutable map, adds `newFile`.
- Called by `BitcaskStore.merge()` after the `Merger` writes new compact files
  to swap old files for new ones.

```
closeAll()
```
- Closes the active file and all immutable files.
- Called by `BitcaskStore.close()`. Wraps individual close failures and
  rethrows as `BitcaskException` after attempting to close all files.

---

## 11. Phase 8 — Observability

### `StoreStats.java`

**Responsibility:** A point-in-time snapshot of store health metrics.
Consumed by `MergePolicy.shouldMerge()` and returned by `Bitcask.stats()`.

**Why a snapshot and not live getters on the store:**
A snapshot is consistent — all fields are computed at the same instant under
the read lock. If `MergePolicy` called individual getters on the store, the
values could change between calls — the dead ratio computed from two separate
reads might not represent any real moment in time.

**Fields (all final, set in constructor):**
- `liveKeyCount` — `int`. From `KeyDir.size()`. The number of keys currently
  accessible to reads.
- `totalFileCount` — `int`. Active file + all immutable files. Indicates
  how fragmented the store is.
- `deadKeyCount` — `long`. Estimated number of records on disk that are no
  longer referenced by the KeyDir — overwritten keys and tombstones. Computed
  as total records across all data files minus `liveKeyCount`. Approximate
  because counting total records requires scanning file sizes.
- `deadByteRatio` — `double`, 0.0–1.0. The fraction of total disk bytes
  occupied by dead records. The primary metric for `SizeTieredMergePolicy`.
  Computed as `1.0 - (estimated live bytes / total disk bytes)`.
- `totalDiskBytes` — `long`. Sum of `DataFile.size()` across all files.
  Raw disk usage before any compression or compaction.

**Constructor:**
Takes all five values. No validation — `BitcaskStore` is responsible for
computing sane values before constructing `StoreStats`.

**Methods:**
Getters for all five fields. No setters — the snapshot is immutable.

```
toString()
```
- Returns a human-readable one-line summary. Used in logging and in the
  output of `merge()` to report before/after stats.
- You will write this by hand. You will feel the repetition. That is the
  lesson.

---

## 12. Design Patterns Map

| Pattern | Where it appears | Why it appears naturally |
|---|---|---|
| **Template Method** | `LogRecord` (base) + `PutRecord` / `DeleteRecord` | `encode()` structure is identical for both types; only `getValue()` differs. The base class defines the algorithm; subclasses supply the variable part. |
| **Factory** | `DataFileFactory` | File creation requires naming conventions and `StandardOpenOption` choices that have nothing to do with per-record operations. Centralising them in a factory means the naming rule changes in one place. |
| **Strategy** | `MergePolicy` + `SizeTieredMergePolicy` | Merge trigger logic is a policy that varies by deployment. The interface separates the decision from the execution. |
| **Builder** | `BitcaskConfig.Builder` | Four or more configuration fields make a plain constructor unreadable. Builder gives every field a name at the call site and allows optional fields with defaults. |
| **Facade** | `Bitcask` interface + `BitcaskStore` | The store has six subsystems. The interface exposes five methods. Users never see `KeyDir`, `DataFile`, `Merger`, or `StartupLoader`. |
| **Value Object** | `KeyEntry`, `StoreStats`, `BitcaskConfig` | These objects carry data, not behaviour. All fields are final. They are never mutated — replaced entirely when state changes. |
| **Iterator** (implicit) | Record-by-record scan in `Merger` and `StartupLoader` | Walking a data file record by record is logically an iteration. The loop-with-decode pattern is an inline iterator — extractable to an explicit `RecordIterator` class if the pattern repeats. |

---

## 13. Java Concepts Map

| Concept | Where used | What you learn by using it |
|---|---|---|
| `abstract class` + inheritance | `LogRecord`, `PutRecord`, `DeleteRecord` | When inheritance is justified (shared fields + shared algorithm). Why `final` on subclasses prevents unintended extension. |
| `enum` with fields and methods | `RecordType` | Enums are not just constants — they carry data and behaviour. `fromCode()` is a type-safe reverse-lookup pattern. |
| `ByteBuffer` | `LogRecord.encode()`, `LogRecord.decode()`, `DataFile` | Buffer state: position, limit, capacity, flip(). Big-endian vs little-endian. Heap vs direct buffers. |
| `FileChannel` | `DataFile` | NIO channel vs stream. Positional reads (`read(buffer, position)`) vs sequential reads. Why positional reads are thread-safe. |
| `CRC32` | `LogRecord.encode()`, `LogRecord.decode()` | Cyclic redundancy check: fast, cheap, not cryptographic. How data integrity is verified without a hash. |
| `channel.force(true)` | `DataFile.sync()` | The difference between OS page cache and physical storage. What "durable write" actually means. Why `force(true)` vs `force(false)` matters. |
| `AtomicLong` | `DataFile.writeOffset` | Java Memory Model: visibility guarantees. Compare-and-swap. Why `AtomicLong` is safe without a lock for a single incrementing counter. |
| `HashMap` with custom key | `KeyDir` | Why `byte[]` breaks hash-based collections. The contract between `equals()` and `hashCode()`. The `ByteArrayKey` wrapper pattern. |
| `ReentrantReadWriteLock` | `KeyDir`, `BitcaskStore` | Read-write lock semantics. Why it outperforms `synchronized` for read-heavy workloads. `finally` blocks for lock release. |
| `ScheduledExecutorService` | `BitcaskStore` background merge | Thread pool lifecycle. `scheduleAtFixedRate()` vs `scheduleWithFixedDelay()`. Daemon threads. `shutdown()` + `awaitTermination()`. |
| `FileChannel.tryLock()` | `BitcaskStore.open()` | OS-level file locks. Difference between advisory and mandatory locks. Why a lock file prevents two writers. |
| `Path` / `Files` NIO2 | `DataFileFactory`, `StartupLoader` | `Path` vs `File`. `Files.list()` for directory listing. `StandardOpenOption`. Why NIO2 is preferred over `java.io`. |
| `AutoCloseable` + try-with-resources | `DataFile`, `BitcaskStore` | Resource leak prevention. Exception suppression in `close()`. The `finally` block guarantee. |
| Builder pattern (manual) | `BitcaskConfig.Builder` | The telescoping constructor problem. Why named parameters matter in multi-field configuration. |

---

## 14. Known Pain Points

These are the drawbacks you will feel while building with plain classes.
Each one is intentional — it is the lived experience that makes the better
tool meaningful when you encounter it later.

| Pain you will feel | The lesson it is teaching |
|---|---|
| Writing `equals()`, `hashCode()`, `toString()` manually for `KeyEntry` and `StoreStats` | Why Java `record` exists — these three methods are always needed on value objects and are always the same shape |
| Checking `isTombstone()` in every code path that processes a record | Why sealed types + exhaustive switch exist — the compiler cannot remind you to handle both cases |
| Null-checking the return value of `KeyDir.get()` everywhere | Why `Optional<T>` exists — null is not a contract, it is an absence of information |
| Writing a getter for every field of every class | Why records collapse field + accessor + constructor + equals + hashCode + toString into one line |
| The `abstract getValue()` forcing a downcast or a method call to get the value in type-specific code | Why sealed interface + pattern matching is cleaner — `switch(record) { case PutRecord p -> ... }` is more direct than `if (!record.isTombstone())` |

When you have felt all five of these, the refactor to modern Java — sealed
interface, records, pattern matching — will have an obvious, justified reason
behind every change.

---

## 15. Javadoc Standards

Every public class and method in this project follows this template:

```java
/**
 * One-line summary sentence ending with a period.
 *
 * <p>Optional second paragraph: deeper explanation of behaviour,
 * contract, or relationship to other classes. Use {@code inline code}
 * for identifiers and type names. Use {@link ClassName} or
 * {@link ClassName#method()} for cross-references.
 *
 * <p>If the method has complex preconditions or edge cases, use a
 * second {@code <p>} paragraph to describe them explicitly.
 *
 * @param paramName  description including constraints (null? empty? range?)
 * @return           what is returned; when it can be null; never write "returns the X" alone
 * @throws BitcaskException  concrete conditions under which this is thrown
 * @see RelatedClass
 */
```

Rules applied uniformly:
- Every `public` class has a class-level Javadoc explaining its single responsibility.
- Every `public` and `protected` method has a Javadoc.
- Every `@param` describes the parameter AND its constraints. "non-null",
  "must be positive", "may be empty" are constraints — include them.
- `@return` is present on every non-void method. Describe the return value and
  when it is null, empty, or absent.
- `@throws` is present for every thrown exception — including unchecked ones
  that callers realistically need to handle.
- Multi-line code examples go in `<pre>{@code ... }</pre>` blocks inside the
  class-level Javadoc.
- Private methods have at minimum a one-line comment explaining intent.

---

## 16. Testing Strategy

| Phase | Test type | What to verify | Tool |
|---|---|---|---|
| 1 | Unit | `RecordType.fromCode()` returns correct type for known bytes | JUnit 5 |
| 1 | Unit | `RecordType.fromCode()` throws for unknown byte | JUnit 5 |
| 1 | Unit | `PutRecord.encode()` → `LogRecord.decode()` round-trip: all fields preserved | JUnit 5 |
| 1 | Unit | `DeleteRecord.encode()` → `LogRecord.decode()` round-trip: type is DELETE, value is empty | JUnit 5 |
| 1 | Unit | `LogRecord.decode()` throws `BitcaskException` when CRC is corrupted | JUnit 5 |
| 1 | Unit | `DataFile.append()` returns offset 0 for first record | JUnit 5 + `@TempDir` |
| 1 | Unit | `DataFile.append()` returns offset = first record size for second record | JUnit 5 + `@TempDir` |
| 1 | Unit | `DataFile.readAt(offset)` returns the exact record that was appended at that offset | JUnit 5 + `@TempDir` |
| 1 | Unit | `DataFile.append()` on a read-only file throws `BitcaskException` | JUnit 5 + `@TempDir` |
| 1 | Unit | `DataFile.size()` matches total bytes appended | JUnit 5 + `@TempDir` |
| 2 | Unit | `ByteArrayKey.equals()`: two arrays with same bytes are equal | JUnit 5 |
| 2 | Unit | `ByteArrayKey.hashCode()`: two arrays with same bytes have same hash | JUnit 5 |
| 2 | Unit | `KeyDir.get()` returns null for a key that was never put | JUnit 5 |
| 2 | Unit | `KeyDir.get()` returns correct entry after `put()` | JUnit 5 |
| 2 | Unit | `KeyDir.get()` returns null after `remove()` | JUnit 5 |
| 2 | Unit | `KeyDir.put()` overwrites previous entry for same key | JUnit 5 |
| 3 | Integration | `BitcaskStore.put()` followed by `get()` returns the correct value | JUnit 5 + `@TempDir` |
| 3 | Integration | `BitcaskStore.delete()` followed by `get()` returns null | JUnit 5 + `@TempDir` |
| 3 | Integration | `BitcaskStore.put()` for an existing key: `get()` returns the new value | JUnit 5 + `@TempDir` |
| 5 | Integration | `BitcaskStore` closed and reopened: all keys from before close are readable | JUnit 5 + `@TempDir` |
| 5 | Integration | `BitcaskStore` simulated crash (no close): reopen rebuilds correct KeyDir | JUnit 5 + `@TempDir` |
| 4 | Integration | `merge()` called after overwrites: `get()` returns latest value for all keys | JUnit 5 + `@TempDir` |
| 4 | Integration | `merge()` called after deletes: deleted keys not readable after reopen | JUnit 5 + `@TempDir` |
| 4 | Integration | Total disk bytes after merge is less than before merge | JUnit 5 + `@TempDir` |
| 8 | Benchmark | Write throughput: ops/sec for sequential puts | JMH |
| 8 | Benchmark | Read latency: p50 and p99 for random gets | JMH |

**Absolute rule:** Never use a hardcoded path in any test. Always inject
`@TempDir Path dir` — JUnit 5 creates and cleans up the directory automatically.
Tests that write to real paths leak state between runs and fail in CI.

---

## 17. What This Unlocks Next

```
Bitcask — plain class implementation
    │
    ├──► Refactor to Java 21 idioms
    │       sealed interface LogRecord permits PutRecord, DeleteRecord
    │       record KeyEntry(...)
    │       record StoreStats(...)
    │       pattern matching switch in StartupLoader, Merger
    │       → understand WHY each Java 21 feature exists from lived pain
    │
    ├──► LSM-Tree storage engine
    │       MemTable (in-memory sorted map) + SSTable (sorted file) + compaction
    │       → understand LevelDB, RocksDB, Cassandra, HBase internals
    │
    ├──► Raft consensus layer (NileDB Phase 2)
    │       BitcaskStore as the replicated state machine storage
    │       Raft log entries map to PutRecord / DeleteRecord
    │       → understand etcd, CockroachDB, TiKV
    │
    ├──► Distributed KV store (NileDB Phase 3)
    │       Consistent hashing to shard keys across Bitcask nodes
    │       Replication factor: each key written to N nodes
    │       → understand DynamoDB, Riak, Apache Cassandra
    │
    └──► Mini SQL engine
            Bitcask as the heap file (unordered row storage)
            B-Tree index file pointing into Bitcask offsets
            Simple SELECT / INSERT / DELETE query planner
            → understand how PostgreSQL heap files and index pages work
```

Every concept in this project — append-only log, in-memory index, compaction,
crash recovery, single-writer concurrency — appears verbatim in Kafka, Cassandra,
RocksDB, and Apache Iceberg. You are not building a toy. You are building the
foundation.
