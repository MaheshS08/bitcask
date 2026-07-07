/* (C)2026 */
package com.bitcask.index;

import java.util.Arrays;

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
public class ByteArrayKey {
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ByteArrayKey that)) return false;
        return Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
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
    public String toString() {
        return "ByteArrayKey{" + new String(bytes) + "}";
    }
}
