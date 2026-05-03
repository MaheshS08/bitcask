# Bitcask — Improvement Proposal v2
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
├── BitcaskException.java         ← single runtime exception hierarchy
│
├── storage/                      ← raw disk I/O, knows nothing about the index
│   ├── LogRecord.java            ← base class: shared fields + encode/decode
│   ├── PutRecord.java            ← extends LogRecord: carries a value
│   ├── DeleteRecord.java         ← extends LogRecord: tombstone, no value
│   ├── RecordType.java           ← enum: PUT(0x01), DELETE(0x02)
│   ├── DataFile.java             ← one .data file: append, readAt, close
│   ├── DataFileSet.java          ← manages active + immutable file collection
│   └── DataFileFactory.java      ← creates and opens DataFile instances
│
├── index/                        ← in-memory KeyDir, knows nothing about disk
│   ├── KeyDir.java               ← HashMap wrapper with manual equals/hashCode
│   ├── KeyEntry.java             ← plain class: fileId, offset, valueSize, timestamp
│   └── ByteArrayKey.java         ← wrapper for byte[] with correct equals/hashCode
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
    └── BitcaskConfig.java        ← plain class with Builder: all tunables
```

---

## 3. Implementation Order

Build bottom-up. Each phase produces something you can test before moving on.

```
Phase 1 ──► RecordType ──► LogRecord ──► PutRecord ──► DeleteRecord
        ──► DataFile   ──► DataFileFactory

Phase 2 ──► ByteArrayKey ──► KeyEntry ──► KeyDir

Phase 3 ──► BitcaskConfig ──► BitcaskException
        ──► Bitcask (interface) ──► BitcaskStore (wire phases 1+2)

Phase 4 ──► HintFile ──► MergePolicy ──► SizeTieredMergePolicy ──► Merger

Phase 5 ──► StartupLoader (hint replay + data file fallback)

Phase 6 ──► ReadWriteLock in BitcaskStore + background ScheduledExecutorService

Phase 7 ──► DataFileSet (manage rotation cleanly)

Phase 8 ──► StoreStats (dead-key ratio, file count, ops counters)
```

---

## 4. Phase 1 — Core Storage

---

### `RecordType.java`
**Responsibility:** Represent the type byte written into every record header on disk.
**Pattern:** none (plain enum)
**Why a separate class:** Every encode/decode path needs this constant.
Keeping it in an enum makes the byte value explicit and named — no magic
numbers scattered across encode() and decode().

```java
/**
 * Identifies the type of a {@link LogRecord} as stored in the record header.
 *
 * <p>The type byte is written as the second field in every record header,
 * immediately after the CRC:
 * <pre>
 * [ crc(4) | type(1) | timestamp(8) | key_size(2) | value_size(4) | key | value ]
 * </pre>
 *
 * <p>Adding a new record type here will require updating
 * {@link LogRecord#decode(java.nio.ByteBuffer)} to handle the new value.
 */
public enum RecordType {

    /** A normal key-value write. Value bytes follow the key in the record. */
    PUT((byte) 0x01),

    /**
     * A tombstone delete marker. value_size is written as 0.
     * No value bytes follow the key. The key is removed from the KeyDir on read.
     */
    DELETE((byte) 0x02);

    private final byte code;

    RecordType(byte code) {
        this.code = code;
    }

    /**
     * Returns the single byte written to disk for this record type.
     *
     * @return type byte
     */
    public byte getCode() {
        return code;
    }

    /**
     * Resolves a type byte read from disk back to a {@link RecordType}.
     *
     * @param code the byte read from the record header
     * @return matching RecordType
     * @throws BitcaskException if the byte does not match any known type
     */
    public static RecordType fromCode(byte code) {
        for (RecordType type : values()) {
            if (type.code == code) return type;
        }
        throw new BitcaskException("Unknown record type byte: " + code);
    }
}
```

---

### `LogRecord.java`
**Responsibility:** Hold the fields shared by all record types and own the
encode/decode logic for the binary on-disk format.
**Pattern:** Value object (base class — not instantiated directly)
**Java concepts:** `ByteBuffer`, `CRC32`, `FileChannel`, inheritance

**Why a base class and not just one flat class:**
Both PutRecord and DeleteRecord share three fields: `timestamp`, `key`,
and `type`. Rather than duplicating those fields and their validation in
two separate classes, they live here once. The base class also owns
`encode()` and `decode()` because the binary format is the same structure
regardless of type — only the value bytes differ.

**Why not instantiate LogRecord directly:**
A raw `LogRecord` with no type context is meaningless — you cannot act on
it without knowing whether it's a put or a delete. Making the constructor
`protected` forces callers through `PutRecord` or `DeleteRecord`, which are
the only meaningful concrete forms.

```java
/**
 * Base class for all Bitcask log entries written to disk.
 *
 * <p>Binary layout on disk (big-endian):
 * <pre>
 * ┌─────────┬────────┬───────────┬──────────┬────────────┬─────────┬─────────┐
 * │   crc   │  type  │ timestamp │ key_size │ value_size │   key   │  value  │
 * │ 4 bytes │ 1 byte │  8 bytes  │  2 bytes │  4 bytes   │ N bytes │ M bytes │
 * └─────────┴────────┴───────────┴──────────┴────────────┴─────────┴─────────┘
 * </pre>
 *
 * <p>Header is 19 bytes fixed. CRC covers everything from {@code type} onward.
 * {@code value_size} is 0 and no value bytes are written for DELETE records.
 *
 * <p>Do not instantiate this class directly.
 * Use {@link PutRecord} or {@link DeleteRecord}.
 *
 * @see PutRecord
 * @see DeleteRecord
 */
public abstract class LogRecord {

    /** Fixed header size in bytes: crc(4) + type(1) + ts(8) + ksz(2) + vsz(4). */
    public static final int HEADER_SIZE = 19;

    private final RecordType type;
    private final long timestamp;
    private final byte[] key;

