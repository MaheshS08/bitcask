/* (C)2026 */
package com.bitcask.Exception;

public class BitcaskException extends RuntimeException {
    public BitcaskException(String message) {
        super(message);
    }

    public BitcaskException(String message, Throwable cause) {
        super(message, cause);
    }
}
