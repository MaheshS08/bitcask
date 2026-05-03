package com.bitcask.storage;

/**
 * Base class for all Bitcask log entries written to disk.
 *
 * <p>Binary layout on disk (big-endian):
 * <pre>
 * ┌─────────┬────────┬───────────┬──────────┬────────────┬─────────┬─────────┐
 * │   crc   │  type  │ timestamp │ key_size │ value_size │   key   │  value  │
 * │ 4 bytes │ 1 byte │  8 bytes  │  2 bytes │  4 bytes   │ N bytes │ M bytes │
 * └─────────┴────────┴───────────┴──────────┴────────────┴─────────┴─────────┘
 * </pre>
 *
 * <p>Header is 19 bytes fixed. CRC covers everything from {@code type} onward.
 * {@code value_size} is 0 and no value bytes are written for DELETE records.
 *
 * <p>Do not instantiate this class directly.
 * Use {@link PutRecord} or {@link DeleteRecord}.
 *
 * @see PutRecord
 * @see DeleteRecord
 */
public abstract class LogRecord {
}
