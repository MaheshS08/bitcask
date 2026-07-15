package com.bitcask.merge;


/**
 * A {@link MergePolicy} that triggers compaction when the fraction of dead
 * (overwritten or deleted) records on disk exceeds a configured threshold.
 *
 * <p>For example, with a threshold of 0.5: if more than 50% of the bytes
 * on disk belong to dead records, a merge is triggered.
 */
public class SizeTieredMergePolicy implements MergePolicy {
    private final long sizeThreshold;

    public SizeTieredMergePolicy(long sizeThreshold) {
        this.sizeThreshold = sizeThreshold;
    }

    @Override
    public boolean shouldMerge(long totalSize, int fileCount) {
        return totalSize >= sizeThreshold && fileCount > 1;
    }
}
