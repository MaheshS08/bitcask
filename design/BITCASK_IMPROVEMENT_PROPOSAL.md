# Bitcask — Improvement Proposal & Implementation Guide
> Apache-style design specification for `com.bitcask`  
> Java 21 · Gradle · Single-module library

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
14. [Javadoc Standards](#14-javadoc-standards)
15. [Testing Strategy](#15-testing-strategy)
16. [What This Unlocks Next](#16-what-this-unlocks-next)

---

## 1. Project Philosophy

| Principle | What it means here |
|---|---|
| **Single responsibility** | Every class finishes the sentence "this class is responsible for ___" with one noun phrase |
| **Immutability by default** | Use `record`, `final` fields, and sealed types wherever state does not need to change |
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
├── BitcaskException.java         ← single checked exception hierarchy
│
├── storage/                      ← raw disk I/O, knows nothing about the index
│   ├── LogRecord.java            ← sealed record: binary encode / decode
│   ├── DataFile.java             ← one .data file: append, readAt, close
│   ├── DataFileSet.java          ← manages active + immutable file collection
│   └── DataFileFactory.java      ← creates and opens DataFile instances
│
├── index/                        ← in-memory KeyDir, knows nothing about disk
│   ├── KeyDir.java               ← ConcurrentHashMap wrapper
│   └── KeyEntry.java             ← record: fileId, offset, valueSize, timestamp
│
├── merge/                        ← compaction, hint files, background worker
│   ├── MergePolicy.java          ← Strategy interface
│   ├── SizeTieredMergePolicy.java← default Strategy impl
│   ├── Merger.java               ← reads old segments, writes new + hint
│   └── HintFile.java             ← write and read .hint format
│
├── recovery/                     ← startup: rebuild KeyDir from hint / data files
│   └── StartupLoader.java        ← orchestrates hint-first, data-file fallback
│
└── config/
    └── BitcaskConfig.java        ← record: all tunables in one place
```

---

## 3. Implementation Order

Build bottom-up. Each phase produces something you can test before moving on.

```
Phase 1 ──► LogRecord  ──► DataFile  ──► DataFileFactory
Phase 2 ──► KeyEntry   ──► KeyDir
Phase 3 ──► BitcaskConfig ──► Bitcask (interface) ──► BitcaskStore (wire phases 1+2)
Phase 4 ──► HintFile   ──► MergePolicy ──► SizeTieredMergePolicy ──► Merger
Phase 5 ──► StartupLoader (hint replay + data file fallback)
Phase 6 ──► ReadWriteLock in BitcaskStore + background ScheduledExecutorService
Phase 7 ──► DataFileSet (manage rotation cleanly)
Phase 8 ──► StoreStats.java (dead-key ratio, file count, ops counters)
```

---

## 4. Phase 1 — Core Storage

### `LogRecord.java`
**Responsibility:** Encode and decode a single binary record to/from bytes.  
**Pattern:** Value object (immutable sealed record)  
**Java concepts:** `sealed record`, `ByteBuffer`, `CRC32`, bitwise ops

```java
/**
 * A single entry in the Bitcask log.
 *
 * <p>Binary layout on disk (big-endian):
 * <pre>
 * ┌────────┬───────────┬──────────┬───────────┬─────────┬───────────┐
 * │ crc    │ timestamp │ key_size │ value_size │  key    │  value    │
 * │ 4 bytes│  8 bytes  │  2 bytes │  4 bytes   │ N bytes │  M bytes  │
 * └────────┴───────────┴──────────┴───────────┴─────────┴───────────┘
 * </pre>
 * Total header = 18 bytes. CRC covers everything after the CRC field.
 *
 * @param timestamp  Unix epoch millis when this record was written
 * @param key        raw key bytes; must not be null or empty
 * @param value      raw value bytes; empty byte array signals a tombstone delete
 */
public sealed record LogRecord(long timestamp, byte[] key, byte[] value)
    permits LogRecord.Put, LogRecord.Delete {

    /** The fixed header size in bytes (crc + timestamp + key_size + value_size). */
    public static final int HEADER_SIZE = 18;

    /** Sentinel value used as the value field to mark a deleted key. */
    public static final byte[] TOMBSTONE = new byte[0];

    /** A normal put record. */
    public record Put(long timestamp, byte[] key, byte[] value) extends LogRecord(timestamp, key, value) {}

    /** A delete marker. Value is always TOMBSTONE. */
    public record Delete(long timestamp, byte[] key) extends LogRecord(timestamp, key, TOMBSTONE) {}

    /**
     * Encodes this record into a newly allocated byte array suitable for appending to a data file.
     *
     * @return encoded bytes including header and CRC
     */
    public byte[] encode() { /* ... */ }

    /**
     * Decodes a record from the given ByteBuffer, starting at its current position.
     *
     * @param buffer source buffer positioned at the start of a record header
     * @return decoded LogRecord
     * @throws BitcaskException if the CRC does not match (corrupt or partial record)
     */
    public static LogRecord decode(ByteBuffer buffer) { /* ... */ }

    /**
     * Returns {@code true} if this record represents a deleted key.
     */
    public boolean isTombstone() { return value.length == 0; }
}
```

---

### `DataFile.java`
**Responsibility:** Manage a single `.data` file — append records, read a record at a known offset, fsync, close.  
**Pattern:** none (plain file I/O wrapper)  
**Java concepts:** `FileChannel`, `ByteBuffer`, `force(true)` for fsync, `AtomicLong` for write offset

```java
/**
 * Represents a single Bitcask data file on disk.
 *
 * <p>A data file is append-only. Once rotated (i.e. it is no longer the active
 * file), it becomes immutable and may only be read.
 *
 * <p>Thread safety: {@code append} is NOT thread-safe. The caller
 * ({@link com.bitcask.BitcaskStore}) is responsible for serializing writes.
 * {@code readAt} IS thread-safe and may be called concurrently.
 *
 * @see DataFileFactory
 */
public final class DataFile implements Closeable {

    /**
     * Appends an encoded log record to this file.
     *
     * @param record the record to append
     * @return the byte offset at which this record starts — store this in the KeyDir
     * @throws BitcaskException if the write fails
     */
    public long append(LogRecord record) { /* ... */ }

    /**
     * Reads the record starting at the given byte offset.
     *
     * @param offset byte offset returned by a previous {@link #append} call
     * @return decoded LogRecord
     * @throws BitcaskException if the offset is out of range or CRC fails
     */
    public LogRecord readAt(long offset) { /* ... */ }

    /**
     * Forces all pending writes to the underlying storage device (fsync).
     *
     * <p>Call this after every write if {@link BitcaskConfig#syncOnWrite()} is true,
     * or in batches for higher throughput.
     *
     * @throws BitcaskException if the sync fails
     */
    public void sync() { /* ... */ }

    /**
     * Returns the numeric file ID assigned at creation time.
     */
    public long fileId() { /* ... */ }

    /**
     * Returns the current size of this file in bytes.
     */
    public long size() { /* ... */ }

    @Override
    public void close() throws IOException { /* flush + release channel */ }
}
```

---

### `DataFileFactory.java`
**Responsibility:** Create new data files and open existing ones by path.  
**Pattern:** Factory  
**Java concepts:** `Path`, `StandardOpenOption`, file naming convention

```java
/**
 * Factory for creating and opening {@link DataFile} instances.
 *
 * <p>File naming convention: {@code <fileId>.data} where {@code fileId}
 * is a zero-padded 20-digit Unix epoch nanosecond timestamp, ensuring
 * lexicographic order equals chronological order.
 *
 * <p>Example filename: {@code 00000000001714000000.data}
 */
public final class DataFileFactory {

    /**
     * Creates a new, empty active data file in the given directory.
     *
     * @param directory the Bitcask store directory
     * @return a writable DataFile ready for appending
     */
    public static DataFile createActive(Path directory) { /* ... */ }

    /**
     * Opens an existing data file in read-only mode.
     *
     * @param path path to an existing {@code .data} file
     * @return a read-only DataFile
     */
    public static DataFile openReadOnly(Path path) { /* ... */ }

    /**
     * Lists all data files in the directory, sorted by file ID (oldest first).
     *
     * @param directory the Bitcask store directory
     * @return sorted list of paths to {@code .data} files
     */
    public static List<Path> listDataFiles(Path directory) { /* ... */ }
}
```

---

## 5. Phase 2 — In-Memory Index

### `KeyEntry.java`
**Responsibility:** An immutable pointer from a key to its location on disk.  
**Pattern:** Value object  
**Java concepts:** `record`, value-based equality

```java
/**
 * An entry in the {@link KeyDir} pointing to the location of a key's value on disk.
 *
 * @param fileId     ID of the data file containing the value
 * @param offset     byte offset within that file where the record starts
 * @param valueSize  size of the value in bytes (used for direct value reads)
 * @param timestamp  write timestamp; used during merge to resolve key conflicts
 */
public record KeyEntry(long fileId, long offset, int valueSize, long timestamp) {}
```

---

### `KeyDir.java`
**Responsibility:** Thread-safe in-memory map from key bytes to `KeyEntry`.  
**Pattern:** none (wrapper)  
**Java concepts:** `ConcurrentHashMap`, `Arrays.equals` for byte-array keys, `ReadWriteLock`

```java
/**
 * The in-memory hash index for a Bitcask store.
 *
 * <p>Maps raw key bytes to their on-disk location. All reads consult this
 * structure first — a key not present here does not exist in the store.
 *
 * <p>Thread safety: all methods are thread-safe. Reads are lock-free via
 * {@link ConcurrentHashMap}. Bulk operations (e.g. {@link #replaceAll})
 * used during merge hold an exclusive lock.
 */
public final class KeyDir {

    /** Inserts or replaces the entry for the given key. */
    public void put(byte[] key, KeyEntry entry) { /* ... */ }

    /**
     * Returns the entry for the given key, or {@link Optional#empty()} if absent.
     */
    public Optional<KeyEntry> get(byte[] key) { /* ... */ }

    /** Removes the entry for the given key (called on tombstone writes). */
    public void remove(byte[] key) { /* ... */ }

    /** Returns a snapshot of all entries. Used by the merger and recovery loader. */
    public Map<ByteArrayKey, KeyEntry> snapshot() { /* ... */ }

    /** Returns the number of live keys currently in the index. */
    public int size() { /* ... */ }
}
```

---

## 6. Phase 3 — Public API

### `BitcaskConfig.java`
**Responsibility:** All tunables in one immutable record passed at open time.

```java
/**
 * Immutable configuration for a {@link BitcaskStore}.
 *
 * <p>Use the {@link Builder} to construct instances:
 * <pre>{@code
 * BitcaskConfig config = BitcaskConfig.builder()
 *     .maxFileSize(512 * 1024 * 1024)   // 512 MB
 *     .syncOnWrite(false)
 *     .mergeDeadRatioThreshold(0.5)
 *     .build();
 * }</pre>
 *
 * @param maxFileSize              rotate to a new data file when this size is exceeded (bytes)
 * @param syncOnWrite              if true, fsync after every write (safe but slow)
 * @param mergeDeadRatioThreshold  trigger merge when dead-key ratio exceeds this fraction (0.0–1.0)
 * @param backgroundMergeEnabled   if true, a background thread checks merge eligibility periodically
 */
public record BitcaskConfig(
    long maxFileSize,
    boolean syncOnWrite,
    double mergeDeadRatioThreshold,
    boolean backgroundMergeEnabled
) {
    public static final BitcaskConfig DEFAULT = builder().build();

    public static Builder builder() { return new Builder(); }

    public static final class Builder { /* fluent builder */ }
}
```

---

### `Bitcask.java`
**Responsibility:** The public contract. Users of the library depend only on this interface.  
**Pattern:** Facade

```java
/**
 * A Bitcask key-value store.
 *
 * <p>Bitcask provides:
 * <ul>
 *   <li>O(1) reads via an in-memory {@link KeyDir} index</li>
 *   <li>High write throughput via sequential, append-only disk writes</li>
 *   <li>Crash recovery via hint files and CRC-validated log replay</li>
 * </ul>
 *
 * <p>Obtain an instance via {@link BitcaskStore#open(Path, BitcaskConfig)}.
 *
 * <p>All implementations are {@link AutoCloseable}. Always use try-with-resources:
 * <pre>{@code
 * try (Bitcask store = BitcaskStore.open(path, BitcaskConfig.DEFAULT)) {
 *     store.put("hello".getBytes(), "world".getBytes());
 *     byte[] val = store.get("hello".getBytes()).orElseThrow();
 * }
 * }</pre>
 */
public interface Bitcask extends AutoCloseable {

    /**
     * Stores a key-value pair. If the key already exists, the previous value is superseded.
     *
     * @param key   non-null, non-empty key bytes
     * @param value non-null value bytes
     * @throws BitcaskException if the write fails
     */
    void put(byte[] key, byte[] value);

    /**
     * Retrieves the value associated with {@code key}.
     *
     * @param key key to look up
     * @return the value, or {@link Optional#empty()} if the key does not exist
     * @throws BitcaskException if the disk read fails
     */
    Optional<byte[]> get(byte[] key);

    /**
     * Deletes a key by writing a tombstone record.
     *
     * <p>The key is immediately removed from the in-memory index.
     * The tombstone is cleaned up from disk during the next merge.
     *
     * @param key key to delete
     * @throws BitcaskException if the write fails
     */
    void delete(byte[] key);

    /**
     * Triggers compaction of immutable data files, reclaiming space used by
     * overwritten and deleted keys.
     *
     * <p>This operation is safe to call while reads and writes are in progress.
     *
     * @throws BitcaskException if the merge fails
     */
    void merge();

    /**
     * Returns a snapshot of all live keys. Useful for iteration, debugging, and export.
     *
     * @return unmodifiable set of all current keys
     */
    Set<byte[]> listKeys();

    @Override
    void close();
}
```

---

## 7. Phase 4 — Compaction & Merge

### `MergePolicy.java`
**Responsibility:** Decide whether a merge should run.  
**Pattern:** Strategy

```java
/**
 * Strategy that decides whether a Bitcask store is eligible for compaction.
 *
 * <p>Implement this interface to provide custom merge triggering logic.
 * The default implementation is {@link SizeTieredMergePolicy}.
 */
@FunctionalInterface
public interface MergePolicy {

    /**
     * Returns {@code true} if the store should run a merge based on current stats.
     *
     * @param stats current store statistics
     * @return true if merge should proceed
     */
    boolean shouldMerge(StoreStats stats);
}
```

### `Merger.java`
**Responsibility:** Execute compaction — read live keys from old segments, write to new segments, write hint files, atomically replace old files.

```java
/**
 * Executes a Bitcask compaction (merge) operation.
 *
 * <p>A merge:
 * <ol>
 *   <li>Identifies all immutable data files eligible for compaction</li>
 *   <li>Reads only the latest live value for each key (consulting the KeyDir)</li>
 *   <li>Writes these values into new compacted data files</li>
 *   <li>Writes a {@link HintFile} alongside each new data file</li>
 *   <li>Atomically updates the {@link KeyDir} to point to the new locations</li>
 *   <li>Deletes the old data files</li>
 * </ol>
 *
 * <p>Reads continue to work throughout this process.
 */
public final class Merger {

    /**
     * Runs the merge operation.
     *
     * @param dataFiles immutable files eligible for merge
     * @param keyDir    live index to consult and update
     * @param directory output directory for merged files
     * @throws BitcaskException if any I/O error occurs during merge
     */
    public void merge(List<DataFile> dataFiles, KeyDir keyDir, Path directory) { /* ... */ }
}
```

---

## 8. Phase 5 — Crash Recovery

### `StartupLoader.java`
**Responsibility:** Rebuild the KeyDir when the store opens. Prefer hint files (fast); fall back to replaying raw data files (slow but always correct).

```java
/**
 * Rebuilds the {@link KeyDir} on store startup.
 *
 * <p>Recovery strategy (in order):
 * <ol>
 *   <li>For each data file, check for a corresponding {@code .hint} file</li>
 *   <li>If a hint file exists — read it (fast: no value bytes, just index entries)</li>
 *   <li>If no hint file — replay the raw data file record by record</li>
 *   <li>Skip records that fail CRC validation (partial writes from prior crash)</li>
 *   <li>The active (newest) file always replays from raw since it has no hint</li>
 * </ol>
 */
public final class StartupLoader {

    /**
     * Loads all data files in the store directory and returns a fully populated KeyDir.
     *
     * @param directory the Bitcask store directory
     * @return populated KeyDir ready for reads
     * @throws BitcaskException if files cannot be read
     */
    public KeyDir load(Path directory) { /* ... */ }
}
```

---

## 9. Phase 6 — Concurrency

No new classes — this phase adds thread safety to `BitcaskStore`.

| What | How | Why |
|---|---|---|
| KeyDir reads | `ReentrantReadWriteLock` read-lock | Many threads can read simultaneously |
| KeyDir + file writes | `ReentrantReadWriteLock` write-lock | Single writer prevents torn index state |
| Background merge | `ScheduledExecutorService` | Checks `MergePolicy` every N seconds |
| Append offset | `AtomicLong` | Track write position without a lock |
| File rotation | Write-lock held during swap | Ensures no reader uses a file being closed |

---

## 10. Phase 7 — Config & Lifecycle

`BitcaskStore.open(Path, BitcaskConfig)` is the single entry point. It must:

1. Acquire an exclusive lock file (`bitcask.lock`) — throw if already locked
2. Run `StartupLoader.load()` to rebuild the KeyDir
3. Open the active data file (create if none exists)
4. Start the background merge scheduler if configured
5. Return the store ready for use

`close()` must:

1. Flush and fsync the active data file
2. Shut down the background executor gracefully
3. Release the lock file

---

## 11. Phase 8 — Observability

### `StoreStats.java`

```java
/**
 * A point-in-time snapshot of store health metrics.
 *
 * @param liveKeyCount      number of keys currently in the index
 * @param totalFileCount    number of data files on disk (active + immutable)
 * @param deadKeyCount      estimated number of superseded / deleted records on disk
 * @param deadByteRatio     fraction of disk space occupied by dead records (0.0–1.0)
 * @param totalDiskBytes    total bytes used by all data files
 */
public record StoreStats(
    int liveKeyCount,
    int totalFileCount,
    long deadKeyCount,
    double deadByteRatio,
    long totalDiskBytes
) {}
```

Add `Bitcask#stats()` to the interface returning `StoreStats`. This is what `MergePolicy` consults.

---

## 12. Design Patterns Map

| Pattern | Where | Why it appears |
|---|---|---|
| **Facade** | `Bitcask` interface + `BitcaskStore` | Hide all subsystems behind 4 methods |
| **Strategy** | `MergePolicy` + `SizeTieredMergePolicy` | Swap compaction logic without changing `Merger` |
| **Factory** | `DataFileFactory` | Centralise file creation and naming |
| **Value Object** | `LogRecord`, `KeyEntry`, `StoreStats`, `BitcaskConfig` | Immutability eliminates whole classes of bugs |
| **Builder** | `BitcaskConfig.Builder` | Clean construction of multi-field config |
| **Iterator** (implicit) | `DataFile` segment scan in `Merger` and `StartupLoader` | Walk records without exposing internals |
| **Template Method** (future) | Base `AbstractMergePolicy` if policies share skeleton | Avoid duplication across policy implementations |

---

## 13. Java Concepts Map

| Concept | Class where you use it | What you learn |
|---|---|---|
| `sealed record` | `LogRecord` | Exhaustive pattern matching, value types |
| `ByteBuffer` + `FileChannel` | `DataFile`, `LogRecord` | Zero-copy I/O, buffer positions/limits/capacity |
| `CRC32` | `LogRecord.encode/decode` | Data integrity without hashing overhead |
| `force(true)` | `DataFile.sync()` | What fsync actually does at the OS level |
| `AtomicLong` | `DataFile` write offset | Lock-free counter, memory visibility |
| `ConcurrentHashMap` | `KeyDir` | Why it's safe without external locks |
| `ReentrantReadWriteLock` | `BitcaskStore` | Read-heavy workload concurrency |
| `ScheduledExecutorService` | `BitcaskStore` background merge | Daemon threads, shutdown lifecycle |
| `Path` / `Files` NIO2 | `DataFileFactory`, `StartupLoader` | Modern file I/O (not `java.io.File`) |
| `AutoCloseable` + try-with-resources | `DataFile`, `BitcaskStore` | Resource leak prevention |
| `Optional<T>` | `Bitcask.get()`, `KeyDir.get()` | Explicit absent-value contract |
| `record` + `@FunctionalInterface` | `KeyEntry`, `MergePolicy` | Java 21 idioms |

---

## 14. Javadoc Standards

Follow these rules on every public class and method:

```
/**
 * One-line summary ending with a period.
 *
 * <p>Optional longer explanation. Use {@code inline code} for
 * identifiers and {@link ClassName} for cross-references.
 *
 * @param paramName  what it is and any constraints (null? empty?)
 * @return           what is returned and when it can be absent
 * @throws BitcaskException  under what conditions
 * @see RelatedClass
 */
```

Rules:
- Every `public` class has a class-level Javadoc
- Every `public` and `protected` method has a Javadoc
- `@param` for every parameter — include constraints (non-null? range?)
- `@return` always present unless `void`
- `@throws` for every declared and notable unchecked exception
- Use `<pre>{@code ... }</pre>` for multi-line usage examples

---

## 15. Testing Strategy

| Test type | What to test | Tool |
|---|---|---|
| Unit | `LogRecord` encode → decode roundtrip | JUnit 5 |
| Unit | `LogRecord` CRC corruption detection | JUnit 5 |
| Unit | `DataFile` append + readAt + size | JUnit 5 + `@TempDir` |
| Unit | `KeyDir` put / get / remove / concurrent access | JUnit 5 |
| Unit | `SizeTieredMergePolicy` threshold logic | JUnit 5 |
| Integration | `BitcaskStore` put → get → delete → get | JUnit 5 + `@TempDir` |
| Integration | `BitcaskStore` survives crash (close without flush, re-open) | JUnit 5 |
| Integration | Merge reclaims space and reads still return correct values | JUnit 5 |
| Benchmark (later) | Write throughput ops/sec, read latency p99 | JMH |

**Key rule:** never use real file paths in tests — always use JUnit 5's `@TempDir` so tests are isolated and clean up automatically.

---

## 16. What This Unlocks Next

```
Bitcask (this project)
    │
    ├──► LSM-Tree engine          Add MemTable + SSTable + level compaction
    │                             → understand LevelDB, RocksDB, Cassandra
    │
    ├──► Raft consensus layer     Wrap BitcaskStore as a state machine
    │                             → replicated KV cluster (NileDB Phase 2)
    │
    ├──► Distributed KV store     Add consistent hashing + replication factor
    │                             → understand DynamoDB, Riak
    │
    └──► Mini SQL engine          Use Bitcask as the heap file
                                  → add a B-Tree index on top
                                  → understand how PostgreSQL stores rows
```

Every concept in this project — append-only log, in-memory index, compaction, crash recovery, single-writer concurrency — appears verbatim in Kafka, Cassandra, RocksDB, and Apache Iceberg. You are not building a toy. You are building the foundation.
