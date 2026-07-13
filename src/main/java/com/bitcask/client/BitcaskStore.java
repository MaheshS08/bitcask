/* (C)2026 */
package com.bitcask.client;

import com.bitcask.config.BitcaskConfig;
import com.bitcask.index.KeyDir;
import com.bitcask.storage.DataFile;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class BitcaskStore implements Bitcask {

    //    config          BitcaskConfig
    //    keyDir          KeyDir
    //    activeFile      DataFile
    //    immutableFiles  Map<Long, DataFile>      fileId → read-only DataFile
    //    writeLock       ReentrantReadWriteLock
    //    mergeExecutor   ScheduledExecutorService  null if backgroundMergeEnabled=false
    //    directory       Path

    private final BitcaskConfig config;
    private final KeyDir keyDir;
    private final DataFile activeFile;
    private final Map<Long, DataFile> immutableFiles;
    private final ReentrantReadWriteLock writeLock;
    private final Path directory;

    public BitcaskStore(
            BitcaskConfig config,
            KeyDir keyDir,
            DataFile activeFile,
            Map<Long, DataFile> immutableFiles,
            ReentrantReadWriteLock writeLock,
            Path directory) {
        this.config = config;
        this.keyDir = keyDir;
        this.activeFile = activeFile;
        this.immutableFiles = immutableFiles;
        this.writeLock = writeLock;
        this.directory = directory;
    }

    @Override
    public void put(byte[] key, byte[] value) {}

    @Override
    public byte[] get(byte[] key) {
        return new byte[0];
    }

    @Override
    public void delete(byte[] key) {}

    @Override
    public void merge() {}

    @Override
    public void sync() {}

    @Override
    public Set<byte[]> listKeys() {
        return Set.of();
    }

    @Override
    public StoreStats stats() {
        return null;
    }

    @Override
    public void close() {}
}