    /**
     * Constructs a log record with the given type, timestamp, and key.
     *
     * @param type      record type — PUT or DELETE
     * @param timestamp Unix epoch millis when this record was created
     * @param key       raw key bytes; must not be null or empty
     * @throws IllegalArgumentException if key is null or empty
     */
    protected LogRecord(RecordType type, long timestamp, byte[] key) {
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException("Key must not be null or empty");
        }
        this.type = type;
        this.timestamp = timestamp;
        this.key = key;
    }

    /**
     * Returns the record type.
     *
     * @return PUT or DELETE
     */
    public RecordType getType() {
        return type;
    }

    /**
     * Returns the Unix epoch millis timestamp of this record.
     *
     * @return timestamp in milliseconds
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Returns the key bytes for this record.
     *
     * @return key bytes; never null or empty
     */
    public byte[] getKey() {
        return key;
    }

    /**
     * Returns true if this record is a tombstone delete marker.
     *
     * <p>Callers should use this to decide whether to update or remove
     * the key from the {@link com.bitcask.index.KeyDir}.
     *
     * @return true if DELETE, false if PUT
     */
    public boolean isTombstone() {
        return type == RecordType.DELETE;
    }

    /**
     * Returns the value bytes for this record, or an empty array for DELETE records.
     *
     * <p>Subclasses override this to return their specific value.
     *
     * @return value bytes; empty array for tombstone records
     */
    public abstract byte[] getValue();

    /**
     * Encodes this record into a byte array suitable for appending to a data file.
     *
     * <p>Layout: [ crc(4) | type(1) | timestamp(8) | key_size(2) | value_size(4) | key | value ]
     * CRC is computed over all bytes from type onward.
     *
     * @return fully encoded record bytes including header and CRC
     */
    public byte[] encode() {
        byte[] value    = getValue();
        int totalSize   = HEADER_SIZE + key.length + value.length;
        ByteBuffer buf  = ByteBuffer.allocate(totalSize);

        // Reserve 4 bytes for CRC — will be filled after computing it
        buf.position(4);

        // Write fields covered by CRC
        buf.put(type.getCode());
        buf.putLong(timestamp);
        buf.putShort((short) key.length);
        buf.putInt(value.length);
        buf.put(key);
        buf.put(value);

        // Compute CRC over bytes [4 .. totalSize)
        CRC32 crc = new CRC32();
        crc.update(buf.array(), 4, totalSize - 4);

        // Write CRC at position 0
        buf.putInt(0, (int) crc.getValue());

        return buf.array();
    }

    /**
     * Decodes a record from the given ByteBuffer positioned at the start of a record header.
     *
     * <p>After this call, the buffer's position is advanced past the entire record.
     *
     * @param buffer source buffer positioned at the first byte of the CRC field
     * @return a {@link PutRecord} or {@link DeleteRecord} depending on the type byte
     * @throws BitcaskException if CRC validation fails (corrupt or partial record)
     */
    public static LogRecord decode(ByteBuffer buffer) {
        int startPosition = buffer.position();

        int storedCrc = buffer.getInt();
        byte typeByte = buffer.get();
        long timestamp = buffer.getLong();
        int keySize   = Short.toUnsignedInt(buffer.getShort());
        int valueSize = buffer.getInt();

        byte[] key   = new byte[keySize];
        byte[] value = new byte[valueSize];
        buffer.get(key);
        buffer.get(value);

        // Validate CRC over everything after the CRC field
        CRC32 crc = new CRC32();
        crc.update(buffer.array(), startPosition + 4, HEADER_SIZE - 4 + keySize + valueSize);

        if ((int) crc.getValue() != storedCrc) {
            throw new BitcaskException("CRC mismatch — corrupt or partial record at position " + startPosition);
        }

        RecordType type = RecordType.fromCode(typeByte);

        return switch (type) {
            case PUT    -> new PutRecord(timestamp, key, value);
            case DELETE -> new DeleteRecord(timestamp, key);
        };
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()
            + "{ type=" + type
            + ", timestamp=" + timestamp
            + ", key.length=" + key.length
            + ", value.length=" + getValue().length
            + " }";
    }
}
```

---

### `PutRecord.java`
**Responsibility:** A log record that stores a key-value pair.
**Pattern:** Value object (concrete subclass of LogRecord)
**Why a separate class:** PUT records carry a value — a concern that DELETE
records do not have. Separating them means the value field is only present
where it is meaningful. No null checks, no "is this field valid for this type?"

```java
/**
 * A Bitcask log record representing a key-value write (PUT operation).
 *
 * <p>PutRecord carries the actual value bytes. Its {@link #getValue()}
 * method returns these bytes as written to disk.
 *
 * <p>Example:
 * <pre>{@code
 * PutRecord record = new PutRecord(
 *     System.currentTimeMillis(),
 *     "user:123".getBytes(),
 *     "{\"name\":\"Mahesh\"}".getBytes()
 * );
 * byte[] encoded = record.encode();   // ready to append to DataFile
 * }</pre>
 *
 * @see DeleteRecord
 * @see LogRecord
 */
public final class PutRecord extends LogRecord {

    private final byte[] value;

    /**
     * Constructs a PUT record.
     *
     * @param timestamp Unix epoch millis when this record was created
     * @param key       raw key bytes; must not be null or empty
     * @param value     raw value bytes; must not be null (may be empty)
     * @throws IllegalArgumentException if key is null/empty, or value is null
     */
    public PutRecord(long timestamp, byte[] key, byte[] value) {
        super(RecordType.PUT, timestamp, key);
        if (value == null) {
            throw new IllegalArgumentException("Value must not be null in a PutRecord");
        }
        this.value = value;
    }

    /**
     * Returns the value bytes stored in this record.
     *
     * @return value bytes; never null, may be empty
     */
    @Override
    public byte[] getValue() {
        return value;
    }
}
```

---

### `DeleteRecord.java`
**Responsibility:** A log record that marks a key as deleted (tombstone).
**Pattern:** Value object (concrete subclass of LogRecord)
**Why a separate class:** DELETE records have no value. A separate class
makes this explicit — you cannot accidentally access a value on a delete.
The type system communicates intent.

```java
/**
 * A Bitcask log record representing a key deletion (DELETE operation).
 *
 * <p>DeleteRecord is a tombstone marker. It carries no value bytes.
 * When this record is encountered during recovery or merge, the key
 * must be removed from the {@link com.bitcask.index.KeyDir} and
 * ultimately purged from disk during compaction.
 *
 * <p>Example:
 * <pre>{@code
 * DeleteRecord record = new DeleteRecord(
 *     System.currentTimeMillis(),
 *     "user:123".getBytes()
 * );
 * byte[] encoded = record.encode();   // tombstone appended to DataFile
 * }</pre>
 *
 * @see PutRecord
 * @see LogRecord
 */
