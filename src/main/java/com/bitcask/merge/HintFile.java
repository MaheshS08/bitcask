/* (C)2026 */
package com.bitcask.merge;

import com.bitcask.exception.BitcaskException;
import com.bitcask.index.ByteArrayKey;
import com.bitcask.index.KeyDir;
import com.bitcask.index.KeyEntry;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * Write and read .hint files — stripped-down index files
 * written alongside each merged data file for fast startup KeyDir reconstruction.
 *
 * <p>Hint files contain only the key and the offset of each record in the corresponding data file.
 * They allow the KeyDir to be rebuilt quickly without scanning the entire data file.</p>
 *
 * <p><b>Why hint files exist:</b> Without them, startup must replay every data file in full —
 * reading all value bytes — just to rebuild the index. Hint files contain only the fields
 * needed for KeyEntry construction: timestamp, key_size, value_size, offset, key. No value bytes.
 * Startup reads hint files for all merged segments — much faster.</p>
 *
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
public class HintFile {
    public static void readInto(Path hintPath, long fileId, KeyDir keyDir) {
        try (FileChannel channel = FileChannel.open(hintPath, StandardOpenOption.READ)) {

            ByteBuffer headerBuffer = ByteBuffer.allocate(HintRecord.HINT_HEADER_SIZE);

            while (true) {

                // Step 1 — read fixed header
                headerBuffer.clear();
                int bytesRead = channel.read(headerBuffer);
                if (bytesRead == -1) break; // end of file
                headerBuffer.flip();

                // Step 2 — decode fixed fields
                long timestamp = headerBuffer.getLong();
                int keySize = Short.toUnsignedInt(headerBuffer.getShort());
                int valueSize = headerBuffer.getInt();
                long offset = headerBuffer.getLong();

                // Step 3 — read variable key bytes (separate read)
                ByteBuffer keyBuffer = ByteBuffer.allocate(keySize);
                channel.read(keyBuffer);
                keyBuffer.flip();
                byte[] keyBytes = new byte[keySize];
                keyBuffer.get(keyBytes);

                // Step 4 — construct and insert
                KeyEntry keyEntry = new KeyEntry(fileId, offset, valueSize, timestamp);
                keyDir.put(keyBytes, keyEntry);
            }

        } catch (IOException e) {
            throw new BitcaskException("Failed to read hint file: " + hintPath, e);
        }
    }

    public void write(Path hintPath, Map<ByteArrayKey, KeyEntry> entries) {
        // Write each KeyEntry to the hint file in the specified format
        // This is a placeholder for the actual implementation

        try (FileChannel channel =
                FileChannel.open(
                        hintPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); ) {
            for (Map.Entry<ByteArrayKey, KeyEntry> entry : entries.entrySet()) {
                KeyEntry keyEntry = entry.getValue();
                // Write timestamp, key_size, value_size, offset, and key to the channel
                // Actual byte writing logic would go here
                HintRecord hintRecord =
                        new HintRecord(
                                keyEntry.getTimestamp(),
                                entry.getKey().getBytes(),
                                keyEntry.getValueSize(),
                                keyEntry.getOffset(),
                                entry.getKey().getBytes());
                byte[] encodedRecord = hintRecord.encode();
                channel.write(ByteBuffer.wrap(encodedRecord));
            }
        } catch (IOException e) {
            throw new BitcaskException("Failed to create active hint file: " + hintPath, e);
        }
    }
}
