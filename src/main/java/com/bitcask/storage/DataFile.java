/* (C)2026 */
package com.bitcask.storage;

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
public class DataFile {

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
}
