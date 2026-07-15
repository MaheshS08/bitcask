package com.bitcask.merge;


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
public interface MergePolicy {
}
