/* (C)2026 */
package com.bitcask.storage;

import com.bitcask.exception.BitcaskException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    private DataFileFactory() {}

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
        Path filePath = directory.resolve(fileName);
        try {
            FileChannel channel =
                    FileChannel.open(
                            filePath,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.READ);
            return new DataFile(fileId, filePath, channel, false);
        } catch (IOException e) {
            throw new BitcaskException("Failed to create active data file: " + filePath, e);
        }
    }

    /**
     * Opens an existing data file in read-only mode.
     *
     * @param path the path to the .data file; must exist and be readable
     * @return a read-only DataFile for reading records
     * @throws BitcaskException if the file cannot be opened or is invalid
     */
    public static DataFile openReadOnly(Path path) {
        long fileId = fileIdFromPath(path);
        try {
            FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
            return new DataFile(fileId, path, channel, true);
        } catch (IOException e) {
            throw new BitcaskException("Failed to open data file for reading: " + path, e);
        }
    }

    /**
     * Lists all {@code .data} files in the directory, sorted by file ID (oldest first).
     *
     * @param directory the Bitcask store directory
     * @return sorted list of paths to {@code .data} files; empty if none exist
     * @throws BitcaskException if the directory cannot be read
     */
    public static List<Path> listDataFiles(Path directory) {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.filter(p -> p.toString().endsWith(DATA_EXTENSION))
                    .sorted(Comparator.comparing(DataFileFactory::fileIdFromPath))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new BitcaskException("Failed to list data files in: " + directory, e);
        }
    }

    public static long fileIdFromPath(Path path) {
        String name = path.getFileName().toString();
        String idPart = name.replace(DATA_EXTENSION, "");
        try {
            return Long.parseLong(idPart);
        } catch (NumberFormatException e) {
            throw new BitcaskException("Cannot parse file ID from filename: " + name, e);
        }
    }
}
