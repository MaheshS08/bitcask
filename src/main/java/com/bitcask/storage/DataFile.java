/* (C)2026 */
package com.bitcask.storage;

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
public class DataFile {}
