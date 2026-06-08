/* (C)2026 */
package com.bitcask.storage;

import com.bitcask.exception.BitcaskException;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

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
public class DataFile implements Closeable {

    private final long fileId;
    private final Path path;
    private final FileChannel channel;
    private final boolean readOnly;
    private final AtomicLong writeOffset;

    /**
     * Package-private constructor — use {@link DataFileFactory} to create instances.
     *
     * @param fileId   numeric file ID (timestamp-based)
     * @param path     path to the .data file on disk
     * @param channel  open FileChannel — writable if active, read-only if immutable
     * @param readOnly true if this file is immutable (no appends allowed)
     */
    public DataFile(long fileId, Path path, FileChannel channel, boolean readOnly) {
        this.fileId = fileId;
        this.path = path;
        this.channel = channel;
        this.readOnly = readOnly;
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
     * @throws BitcaskException     if this file is read-only or the write fails
     * @throws NullPointerException if record is null
     */
    public long append(LogRecord record) {
        if (readOnly) {
            throw BitcaskException.readOnlyFile(path);
        }
        if (record == null) {
            throw new NullPointerException("LogRecord cannot be null");
        }
        // Implementation of appending the record to the file goes here.
        byte[] recordBytes = record.encode();
        long offset = writeOffset.get();
        ByteBuffer buffer = ByteBuffer.wrap(recordBytes);
        try {
            while (buffer.hasRemaining()) {
                channel.write(buffer, offset + (recordBytes.length - buffer.remaining()));
            }
            writeOffset.addAndGet(recordBytes.length);
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
        // Implementation of reading a record from the file at the given offset goes here.

        ByteBuffer headerBuffer = ByteBuffer.allocate(LogRecord.HEADER_SIZE);
        try {
            int bytesRead = channel.read(headerBuffer, offset);
            if (bytesRead < LogRecord.HEADER_SIZE) {
                throw new BitcaskException(
                        "Offset out of range or incomplete record at " + path + ":" + offset);
            }
            int keySize = Short.toUnsignedInt(headerBuffer.getShort(12)); // key_size is at byte 12
            int valueSize = headerBuffer.getInt(14); // value_size is at byte 14
            ByteBuffer recordBuffer =
                    ByteBuffer.allocate(LogRecord.HEADER_SIZE + keySize + valueSize);
            channel.read(recordBuffer, offset);
            return LogRecord.decode(recordBuffer);
        } catch (IOException e) {
            throw new BitcaskException(
                    "Failed to read record header from " + path + " at offset " + offset, e);
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

            }
        }
        channel.close();
    }
}