public final class DeleteRecord extends LogRecord {

    /** Empty value returned for all delete records — no bytes written to disk. */
    private static final byte[] EMPTY_VALUE = new byte[0];

    /**
     * Constructs a DELETE record (tombstone).
     *
     * @param timestamp Unix epoch millis when the delete was issued
     * @param key       raw key bytes for the key being deleted; must not be null or empty
     */
    public DeleteRecord(long timestamp, byte[] key) {
        super(RecordType.DELETE, timestamp, key);
    }

    /**
     * Returns an empty byte array — DELETE records carry no value.
     *
     * @return empty byte array; never null
     */
    @Override
    public byte[] getValue() {
        return EMPTY_VALUE;
    }
}
```

---

### `DataFile.java`
**Responsibility:** Manage a single `.data` file — append records, read a
record at a known byte offset, fsync, close.
**Pattern:** none (plain file I/O wrapper)
**Java concepts:** `FileChannel`, `ByteBuffer`, `force(true)` for fsync,
`AtomicLong` for write offset tracking

```java
/**
 * Represents a single Bitcask data file on disk.
 *
 * <p>A data file is append-only. Once rotated (i.e. it is no longer the
 * active file), it becomes immutable and may only be read via
 * {@link #readAt(long)}.
 *
 * <p><b>Thread safety:</b> {@link #append(LogRecord)} is NOT thread-safe.
 * The caller ({@link com.bitcask.BitcaskStore}) is responsible for
 * serializing all writes. {@link #readAt(long)} IS thread-safe and may
 * be called by multiple threads concurrently.
 *
 * @see DataFileFactory
 */
public final class DataFile implements Closeable {

    private final long fileId;
    private final Path path;
    private final FileChannel channel;
    private final AtomicLong writeOffset;
    private final boolean readOnly;

    /**
     * Package-private constructor — use {@link DataFileFactory} to create instances.
     *
     * @param fileId   numeric file ID (timestamp-based)
     * @param path     path to the .data file on disk
     * @param channel  open FileChannel — writable if active, read-only if immutable
     * @param readOnly true if this file is immutable (no appends allowed)
     */
    DataFile(long fileId, Path path, FileChannel channel, boolean readOnly) {
        this.fileId      = fileId;
        this.path        = path;
        this.channel     = channel;
        this.readOnly    = readOnly;
        this.writeOffset = new AtomicLong(0);
    }

    /**
     * Appends a log record to this data file.
     *
     * <p>The record is encoded to bytes and written at the current end of file.
     * The write offset is advanced atomically after a successful write.
     *
     * @param record the record to append; must not be null
     * @return the byte offset at which this record starts — store this in the KeyDir
     * @throws BitcaskException    if this file is read-only or the write fails
     * @throws NullPointerException if record is null
     */
    public long append(LogRecord record) {
        if (readOnly) {
            throw new BitcaskException("Cannot append to read-only data file: " + path);
        }
        byte[] encoded    = record.encode();
        long   offset     = writeOffset.get();
        ByteBuffer buffer = ByteBuffer.wrap(encoded);
        try {
            while (buffer.hasRemaining()) {
                channel.write(buffer, offset + (encoded.length - buffer.remaining()));
            }
            writeOffset.addAndGet(encoded.length);
            return offset;
        } catch (IOException e) {
            throw new BitcaskException("Failed to append record to " + path, e);
        }
    }

    /**
     * Reads and decodes the record starting at the given byte offset.
     *
     * <p>This method may be called concurrently by multiple threads.
     * Each call allocates its own ByteBuffer to avoid shared state.
     *
     * @param offset byte offset returned by a previous {@link #append} call
     * @return the decoded LogRecord (PutRecord or DeleteRecord)
     * @throws BitcaskException if the offset is out of range or CRC validation fails
     */
    public LogRecord readAt(long offset) {
        try {
            // Read fixed header first to determine total record size
            ByteBuffer header = ByteBuffer.allocate(LogRecord.HEADER_SIZE);
            channel.read(header, offset);
            header.flip();

            // Peek at key_size and value_size without consuming the buffer
            // crc(4) + type(1) + timestamp(8) = 13 bytes before key_size
            int keySize   = Short.toUnsignedInt(header.getShort(13));
            int valueSize = header.getInt(15);

            // Read the full record
            int totalSize    = LogRecord.HEADER_SIZE + keySize + valueSize;
            ByteBuffer full  = ByteBuffer.allocate(totalSize);
            channel.read(full, offset);
            full.flip();

            return LogRecord.decode(full);
        } catch (IOException e) {
            throw new BitcaskException("Failed to read record at offset " + offset + " in " + path, e);
        }
    }

    /**
     * Forces all pending writes to reach the underlying storage device (fsync).
     *
     * <p>Call this after every write when {@link BitcaskConfig#isSyncOnWrite()}
     * is true. For higher throughput, call in batches instead.
     *
     * @throws BitcaskException if the sync fails
     */
    public void sync() {
        try {
            channel.force(true);
        } catch (IOException e) {
            throw new BitcaskException("Failed to sync data file: " + path, e);
        }
    }

    /**
     * Returns the numeric file ID assigned at creation time.
     * The ID is also the creation timestamp in milliseconds.
     *
     * @return file ID
     */
    public long getFileId() {
        return fileId;
    }

    /**
     * Returns the path of this data file on disk.
     *
     * @return file path
     */
    public Path getPath() {
        return path;
    }

    /**
     * Returns the current size of this file in bytes.
     *
     * @return file size in bytes
     * @throws BitcaskException if the size cannot be determined
     */
    public long size() {
        try {
            return channel.size();
        } catch (IOException e) {
            throw new BitcaskException("Failed to get size of " + path, e);
        }
    }

