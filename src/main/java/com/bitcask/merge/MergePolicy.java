/* (C)2026 */
package com.bitcask.merge;

import com.bitcask.metrics.StoreStats;

/**
 * Strategy interface for deciding whether a Bitcask store should run compaction.
 *
 * <p>Implement this interface to provide custom merge-trigger logic.
 * The default implementation is {@link SizeTieredMergePolicy}.
 *
 * <p>Example — always merge:
 * <pre>{@code
 * MergePolicy alwaysMerge = stats -> true;
 * }</pre>
 */
@FunctionalInterface
public interface MergePolicy {
    /**
     * Returns true if the store should run a merge based on current statistics.
     *
     * @param stats current point-in-time store statistics; never null
     * @return true if merge should proceed
     */
    boolean shouldMerge(StoreStats stats);
}
