/* (C)2026 */
package com.bitcask.storage;

import com.bitcask.Exception.BitcaskException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * A single entry in the Bitcask log.
 *
 * <p>Binary layout on disk (big-endian):
 * <pre>
 * ┌────────┬───────────┬──────────┬───────────┬─────────┬───────────┐
 * │ crc    │ timestamp │ key_size │ value_size│  key    │  value    │
 * │ 4 bytes│  8 bytes  │  2 bytes │  4 bytes  │ N bytes │  M bytes  │
 * └────────┴───────────┴──────────┴───────────┴─────────┴───────────┘
 * </pre>
 * Total header = 18 bytes. CRC covers everything after the CRC field.
 *
 * <p>Notes:
 * <ul>
 *   <li>CRC is computed over everything except itself.</li>
 *   <li>value_size == 0 indicates a tombstone (delete).</li>
 *   <li>Entries are append-only and never updated in place.</li>
 * </ul>
 */
public final class LogRecord {

    /** Fixed header size in bytes: crc(4) + ts(8) + ksz(2) + vsz(4). */
    public static final int HEADER_SIZE = 18;

    public static final int CRC_SIZE = 4;

    private final long timestamp;
    private final byte[] key;
    private final byte[] value;

    public LogRecord(long timestamp, byte[] key, byte[] value) {
        if (key == null || key.length == 0) {
            throw BitcaskException.nullOrEmpty();
        }
        if (key.length > 65535) {
            throw BitcaskException.keyTooLarge(key.length, 65535);
        }
        if (value == null) {
            throw BitcaskException.nullValue();
        }
        this.timestamp = timestamp;
        this.key = key;
        this.value = value;
    }

    /**
     * Decodes a record from the given ByteBuffer, starting at its current position.
     * <p>Layout: [ crc(4) | timestamp(8) | key_size(2) | value_size(4) | key | value ]</p>
     *
     * @param buffer source buffer positioned at the start of a record header
     * @return decoded {@link LogRecord}
     * @throws BitcaskException if the CRC does not match (corrupt or partial record)
     */
    public static LogRecord decode(ByteBuffer buffer) {
        int startPosition = buffer.position();
        int storedCrc = buffer.getInt();
        long timestamp = buffer.getLong();
        int keySize = Short.toUnsignedInt(buffer.getShort());
        int valueSize = buffer.getInt();
        byte[] key = new byte[keySize];
        buffer.get(key);
        byte[] value = new byte[valueSize];
        buffer.get(value);
        CRC32 checksum = new CRC32();
        int totalRecordSize = HEADER_SIZE + keySize + valueSize;
        checksum.update(buffer.array(), startPosition + CRC_SIZE, totalRecordSize - CRC_SIZE);
        if (storedCrc != (int) checksum.getValue()) {
            throw BitcaskException.crcMismatch(startPosition);
        }
        return new LogRecord(timestamp, key, value);
    }

    /**
     * Returns the Unix epoch millis timestamp of this record.
     *
     * @return timestamp in milliseconds
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Returns the key bytes for this record.
     *
     * @return key bytes; never null or empty
     */
    public byte[] getKey() {
        return key;
    }

    /**
     * Returns the value bytes for this record.
     * Returns an empty array for tombstone (delete) records.
     *
     * @return value bytes; never null, empty array for deletes
     */
    public byte[] getValue() {
        return value;
    }

    /**
     * Returns {@code true} if this record represents a deleted key.
     */
    public boolean isTombstone() {
        return value.length == 0;
    }

    /**
     * Encodes this record into a byte array suitable for appending to a data file.
     *
     * <p>Layout: [ crc(4) | timestamp(8) | key_size(2) | value_size(4) | key | value ]</p>
     * CRC is computed over all bytes from type onward.
     *
     * @return fully encoded record bytes including header and CRC
     */
    public byte[] encode() {
        // allocate buffer for header + key + value
        ByteBuffer buffer = ByteBuffer.allocate(totalSize());

        // skip CRC for now, will fill in after calculating
        buffer.position(CRC_SIZE);
        buffer.putLong(this.timestamp);
        buffer.putShort((short) this.key.length);
        buffer.putInt(this.value.length);
        buffer.put(key);
        buffer.put(value);

        // Calculate CRC32
        CRC32 checksum = new CRC32();
        checksum.update(buffer.array(), CRC_SIZE, totalSize() - CRC_SIZE);
        buffer.putInt(0, (int) checksum.getValue());
        return buffer.array();
    }

    /**
     * Returns totalSize required for storing and encoding {@link LogRecord} to I/O
     *
     * @return totalSize to allocate for ByteBuffer
     */
    private int totalSize() {
        return HEADER_SIZE + key.length + value.length;
    }

    /**
     * Returns a human-readable representation of this record for debugging purposes.
     *
     * <p>Format:
     * <pre>
     * LogRecord{ts=1714000000000, key="user:123", keyLen=8, valueLen=42, type=PUT}
     * LogRecord{ts=1714000001000, key="user:123", keyLen=8, valueLen=0, type=DELETE}
     * </pre>
     *
     * <p>The key is displayed as a UTF-8 string if all bytes are printable ASCII,
     * otherwise as a hex string prefixed with {@code hex:}.
     * The value bytes are never printed — values may be large or contain
     * sensitive data.
     *
     * <p>type=DELETE is inferred from valueLen=0 since there is no explicit
     * type field in the on-disk format.
     */
    @Override
    public String toString() {
        return "LogRecord{"
                + "ts="
                + timestamp
                + ", key="
                + new String(key, StandardCharsets.UTF_8)
                + ", keyLen="
                + key.length
                + ", valueLen="
                + value.length
                + ",type="
                + (isTombstone() ? "DELETE" : "PUT")
                + "}";
    }

    // Helper methods for toString

    //    private String toReadable(byte[] data) {
    //        if (data == null) return "null";
    //
    //        // Try to interpret as UTF-8, fallback to hex if not printable
    //        String str = new String(data, java.nio.charset.StandardCharsets.UTF_8);
    //
    //        if (isPrintable(str)) {
    //            return str;
    //        }
    //
    //        return toHex(data);
    //    }
    //
    //    private boolean isPrintable(String str) {
    //        for (char c : str.toCharArray()) {
    //            if (Character.isISOControl(c)) {
    //                return false;
    //            }
    //        }
    //        return true;
    //    }
    //
    //    private String toHex(byte[] bytes) {
    //        StringBuilder sb = new StringBuilder();
    //        for (byte b : bytes) {
    //            sb.append(String.format("%02x", b));
    //        }
    //        return sb.toString();
    //    }
}
