package com.cosh.adaptive;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-key access statistics maintained by {@link AdaptiveEvictionPolicy}.
 *
 * <p>
 * Thread-safe:
 * <ul>
 * <li>Sliding window frequency via time-bucketed counters (4x 250ms buckets)</li>
 * <li>{@code lastAccessNanos} is {@code volatile}</li>
 * </ul>
 *
 * <p>
 * Inference cost: O(1) reads during eviction candidate scoring.
 */
final class KeyStats {

    /** Epoch-nanosecond timestamp when this key was first inserted or reset. */
    long firstSeenNanos;

    /** Nanosecond timestamp of the most recent GET that returned a hit. */
    volatile long lastAccessNanos;

    private static final long BUCKET_NANOS = 250_000_000L; // 250ms
    private static final int NUM_BUCKETS = 4;

    private final AtomicLong[] buckets;
    private volatile long currentBucketEpoch;

    KeyStats() {
        this.buckets = new AtomicLong[NUM_BUCKETS];
        for (int i = 0; i < NUM_BUCKETS; i++) {
            this.buckets[i] = new AtomicLong();
        }
        reset();
    }

    void reset() {
        this.firstSeenNanos = System.nanoTime();
        this.lastAccessNanos = this.firstSeenNanos;
        this.currentBucketEpoch = this.firstSeenNanos / BUCKET_NANOS;
        for (AtomicLong bucket : buckets) {
            bucket.set(0);
        }
    }

    /** Called on each successful GET. */
    void recordAccess() {
        long now = System.nanoTime();
        lastAccessNanos = now;
        long epoch = now / BUCKET_NANOS;

        // Fast path: still in same bucket epoch
        if (epoch == currentBucketEpoch) {
            buckets[(int) (epoch % NUM_BUCKETS)].incrementAndGet();
            return;
        }

        // Slow path: window advanced
        synchronized (this) {
            long currentEpoch = currentBucketEpoch;
            if (epoch > currentEpoch) {
                long diff = epoch - currentEpoch;
                if (diff >= NUM_BUCKETS) {
                    for (AtomicLong bucket : buckets) {
                        bucket.set(0);
                    }
                } else {
                    for (long i = 1; i <= diff; i++) {
                        buckets[(int) ((currentEpoch + i) % NUM_BUCKETS)].set(0);
                    }
                }
                currentBucketEpoch = epoch;
            }
        }
        buckets[(int) (epoch % NUM_BUCKETS)].incrementAndGet();
    }

    /** Snapshot of the windowed access count (used during scoring). */
    long getWindowedFrequency(long nowNanos) {
        long epoch = nowNanos / BUCKET_NANOS;
        long currentEpoch = currentBucketEpoch;

        long diff = epoch - currentEpoch;
        if (diff >= NUM_BUCKETS) {
            return 0; // Window entirely passed
        }

        long sum = 0;
        long earliestValidEpoch = epoch - NUM_BUCKETS + 1;

        for (int i = 0; i < NUM_BUCKETS; i++) {
            long bucketEpoch = currentEpoch - i;
            if (bucketEpoch >= earliestValidEpoch) {
                sum += buckets[(int) (bucketEpoch % NUM_BUCKETS)].get();
            }
        }
        return sum;
    }
}