    /**
     * Returns true if this file is read-only (immutable after rotation).
     *
     * @return true if no appends are allowed
     */
    public boolean isReadOnly() {
        return readOnly;
    }

    /**
     * Flushes pending writes, syncs to disk, and closes the underlying channel.
     *
     * @throws IOException if closing the channel fails
     */
    @Override
    public void close() throws IOException {
        if (!readOnly) {
            try {
                channel.force(true);
            } catch (IOException ignored) {
                // best-effort sync before close
            }
        }
        channel.close();
    }
}
```

---

### `DataFileFactory.java`
**Responsibility:** Create new data files and open existing ones.
**Pattern:** Factory
**Java concepts:** `Path`, `Files`, `StandardOpenOption`, file naming

```java
/**
 * Factory for creating and opening {@link DataFile} instances.
 *
 * <p>File naming convention: {@code <fileId>.data} where {@code fileId} is
 * {@link System#currentTimeMillis()} at creation time, zero-padded to 20 digits.
 * This ensures lexicographic order equals chronological order.
 *
 * <p>Example filename: {@code 00000001714000000000.data}
 *
 * <p>Hint files produced during merge use the same fileId with extension
 * {@code .hint}: {@code 00000001714000000000.hint}
 */
public final class DataFileFactory {

    private static final String DATA_EXTENSION = ".data";

    /** Utility class — no instances. */
    private DataFileFactory() {}

    /**
     * Creates a new, empty writable data file in the given directory.
     * The file ID is set to {@link System#currentTimeMillis()}.
     *
     * @param directory the Bitcask store directory; must exist and be writable
     * @return a new writable DataFile ready for appending
     * @throws BitcaskException if the file cannot be created
     */
    public static DataFile createActive(Path directory) {
        long   fileId   = System.currentTimeMillis();
        String filename = String.format("%020d%s", fileId, DATA_EXTENSION);
        Path   path     = directory.resolve(filename);
        try {
            FileChannel channel = FileChannel.open(path,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                StandardOpenOption.READ);
            return new DataFile(fileId, path, channel, false);
        } catch (IOException e) {
            throw new BitcaskException("Failed to create active data file: " + path, e);
        }
    }

    /**
     * Opens an existing data file in read-only mode.
     *
     * @param path path to an existing {@code .data} file
     * @return a read-only DataFile
     * @throws BitcaskException if the file cannot be opened
     */
    public static DataFile openReadOnly(Path path) {
        long fileId = fileIdFromPath(path);
        try {
            FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
            return new DataFile(fileId, path, channel, true);
        } catch (IOException e) {
            throw new BitcaskException("Failed to open data file for reading: " + path, e);
        }
    }

    /**
     * Lists all {@code .data} files in the directory, sorted by file ID (oldest first).
     *
     * @param directory the Bitcask store directory
     * @return sorted list of paths to {@code .data} files; empty if none exist
     * @throws BitcaskException if the directory cannot be read
     */
    public static List<Path> listDataFiles(Path directory) {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                .filter(p -> p.toString().endsWith(DATA_EXTENSION))
                .sorted(Comparator.comparing(DataFileFactory::fileIdFromPath))
                .collect(Collectors.toList());
        } catch (IOException e) {
            throw new BitcaskException("Failed to list data files in: " + directory, e);
        }
    }

    /**
     * Extracts the numeric file ID from a data file path.
     * Filename must be in the format {@code <20-digit-id>.data}.
     *
     * @param path path to a data file
     * @return numeric file ID
     * @throws BitcaskException if the filename does not match the expected format
     */
    public static long fileIdFromPath(Path path) {
        String name = path.getFileName().toString();
        String idPart = name.replace(DATA_EXTENSION, "");
        try {
            return Long.parseLong(idPart);
        } catch (NumberFormatException e) {
            throw new BitcaskException("Cannot parse file ID from filename: " + name, e);
        }
    }
}
```

---

## 5. Phase 2 — In-Memory Index

---

### `ByteArrayKey.java`
**Responsibility:** Wrap a `byte[]` with correct `equals()` and `hashCode()`
so it can be used safely as a `HashMap` key.
**Why needed:** Java's `byte[]` uses reference equality in `HashMap`. Two
different arrays with identical bytes are NOT equal by default. Without this
wrapper your KeyDir silently becomes broken — get() never finds a key that
put() just stored, and you spend hours debugging.

```java
/**
 * A wrapper around a byte array that provides value-based equality and hashing.
 *
 * <p>Java's {@code byte[]} uses reference equality in hash-based collections.
 * This means two byte arrays with identical contents are treated as different
 * keys in a {@link java.util.HashMap}. This class fixes that.
 *
 * <p>Used as the key type in {@link KeyDir} to ensure correct lookup semantics.
 *
 * <p>Example — why this is necessary:
 * <pre>{@code
 * byte[] a = "hello".getBytes();
 * byte[] b = "hello".getBytes();
 * new HashMap<byte[], String>().put(a, "x");
 * map.get(b);  // returns null — WRONG
 *
 * new HashMap<ByteArrayKey, String>().put(new ByteArrayKey(a), "x");
 * map.get(new ByteArrayKey(b));  // returns "x" — CORRECT
 * }</pre>
 */
public final class ByteArrayKey {

    private final byte[] bytes;

    /**
     * Constructs a ByteArrayKey wrapping the given bytes.
     *
     * @param bytes the raw key bytes; must not be null
     * @throws IllegalArgumentException if bytes is null
     */
    public ByteArrayKey(byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("Key bytes must not be null");
        this.bytes = bytes;
    }

