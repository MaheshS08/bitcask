package com.bitcask.index;


/**
 * An entry in the {@link KeyDir} pointing to the on-disk location of a key's value.
 *
 * <p>All fields are final — a KeyEntry is never mutated after creation.
 * When a key is overwritten, the old KeyEntry is replaced entirely in the KeyDir.
 *
 * <p>Fields map directly to the paper's keydir structure:
 * <ul>
 *   <li>{@code fileId}    → which .data file contains this value</li>
 *   <li>{@code offset}    → byte offset within that file (value_pos in paper)</li>
 *   <li>{@code valueSize} → size of the value in bytes (value_sz in paper)</li>
 *   <li>{@code timestamp} → write timestamp; used in merge to resolve conflicts</li>
 * </ul>
 */
public class KeyEntry {
    private final long fileId;
    private final long offset;
    private final int valueSize;
    private final long timestamp;

    /**
     * Constructs a KeyEntry.
     *
     * @param fileId    ID of the data file containing this value
     * @param offset    byte offset in that file where the record starts
     * @param valueSize size of the value in bytes
     * @param timestamp write timestamp in Unix epoch millis
     */
    public KeyEntry(long fileId, long offset, int valueSize, long timestamp) {
        this.fileId = fileId;
        this.offset = offset;
        this.valueSize = valueSize;
        this.timestamp = timestamp;
    }

    /** @return ID of the data file containing this value */
    public long getFileId() {
        return fileId;
    }

    /** @return byte offset in the data file where the record starts */
    public long getOffset() {
        return offset;
    }

    /** @return size of the value in bytes */
    public int getValueSize() {
        return valueSize;
    }

    /** @return write timestamp in Unix epoch millis */
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "KeyEntry{"
                + "fileId=" + fileId
                + ", offset=" + offset
                + ", valueSize=" + valueSize
                + ", timestamp=" + timestamp
                + "}";
    }
}
