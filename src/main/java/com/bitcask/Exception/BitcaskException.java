/* (C)2026 */
package com.bitcask.Exception;

public class BitcaskException extends RuntimeException {
    public BitcaskException(String message) {
        super(message);
    }

    public BitcaskException(String message, Throwable cause) {
        super(message, cause);
    }

    public static BitcaskException crcMismatch(int position) {
        return new BitcaskException(
                "CRC mismatch at buffer position " + position + " — corrupt or partial record");
    }
}
