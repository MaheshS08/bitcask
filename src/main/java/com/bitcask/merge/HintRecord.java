/* (C)2026 */
package com.bitcask.merge;

import com.bitcask.exception.BitcaskException;
import com.bitcask.storage.LogRecord;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * <p><b>Hint record format (no CRC — hint corruption is non-fatal):</b></p>
 * <pre>
 * ┌───────────┬──────────┬────────────┬──────────┬─────────┐
 * │ timestamp │ key_size │ value_size │  offset  │   key   │
 * │  8 bytes  │  2 bytes │  4 bytes   │  8 bytes │ N bytes │
 * └───────────┴──────────┴────────────┴──────────┴─────────┘
 * No type byte. Only live keys written — tombstones excluded.
 * If hint file is corrupt, StartupLoader falls back to data file replay.
 *
 * See {@link HintRecord}
 * </pre>
 */
public class HintRecord {

    /** Fixed header size in bytes:  ts(8) + ksz(2) + vsz(4). */
    public static final int HINT_HEADER_SIZE = 22;

    private final long offset;
    private final long timestamp;
    private final int valueSize;
    private final byte[] key;

    public HintRecord(long timestamp, byte[] keySize, int valueSize, long offset, byte[] key) {
        this.offset = offset;
        this.valueSize = valueSize;
        this.key = key;
        this.timestamp = timestamp;
    }

    /**
     * Decodes a record from the given ByteBuffer, starting at its current position.
     * <p>Layout: [ crc(4) | timestamp(8) | key_size(2) | value_size(4) | key | value ]</p>
     *
     * @param buffer source buffer positioned at the start of a record header
     * @return decoded {@link HintRecord}
     * @throws BitcaskException if the CRC does not match (corrupt or partial record)
     */
    public static HintRecord decode(ByteBuffer buffer) {
        int startPosition = buffer.position();
        int storedCrc = buffer.getInt();
        long timestamp = buffer.getLong();
        int keySize = Short.toUnsignedInt(buffer.getShort());
        int valueSize = buffer.getInt();
        long offset = buffer.getLong();
        byte[] key = new byte[keySize];
        buffer.get(key);
        byte[] value = new byte[valueSize];
        buffer.get(value);
        return new HintRecord(timestamp, key, valueSize, offset, key);
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
        buffer.putLong(this.timestamp);
        buffer.putShort((short) this.key.length);
        buffer.putInt(this.valueSize);
        buffer.putLong(this.offset);
        buffer.put(key);
        return buffer.array();
    }

    /**
     * Returns totalSize required for storing and encoding {@link LogRecord} to I/O
     *
     * @return totalSize to allocate for ByteBuffer
     */
    private int totalSize() {
        return HINT_HEADER_SIZE + key.length + valueSize;
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
        return "HintRecord{"
                + "ts="
                + timestamp
                + ", key="
                + new String(key, StandardCharsets.UTF_8)
                + ", keyLen="
                + key.length
                + ", valueLen="
                + valueSize
                + ", offset="
                + offset
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
