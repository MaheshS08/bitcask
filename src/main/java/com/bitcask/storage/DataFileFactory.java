/* (C)2026 */
package com.bitcask.storage;

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
public class DataFileFactory {}
