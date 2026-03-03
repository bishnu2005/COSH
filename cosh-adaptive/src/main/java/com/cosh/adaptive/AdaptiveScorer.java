package com.cosh.adaptive;

/**
 * Heuristic scoring formula for the adaptive eviction policy.
 *
 * <h2>Algorithm: Normalized Recency-Frequency</h2>
 *
 * <p>
 * score = α * normalizedRecency - β * normalizedFrequency
 *
 * <p> Higher score = better candidate to evict:
 * <ul>
 * <li>Stale keys have high recency, raising score</li>
 * <li>Hot keys have high frequency, lowering score</li>
 * </ul>
 */
final class AdaptiveScorer {

    // Tunable parameters (adjusted by auto-tuner)
    static volatile double alpha = 0.5;
    static volatile double beta = 0.5;

    private AdaptiveScorer() {
    }

    /**
     * Compute the eviction score for a single cache entry.
     *
     * @param stats                the key's accumulated statistics
     * @param nowNanos             current epoch-nanoseconds
     * @param maxObservedRecency   max recency seen across cache (nanos)
     * @param maxObservedFrequency max frequency seen across cache
     * @return eviction score — higher means "prefer to evict this key"
     */
    static double score(KeyStats stats, long nowNanos, long maxObservedRecency, long maxObservedFrequency) {
        long recencyNs = nowNanos - stats.lastAccessNanos;
        long freq = stats.getWindowedFrequency(nowNanos);

        if (freq == 0) {
            // Unused recently, highly evictable
            return Double.MAX_VALUE;
        }

        double normalizedRecency = (double) recencyNs / Math.max(1L, maxObservedRecency);
        double normalizedFrequency = (double) freq / Math.max(1L, maxObservedFrequency);

        return alpha * normalizedRecency - beta * normalizedFrequency;
    }
}
