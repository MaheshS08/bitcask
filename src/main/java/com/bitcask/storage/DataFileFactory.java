/* (C)2026 */
package com.bitcask.storage;

import java.nio.file.Path;

/**
 * Factory for creating and opening {@link DataFile} instances.
 *
 * <p>File naming convention: {@code <fileId>.data} where {@code fileId} is
 * {@link System#currentTimeMillis()} at creation time, zero-padded to 20 digits.
 * This ensures lexicographic order equals chronological order.
 *
 * <p>Example filename: {@code 00000001714000000000.data}
 *
 * <p>Hint files produced during merge use the same fileId with extension
 * {@code .hint}: {@code 00000001714000000000.hint}
 */
public class DataFileFactory {

    private static final String DATA_EXTENSION = ".data";

    /** Utility class — no instances. */
    private DataFileFactory() {
    }

    /**
     * Creates a new, empty writable data file in the given directory.
     * The file ID is set to {@link System#currentTimeMillis()}.
     *
     * @param directory the Bitcask store directory; must exist and be writable
     * @return a new writable DataFile ready for appending
     * @throws BitcaskException if the file cannot be created
     */
    public static DataFile createNew(Path directory) {
        long fileId = System.currentTimeMillis();
        String fileName = String.format("%020d%s", fileId, DATA_EXTENSION);
        return DataFileUtil.createNewDataFile(directory);
    }
}
