/* (C)2026 */
package com.bitcask.config;

/**
 * Immutable configuration for a {@link BitcaskStore}.
 *
 * <p>Construct via the {@link Builder}:
 * <pre>{@code
 * BitcaskConfig config = new BitcaskConfig.Builder()
 *     .maxFileSize(512 * 1024 * 1024L)
 *     .syncOnWrite(false)
 *     .mergeDeadRatioThreshold(0.5)
 *     .backgroundMergeEnabled(true)
 *     .build();
 * }</pre>
 */
public class BitcaskConfig {
    /** Default dead-key ratio threshold that triggers a merge: 50% */
    public static final double DEFAULT_MERGE_THRESHOLD = 0.5;

    /** Default maximum file size for a single data file (512 MB). */
    private static final long DEFAULT_MAX_FILE_SIZE = 512 * 1024 * 1024L; // 512 MB

    private final long maxFileSize;
    private final boolean syncOnWrite;
    private final double mergeDeadRatioThreshold;
    private final boolean backgroundMergeEnabled;

    private BitcaskConfig(Builder builder) {
        this.maxFileSize = builder.maxFileSize;
        this.syncOnWrite = builder.syncOnWrite;
        this.mergeDeadRatioThreshold = builder.mergeDeadRatioThreshold;
        this.backgroundMergeEnabled = builder.backgroundMergeEnabled;
    }

    /** @return max file size in bytes before the active file is rotated */
    public long getMaxFileSize() {
        return maxFileSize;
    }

    /** @return true if fsync is called after every write */
    public boolean isSyncOnWrite() {
        return syncOnWrite;
    }

    /** @return dead-key ratio (0.0–1.0) above which a merge is triggered */
    public double getMergeDeadRatioThreshold() {
        return mergeDeadRatioThreshold;
    }

    /** @return true if a background thread should check merge eligibility */
    public boolean isBackgroundMergeEnabled() {
        return backgroundMergeEnabled;
    }

    /** Builder for {@link BitcaskConfig}. */
    public static final class Builder {
        private long maxFileSize = DEFAULT_MAX_FILE_SIZE;
        private boolean syncOnWrite = true;
        private double mergeDeadRatioThreshold = DEFAULT_MERGE_THRESHOLD;
        private boolean backgroundMergeEnabled = true;

        /**
         * Sets the maximum data file size before rotation.
         *
         * @param bytes max file size in bytes; must be positive
         * @return this builder
         */
        public Builder maxFileSize(long bytes) {
            this.maxFileSize = bytes;
            return this;
        }

        /**
         * If true, fsync is called after every write.
         * Safer but slower. Default: false.
         *
         * @param sync true to sync after every write
         * @return this builder
         */
        public Builder syncOnWrite(boolean sync) {
            this.syncOnWrite = sync;
            return this;
        }

        /**
         * Sets the dead-key ratio threshold for triggering compaction.
         *
         * @param threshold value between 0.0 and 1.0
         * @return this builder
         */
        public Builder mergeDeadRatioThreshold(double threshold) {
            this.mergeDeadRatioThreshold = threshold;
            return this;
        }

        /**
         * Enables or disables the background merge thread.
         *
         * @param enabled true to run periodic background merge checks
         * @return this builder
         */
        public Builder backgroundMergeEnabled(boolean enabled) {
            this.backgroundMergeEnabled = enabled;
            return this;
        }

        /**
         * Builds and returns the {@link BitcaskConfig}.
         *
         * @return configured BitcaskConfig instance
         */
        public BitcaskConfig build() {
            return new BitcaskConfig(this);
        }
    }
}
