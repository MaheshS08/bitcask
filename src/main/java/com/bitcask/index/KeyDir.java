package com.bitcask.index;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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
public class KeyDir {
    private final HashMap<ByteArrayKey, KeyEntry> map;
    private final ReentrantReadWriteLock lock;

    public KeyDir() {
        this.map = new HashMap<>();
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