    /**
     * Returns the underlying byte array.
     *
     * @return raw key bytes; never null
     */
    public byte[] getBytes() {
        return bytes;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ByteArrayKey)) return false;
        return Arrays.equals(bytes, ((ByteArrayKey) other).bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "ByteArrayKey{" + new String(bytes) + "}";
    }
}
```

---

### `KeyEntry.java`
**Responsibility:** An immutable pointer from a key to its location on disk.
**Pattern:** Value object (plain class with final fields)
**Why not a record yet:** You will feel the absence of auto-generated
`equals()`, `hashCode()`, and `toString()` when writing tests. That friction
is the signal that a record would be better here — but feel it first.

```java
/**
 * An entry in the {@link KeyDir} pointing to the on-disk location of a key's value.
 *
 * <p>All fields are final — a KeyEntry is never mutated after creation.
 * When a key is overwritten, the old KeyEntry is replaced entirely in the KeyDir.
 *
 * <p>Fields map directly to the paper's keydir structure:
 * <ul>
 *   <li>{@code fileId}    → which .data file contains this value</li>
 *   <li>{@code offset}    → byte offset within that file (value_pos in paper)</li>
 *   <li>{@code valueSize} → size of the value in bytes (value_sz in paper)</li>
 *   <li>{@code timestamp} → write timestamp; used in merge to resolve conflicts</li>
 * </ul>
 */
public final class KeyEntry {

    private final long fileId;
    private final long offset;
    private final int  valueSize;
    private final long timestamp;

    /**
     * Constructs a KeyEntry.
     *
     * @param fileId    ID of the data file containing this value
     * @param offset    byte offset in that file where the record starts
     * @param valueSize size of the value in bytes
     * @param timestamp write timestamp in Unix epoch millis
     */
    public KeyEntry(long fileId, long offset, int valueSize, long timestamp) {
        this.fileId    = fileId;
        this.offset    = offset;
        this.valueSize = valueSize;
        this.timestamp = timestamp;
    }

    /** @return ID of the data file containing this value */
    public long getFileId()    { return fileId;    }

    /** @return byte offset in the data file where the record starts */
    public long getOffset()    { return offset;    }

    /** @return size of the value in bytes */
    public int  getValueSize() { return valueSize; }

    /** @return write timestamp in Unix epoch millis */
    public long getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return "KeyEntry{"
            + "fileId=" + fileId
            + ", offset=" + offset
            + ", valueSize=" + valueSize
            + ", timestamp=" + timestamp
            + "}";
    }
}
```

---

### `KeyDir.java`
**Responsibility:** Thread-safe in-memory map from `ByteArrayKey` to `KeyEntry`.
**Java concepts:** `HashMap`, `ByteArrayKey` for correct equality,
`ReentrantReadWriteLock` for concurrent access

```java
/**
 * The in-memory hash index for a Bitcask store.
 *
 * <p>Maps raw key bytes to their on-disk location ({@link KeyEntry}).
 * All reads consult this structure first — a key not present here
 * does not exist in the store regardless of what is on disk.
 *
 * <p><b>Thread safety:</b> all public methods are thread-safe via an internal
 * {@link ReentrantReadWriteLock}. Multiple readers proceed concurrently.
 * Writers (put, remove) hold an exclusive lock.
 *
 * <p>Note: uses {@link ByteArrayKey} as the map key to ensure that two
 * byte arrays with identical contents map to the same entry. Do NOT replace
 * this with a raw {@code byte[]} key — it will silently break all lookups.
 */
public final class KeyDir {

    private final HashMap<ByteArrayKey, KeyEntry> map;
    private final ReentrantReadWriteLock lock;

    /**
     * Constructs an empty KeyDir.
     */
    public KeyDir() {
        this.map  = new HashMap<>();
        this.lock = new ReentrantReadWriteLock();
    }

