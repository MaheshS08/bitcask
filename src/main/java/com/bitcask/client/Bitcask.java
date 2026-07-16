/* (C)2026 */
package com.bitcask.client;

import com.bitcask.exception.BitcaskException;
import com.bitcask.metrics.StoreStats;
import java.util.Set;

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
