/* (C)2026 */
package com.bitcask.metrics;

import com.bitcask.client.Bitcask;
import com.bitcask.merge.MergePolicy;

/**
 * A point-in-time snapshot of store health metrics.
 * Returned by {@link Bitcask#stats()} and consumed by {@link MergePolicy}.
 */
public class StoreStats {

    private final int liveKeyCount;
    private final int totalFileCount;
    private final long deadKeyCount;
    private final double deadByteRatio;
    private final long totalDiskBytes;

    /**
     * Constructs a StoreStats snapshot.
     *
     * @param liveKeyCount   number of keys currently in the index
     * @param totalFileCount number of data files on disk (active + immutable)
     * @param deadKeyCount   estimated number of superseded or deleted records on disk
     * @param deadByteRatio  fraction of disk bytes occupied by dead records (0.0–1.0)
     * @param totalDiskBytes total bytes used by all data files
     */
    public StoreStats(
            int liveKeyCount,
            int totalFileCount,
            long deadKeyCount,
            double deadByteRatio,
            long totalDiskBytes) {
        this.liveKeyCount = liveKeyCount;
        this.totalFileCount = totalFileCount;
        this.deadKeyCount = deadKeyCount;
        this.deadByteRatio = deadByteRatio;
        this.totalDiskBytes = totalDiskBytes;
    }

    /** @return number of live keys in the index */
    public int getLiveKeyCount() {
        return liveKeyCount;
    }

    /** @return total number of data files on disk */
    public int getTotalFileCount() {
        return totalFileCount;
    }

    /** @return estimated dead record count */
    public long getDeadKeyCount() {
        return deadKeyCount;
    }

    /** @return fraction of disk space used by dead records */
    public double getDeadByteRatio() {
        return deadByteRatio;
    }

    /** @return total bytes consumed by all data files */
    public long getTotalDiskBytes() {
        return totalDiskBytes;
    }

    @Override
    public String toString() {
        return "StoreStats{"
                + "liveKeys="
                + liveKeyCount
                + ", files="
                + totalFileCount
                + ", deadRatio="
                + String.format("%.2f", deadByteRatio)
                + ", diskBytes="
                + totalDiskBytes
                + "}";
    }
}