    /**
     * Inserts or replaces the KeyEntry for the given key.
     *
     * @param key   raw key bytes; must not be null
     * @param entry the new location for this key; must not be null
     */
    public void put(byte[] key, KeyEntry entry) {
        lock.writeLock().lock();
        try {
            map.put(new ByteArrayKey(key), entry);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns the KeyEntry for the given key, or {@code null} if the key does not exist.
     *
     * <p>Returns null rather than Optional to keep the hot read path allocation-free.
     * Callers must null-check the result.
     *
     * @param key raw key bytes; must not be null
     * @return KeyEntry, or null if absent
     */
    public KeyEntry get(byte[] key) {
        lock.readLock().lock();
        try {
            return map.get(new ByteArrayKey(key));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Removes the entry for the given key (called when a DELETE record is processed).
     *
     * @param key raw key bytes; must not be null
     */
    public void remove(byte[] key) {
        lock.writeLock().lock();
        try {
            map.remove(new ByteArrayKey(key));
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns the number of live keys currently tracked.
     *
     * @return live key count
     */
    public int size() {
        lock.readLock().lock();
        try {
            return map.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns a snapshot copy of all entries.
     * Used by {@link com.bitcask.merge.Merger} and
     * {@link com.bitcask.recovery.StartupLoader}.
     *
     * @return unmodifiable copy of the current map
     */
    public Map<ByteArrayKey, KeyEntry> snapshot() {
        lock.readLock().lock();
        try {
            return Collections.unmodifiableMap(new HashMap<>(map));
        } finally {
            lock.readLock().unlock();
        }
    }
}
```

---

## 6. Phase 3 — Public API

---

### `BitcaskException.java`
**Responsibility:** Single unchecked exception type for all Bitcask errors.

```java
/**
 * Unchecked exception thrown for all Bitcask I/O and state errors.
 *
 * <p>Using a single exception type keeps the API clean — callers catch
 * {@code BitcaskException} and decide how to handle it, rather than juggling
 * multiple checked exception types.
 *
 * <p>Always includes a message describing what failed and where.
 * Wrap underlying {@link IOException} instances as the cause.
 */
public final class BitcaskException extends RuntimeException {

    /**
     * Constructs a BitcaskException with a message.
     *
     * @param message description of what went wrong
     */
    public BitcaskException(String message) {
        super(message);
    }

    /**
     * Constructs a BitcaskException with a message and underlying cause.
     *
     * @param message description of what went wrong
     * @param cause   the underlying exception (e.g. IOException)
     */
    public BitcaskException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

---

### `BitcaskConfig.java`
**Responsibility:** All tunables in one place, constructed via a Builder.
**Pattern:** Builder
**Why Builder and not a plain constructor:** BitcaskConfig has 4+ fields.
A plain constructor `new BitcaskConfig(512MB, false, 0.5, true)` is unreadable
— what does `false` mean? The Builder makes each field named and optional.

```java
/**
 * Immutable configuration for a {@link BitcaskStore}.
 *
 * <p>Construct via the {@link Builder}:
 * <pre>{@code
 * BitcaskConfig config = new BitcaskConfig.Builder()
 *     .maxFileSize(512 * 1024 * 1024L)
 *     .syncOnWrite(false)
 *     .mergeDeadRatioThreshold(0.5)
 *     .backgroundMergeEnabled(true)
 *     .build();
 * }</pre>
 */
public final class BitcaskConfig {

    /** Default max data file size before rotation: 1 GB */
    public static final long DEFAULT_MAX_FILE_SIZE = 1024L * 1024 * 1024;

    /** Default dead-key ratio threshold that triggers a merge: 50% */
    public static final double DEFAULT_MERGE_THRESHOLD = 0.5;

    private final long    maxFileSize;
    private final boolean syncOnWrite;
    private final double  mergeDeadRatioThreshold;
    private final boolean backgroundMergeEnabled;

    private BitcaskConfig(Builder builder) {
        this.maxFileSize               = builder.maxFileSize;
        this.syncOnWrite               = builder.syncOnWrite;
        this.mergeDeadRatioThreshold   = builder.mergeDeadRatioThreshold;
        this.backgroundMergeEnabled    = builder.backgroundMergeEnabled;
    }

    /** @return max file size in bytes before the active file is rotated */
    public long getMaxFileSize()             { return maxFileSize; }

    /** @return true if fsync is called after every write */
    public boolean isSyncOnWrite()           { return syncOnWrite; }

    /** @return dead-key ratio (0.0–1.0) above which a merge is triggered */
    public double getMergeDeadRatioThreshold() { return mergeDeadRatioThreshold; }

    /** @return true if a background thread should check merge eligibility */
    public boolean isBackgroundMergeEnabled() { return backgroundMergeEnabled; }

    /**
     * Builder for {@link BitcaskConfig}.
     */
    public static final class Builder {

        private long    maxFileSize             = DEFAULT_MAX_FILE_SIZE;
        private boolean syncOnWrite             = false;
        private double  mergeDeadRatioThreshold = DEFAULT_MERGE_THRESHOLD;
        private boolean backgroundMergeEnabled  = true;

        /**
         * Sets the maximum data file size before rotation.
         *
         * @param bytes max file size in bytes; must be positive
         * @return this builder
         */
        public Builder maxFileSize(long bytes) {
            this.maxFileSize = bytes;
            return this;
        }

        /**
         * If true, fsync is called after every write.
         * Safer but slower. Default: false.
         *
         * @param sync true to sync after every write
         * @return this builder
         */
        public Builder syncOnWrite(boolean sync) {
            this.syncOnWrite = sync;
            return this;
        }

        /**
         * Sets the dead-key ratio threshold for triggering compaction.
         *
         * @param threshold value between 0.0 and 1.0
         * @return this builder
         */
        public Builder mergeDeadRatioThreshold(double threshold) {
            this.mergeDeadRatioThreshold = threshold;
            return this;
        }

        /**
         * Enables or disables the background merge thread.
         *
         * @param enabled true to run periodic background merge checks
         * @return this builder
         */
        public Builder backgroundMergeEnabled(boolean enabled) {
            this.backgroundMergeEnabled = enabled;
            return this;
        }

        /**
         * Builds and returns the {@link BitcaskConfig}.
         *
         * @return configured BitcaskConfig instance
         */
        public BitcaskConfig build() {
            return new BitcaskConfig(this);
        }
    }
}
```

---

### `Bitcask.java`
**Responsibility:** The public contract of the store.
**Pattern:** Facade — users depend only on this interface.

```java
/**
 * Public API for a Bitcask key-value store.
 *
 * <p>Obtain a store instance via {@link BitcaskStore#open(Path, BitcaskConfig)}.
 * Always use try-with-resources to ensure proper cleanup:
 *
 * <pre>{@code
 * try (Bitcask store = BitcaskStore.open(path, new BitcaskConfig.Builder().build())) {
 *     store.put("hello".getBytes(), "world".getBytes());
 *     byte[] val = store.get("hello".getBytes());
 *     store.delete("hello".getBytes());
 * }
 * }</pre>
 */
public interface Bitcask extends AutoCloseable {

    /**
     * Stores a key-value pair.
     * If the key already exists, the previous value is superseded.
     *
     * @param key   non-null, non-empty key bytes
     * @param value non-null value bytes
     * @throws BitcaskException if the write fails
     */
    void put(byte[] key, byte[] value);

    /**
     * Retrieves the value for the given key.
     *
     * @param key key to look up; must not be null
     * @return value bytes, or null if the key does not exist
     * @throws BitcaskException if the disk read fails
     */
    byte[] get(byte[] key);

    /**
     * Deletes a key by appending a tombstone record.
     * The key is removed from the index immediately.
     * The tombstone is removed from disk during the next merge.
     *
     * @param key key to delete; must not be null
     * @throws BitcaskException if the write fails
     */
    void delete(byte[] key);

    /**
     * Triggers compaction of immutable data files.
     * Safe to call while reads and writes are in progress.
     *
     * @throws BitcaskException if the merge fails
     */
    void merge();

    /**
     * Forces any pending writes to sync to disk (fsync).
     *
     * @throws BitcaskException if the sync fails
     */
    void sync();

    /**
     * Returns a snapshot of all live keys in the store.
     *
     * @return set of all current key byte arrays
     */
    Set<byte[]> listKeys();

    /**
     * Returns a point-in-time snapshot of store statistics.
     *
     * @return current StoreStats
     */
    StoreStats stats();

    /**
     * Closes the store: flushes pending writes, syncs to disk,
     * shuts down background threads, and releases the write lock file.
     */
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
 * Strategy interface for deciding whether a Bitcask store should run compaction.
 *
 * <p>Implement this interface to provide custom merge-trigger logic.
 * The default implementation is {@link SizeTieredMergePolicy}.
 *
 * <p>Example — always merge:
 * <pre>{@code
 * MergePolicy alwaysMerge = stats -> true;
 * }</pre>
 */
public interface MergePolicy {

    /**
     * Returns true if the store should run a merge based on current statistics.
     *
     * @param stats current point-in-time store statistics; never null
     * @return true if merge should proceed
     */
    boolean shouldMerge(StoreStats stats);
}
```

### `SizeTieredMergePolicy.java`
**Responsibility:** Trigger merge when dead-key ratio exceeds a threshold.

```java
/**
 * A {@link MergePolicy} that triggers compaction when the fraction of dead
 * (overwritten or deleted) records on disk exceeds a configured threshold.
 *
 * <p>For example, with a threshold of 0.5: if more than 50% of the bytes
 * on disk belong to dead records, a merge is triggered.
 */
public final class SizeTieredMergePolicy implements MergePolicy {

    private final double deadRatioThreshold;

    /**
     * Constructs a SizeTieredMergePolicy.
     *
     * @param deadRatioThreshold fraction of dead bytes (0.0–1.0) that triggers merge
     * @throws IllegalArgumentException if threshold is not in [0.0, 1.0]
     */
    public SizeTieredMergePolicy(double deadRatioThreshold) {
        if (deadRatioThreshold < 0.0 || deadRatioThreshold > 1.0) {
            throw new IllegalArgumentException("Threshold must be between 0.0 and 1.0");
        }
        this.deadRatioThreshold = deadRatioThreshold;
    }

    /**
     * Returns true if the current dead-byte ratio exceeds the configured threshold.
     *
     * @param stats current store statistics
     * @return true if merge should proceed
     */
    @Override
    public boolean shouldMerge(StoreStats stats) {
        return stats.getDeadByteRatio() >= deadRatioThreshold;
    }
}
```

---

## 8. Phase 5 — Crash Recovery

### `StartupLoader.java`

```java
/**
 * Rebuilds the {@link KeyDir} when a Bitcask store is opened.
 *
 * <p>Recovery strategy (executed in this order):
 * <ol>
 *   <li>List all .data files in the store directory, sorted oldest-first</li>
 *   <li>For each file: if a .hint file exists, read the hint file (fast path)</li>
 *   <li>If no hint file: replay the raw .data file record by record (slow path)</li>
 *   <li>The active (newest) file is always replayed from raw — it has no hint</li>
 *   <li>On CRC failure: skip the record and stop reading that file (partial write)</li>
 * </ol>
 *
 * <p>After this method returns, the KeyDir reflects the latest state of all
 * committed writes up to the last successful record in each file.
 */
public final class StartupLoader {

    /**
     * Loads all data files in the store directory and returns a fully populated KeyDir.
     *
     * @param directory the Bitcask store directory; must exist and be readable
     * @return populated KeyDir ready for reads and writes
     * @throws BitcaskException if the directory or files cannot be read
     */
    public KeyDir load(Path directory) { /* ... */ }
}
```

---

## 9. Phase 6 — Concurrency

No new classes — this phase adds thread safety inside `BitcaskStore`.

| What | How | Why |
|---|---|---|
| KeyDir reads | `ReentrantReadWriteLock` read-lock | Many threads read simultaneously |
| KeyDir + file writes | `ReentrantReadWriteLock` write-lock | Single writer — no torn index state |
| Append offset | `AtomicLong` in DataFile | Track write position without a lock |
| Background merge | `ScheduledExecutorService` | Checks MergePolicy every N seconds |
| File rotation | Write-lock held during swap | No reader references a file being closed |

---

## 10. Phase 7 — Config & Lifecycle

`BitcaskStore.open(Path, BitcaskConfig)` is the single entry point. It must:

1. Acquire an exclusive lock file (`bitcask.lock`) — throw if already held
2. Run `StartupLoader.load()` to rebuild the KeyDir
3. Open or create the active data file
4. Start the background merge scheduler if enabled in config
5. Return the store ready for use

`close()` must:

1. Flush and fsync the active data file
2. Shut down the background executor (with a timeout)
3. Release the lock file

---

## 11. Phase 8 — Observability

### `StoreStats.java`

```java
/**
 * A point-in-time snapshot of store health metrics.
 * Returned by {@link Bitcask#stats()} and consumed by {@link MergePolicy}.
 */
public final class StoreStats {

    private final int    liveKeyCount;
    private final int    totalFileCount;
    private final long   deadKeyCount;
    private final double deadByteRatio;
    private final long   totalDiskBytes;

    /**
     * Constructs a StoreStats snapshot.
     *
     * @param liveKeyCount   number of keys currently in the index
     * @param totalFileCount number of data files on disk (active + immutable)
     * @param deadKeyCount   estimated number of superseded or deleted records on disk
     * @param deadByteRatio  fraction of disk bytes occupied by dead records (0.0–1.0)
     * @param totalDiskBytes total bytes used by all data files
     */
    public StoreStats(int liveKeyCount, int totalFileCount,
                      long deadKeyCount, double deadByteRatio, long totalDiskBytes) {
        this.liveKeyCount   = liveKeyCount;
        this.totalFileCount = totalFileCount;
        this.deadKeyCount   = deadKeyCount;
        this.deadByteRatio  = deadByteRatio;
        this.totalDiskBytes = totalDiskBytes;
    }

    /** @return number of live keys in the index */
    public int    getLiveKeyCount()   { return liveKeyCount;   }

    /** @return total number of data files on disk */
    public int    getTotalFileCount() { return totalFileCount; }

    /** @return estimated dead record count */
    public long   getDeadKeyCount()   { return deadKeyCount;   }

    /** @return fraction of disk space used by dead records */
    public double getDeadByteRatio()  { return deadByteRatio;  }

    /** @return total bytes consumed by all data files */
    public long   getTotalDiskBytes() { return totalDiskBytes; }

    @Override
    public String toString() {
        return "StoreStats{"
            + "liveKeys=" + liveKeyCount
            + ", files=" + totalFileCount
            + ", deadRatio=" + String.format("%.2f", deadByteRatio)
            + ", diskBytes=" + totalDiskBytes
            + "}";
    }
}
```

---

## 12. Design Patterns Map

| Pattern | Where | Why it appears |
|---|---|---|
| **Facade** | `Bitcask` interface + `BitcaskStore` | Hide all subsystems behind 5 methods |
| **Strategy** | `MergePolicy` + `SizeTieredMergePolicy` | Swap compaction logic without touching Merger |
| **Factory** | `DataFileFactory` | Centralise file creation and naming rules |
| **Builder** | `BitcaskConfig.Builder` | Named, optional construction of multi-field config |
| **Template Method** | `LogRecord` base + `PutRecord`/`DeleteRecord` | Common encode/decode logic in base, value behaviour in subclass |
| **Iterator** (implicit) | Segment scan in `Merger` and `StartupLoader` | Walk records without exposing file internals |

---

## 13. Java Concepts Map

| Concept | Class | What you learn |
|---|---|---|
| Abstract class + inheritance | `LogRecord`, `PutRecord`, `DeleteRecord` | When inheritance is appropriate; why final matters |
| `enum` | `RecordType` | Type-safe constants; fromCode() pattern |
| `ByteBuffer` + `FileChannel` | `DataFile`, `LogRecord` | Zero-copy I/O, buffer positions/limits/capacity |
| `CRC32` | `LogRecord.encode/decode` | Data integrity without hashing overhead |
| `force(true)` | `DataFile.sync()` | What fsync actually does at the OS level |
| `AtomicLong` | `DataFile` write offset | Lock-free counter, happens-before guarantee |
| `HashMap` + `ByteArrayKey` | `KeyDir` | Why byte[] breaks HashMap; wrapper pattern |
| `ReentrantReadWriteLock` | `KeyDir`, `BitcaskStore` | Read-heavy workload concurrency |
| `ScheduledExecutorService` | `BitcaskStore` background merge | Daemon threads, shutdown lifecycle |
| `Path` / `Files` NIO2 | `DataFileFactory`, `StartupLoader` | Modern file I/O; never use java.io.File |
| `AutoCloseable` + try-with-resources | `DataFile`, `BitcaskStore` | Resource leak prevention |
| Builder pattern (manual) | `BitcaskConfig.Builder` | Named parameters in Java; telescoping constructor problem |

---

## 14. Known Pain Points

These are the drawbacks you will feel during this phase.
Each one is intentional — it is teaching you something.

| Pain you will feel | What it is teaching you |
|---|---|
| Writing `equals()`, `hashCode()`, `toString()` manually in `KeyEntry` and `StoreStats` | Why records exist |
| Checking `isTombstone()` in every code path that processes a record | Why sealed types + exhaustive switch exist |
| Null-checking `get()` return value everywhere | Why `Optional<T>` exists |
| Getters on every field of every class | Why records eliminate boilerplate |
| The abstract class forcing you to override `getValue()` | Why sealed interface + pattern matching is cleaner |

When you have felt all five of these, you are ready to refactor to the modern Java design with full understanding.

---

## 15. Javadoc Standards

```java
/**
 * One-line summary ending with a period.
 *
 * <p>Optional longer explanation. Use {@code inline code} for
 * identifiers and {@link ClassName} for cross-references.
 *
 * @param paramName  what it is and any constraints (null? empty? range?)
 * @return           what is returned and when it can be null/absent
 * @throws BitcaskException  under what conditions this is thrown
 * @see RelatedClass
 */
```

Rules:
- Every `public` class has a class-level Javadoc
- Every `public` and `protected` method has a Javadoc
- `@param` for every parameter including constraints
- `@return` always present unless `void`
- `@throws` for every thrown exception including unchecked ones
- Use `<pre>{@code ... }</pre>` for multi-line code examples in Javadoc

---

## 16. Testing Strategy

| Test type | What to test | Tool |
|---|---|---|
| Unit | `LogRecord` encode → decode roundtrip for PUT | JUnit 5 |
| Unit | `LogRecord` encode → decode roundtrip for DELETE | JUnit 5 |
| Unit | `LogRecord` CRC corruption detection | JUnit 5 |
| Unit | `ByteArrayKey` equals/hashCode with identical byte contents | JUnit 5 |
| Unit | `KeyDir` put / get / remove / size | JUnit 5 |
| Unit | `KeyDir` concurrent reads do not block each other | JUnit 5 |
| Unit | `DataFile` append returns correct offset | JUnit 5 + `@TempDir` |
| Unit | `DataFile` readAt returns same record that was appended | JUnit 5 + `@TempDir` |
| Unit | `DataFile` append to read-only file throws BitcaskException | JUnit 5 |
| Unit | `SizeTieredMergePolicy` triggers above threshold, not below | JUnit 5 |
| Integration | `BitcaskStore` put → get → delete → get(null) | JUnit 5 + `@TempDir` |
| Integration | `BitcaskStore` survives restart (close + reopen, data intact) | JUnit 5 + `@TempDir` |
| Integration | Merge reclaims space, reads still return correct values | JUnit 5 + `@TempDir` |
| Benchmark (Phase 8) | Write throughput ops/sec, read latency p50/p99 | JMH |

**Key rule:** never use real file paths in tests — always use `@TempDir`.
Each test gets a fresh isolated directory that is cleaned up automatically.

---

## 17. What This Unlocks Next

```
Bitcask (this project)
    │
    ├──► Refactor to modern Java     sealed interface + records + pattern matching
    │                                → understand why Java 21 features exist
    │
    ├──► LSM-Tree engine             Add MemTable + SSTable + level compaction
    │                                → understand LevelDB, RocksDB, Cassandra
    │
    ├──► Raft consensus layer        Wrap BitcaskStore as a replicated state machine
    │                                → replicated KV cluster (NileDB Phase 2)
    │
    ├──► Distributed KV store        Add consistent hashing + replication factor
    │                                → understand DynamoDB, Riak
    │
    └──► Mini SQL engine             Use Bitcask as the heap file
                                     → add a B-Tree index on top
                                     → understand how PostgreSQL stores rows
```

Every concept in this project — append-only log, in-memory index, compaction,
crash recovery, single-writer concurrency — appears verbatim in Kafka,
Cassandra, RocksDB, and Apache Iceberg. You are not building a toy.
You are building the foundation.
