/* (C)2026 */
package com.bitcask.merge;

import com.bitcask.metrics.StoreStats;

/**
 * A {@link MergePolicy} that triggers compaction when the fraction of dead
 * (overwritten or deleted) records on disk exceeds a configured threshold.
 *
 * <p>For example, with a threshold of 0.5: if more than 50% of the bytes
 * on disk belong to dead records, a merge is triggered.
 */
public class SizeTieredMergePolicy implements MergePolicy {
    private final double deadRatioThreshold;

    /**
     * Constructs a SizeTieredMergePolicy.
     *
     * @param deadRatioThreshold fraction of dead bytes (0.0–1.0) that triggers merge
     * @throws IllegalArgumentException if threshold is not in [0.0, 1.0]
     */
    public SizeTieredMergePolicy(double deadRatioThreshold) {
        if (deadRatioThreshold < 0.0 || deadRatioThreshold > 1.0) {
            throw new IllegalArgumentException("Threshold must be between 0.0 and 1.0");
        }
        this.deadRatioThreshold = deadRatioThreshold;
    }

    /**
     * Returns true if the current dead-byte ratio exceeds the configured threshold.
     *
     * @param stats current store statistics
     * @return true if merge should proceed
     */
    @Override
    public boolean shouldMerge(StoreStats stats) {
        return stats.getDeadByteRatio() >= deadRatioThreshold;
    }
}
