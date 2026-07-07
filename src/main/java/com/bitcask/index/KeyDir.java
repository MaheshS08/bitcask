package com.bitcask.index;

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
}
