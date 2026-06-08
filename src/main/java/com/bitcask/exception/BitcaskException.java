/* (C)2026 */
package com.bitcask.exception;

import java.nio.file.Path;

public class BitcaskException extends RuntimeException {
    public BitcaskException(String message) {
        super(message);
    }

    public BitcaskException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Thrown when a CRC validation is failed
     *
     * @param position position of CRC
     */
    public static BitcaskException crcMismatch(int position) {
        return new BitcaskException(
                "CRC mismatch at buffer position " + position + " — corrupt or partial record");
    }

    /**
     * Thrown when a key is null or empty.
     * Keys must be non-null and contain at least one byte.
     */
    public static BitcaskException nullOrEmpty() {
        return new BitcaskException("Key cannot be null or empty");
    }

    /**
     * Thrown when a key is null or empty.
     * Keys must be non-null and contain at least one byte.
     */
    public static BitcaskException nullValue() {
        return new BitcaskException("Value cannot be null");
    }

    /**
     * Thrown when a key exceeds the maximum allowed size.
     * key_size is stored as an unsigned short on disk — max 65535 bytes.
     *
     * @param actualSize  the size of the key that was passed
     * @param maximumSize the maximum allowed key size (65535)
     */
    public static BitcaskException keyTooLarge(int actualSize, int maximumSize) {
        return new BitcaskException(
                "Key too large: "
                        + actualSize
                        + " bytes. Maximum allowed size is "
                        + maximumSize
                        + " bytes (unsigned short limit).");
    }

    /**
     * Thrown when a write is attempted on a read-only data file.
     *
     * @param path the path of the read-only file
     */
    public static BitcaskException readOnlyFile(Path path) {
        return new BitcaskException("File is read-only and cannot be written to: " + path);
    }
}
