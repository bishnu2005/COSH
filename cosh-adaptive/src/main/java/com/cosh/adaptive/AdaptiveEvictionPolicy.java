package com.cosh.adaptive;

import com.cosh.core.eviction.EvictionPolicy;
import com.cosh.core.store.CacheEntry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Adaptive eviction policy using lightweight frequency-recency heuristics.
 *
 * <h2>Design Goals</h2>
 * <ul>
 * <li><strong>Zero ML frameworks</strong> — pure Java arithmetic only</li>
 * <li><strong>Zero GET overhead</strong> — inference runs ONLY during
 * {@link #selectEvictionCandidate}</li>
 * <li><strong>Optional</strong> — registered via SPI; if absent, system uses
 * any other policy</li>
 * <li><strong>No cosh-core modification</strong> — implements
 * {@link EvictionPolicy} as-is</li>
 * </ul>
 *
 * <h2>SPI Registration</h2>
 * <p>
 * This class is declared in:
 * {@code META-INF/services/com.cosh.core.eviction.EvictionPolicy}
 * <p>
 * Discover it at runtime via {@link PolicyDiscovery#findAdaptivePolicy()}.
 *
 * <h2>Algorithm</h2>
 * <p>
 * See {@link AdaptiveScorer} for the scoring formula.
 * At eviction time, we random-sample {@value #SAMPLE_SIZE} entries and evict
 * the one with the highest score (most stale + least frequent).
 *
 * <h2>Thread Safety</h2>
 * <p>
 * All per-key state lives in a {@link ConcurrentHashMap}. {@link KeyStats}
 * fields are individually thread-safe. Normalization maxima use
 * {@link AtomicLong} for visibility across threads. No global locks.
 */
public class AdaptiveEvictionPolicy implements EvictionPolicy {

    /**
     * Number of candidates to sample per eviction decision. Larger than
     * SampledLRU's 5.
     */
    private static final int SAMPLE_SIZE = 10;

    /**
     * Per-key stats: frequency + timestamps. Maintained independently of
     * {@link CacheEntry}
     * so cosh-core's API is not modified.
     */
    private final ConcurrentHashMap<String, KeyStats> stats = new ConcurrentHashMap<>();

    /**
     * Running maximum of observed recency values (nanos), used for score
     * normalization. Updated lazily during {@link #selectEvictionCandidate}.
     * Initialized to 1 to avoid divide-by-zero on the first call.
     */
    final AtomicLong maxObservedRecencyNs = new AtomicLong(1L);

    /**
     * Running maximum of observed windowed frequencies, used for score
     * normalization. Updated lazily during {@link #selectEvictionCandidate}.
     * Initialized to 1 to avoid divide-by-zero on the first call.
     */
    final AtomicLong maxObservedFrequency = new AtomicLong(1L);

    // ─── EvictionPolicy callbacks ───────────────────────────────────────────────

    @Override
    public void onGet(String key) {
        // O(1) update — NO scoring happening here, only a counter increment and
        // timestamp write
        KeyStats s = stats.get(key);
        if (s != null) {
            s.recordAccess();
        }
    }

    @Override
    public void onPut(String key) {
        // Reuse existing KeyStats instance (reset fields) to avoid allocation churn
        // on re-insertion of the same key. Creates new instance only for first insert.
        stats.compute(key, (k, existing) -> {
            if (existing != null) {
                existing.reset();
                return existing;
            }
            return new KeyStats();
        });
    }

    @Override
    public void onRemove(String key) {
        stats.remove(key);
    }

    // ─── Eviction candidate selection ───────────────────────────────────────────

    /**
     * Score {@value #SAMPLE_SIZE} random candidates from the store and return the
     * key with the highest eviction score (most stale, least frequently accessed).
     *
     * <p>
     * Inference happens ONLY here — never on every GET. Normalization maxima are
     * updated lazily from the sampled set.
     *
     * @param store current cache contents
     * @return key to evict, or {@code null} if the store is empty
     */
    @Override
    public String selectEvictionCandidate(Map<String, CacheEntry> store) {
        if (store.isEmpty())
            return null;

        long nowNanos = System.nanoTime();
        long localMaxRecency = maxObservedRecencyNs.get();
        long localMaxFreq = maxObservedFrequency.get();

        // First pass: collect sampled stats and update running maxima
        String bestKey = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        int sampled = 0;

        for (Map.Entry<String, CacheEntry> entry : store.entrySet()) {
            if (sampled >= SAMPLE_SIZE)
                break;

            String key = entry.getKey();
            KeyStats keyStats = stats.get(key);

            if (keyStats == null) {
                // No stats tracked (e.g. concurrent insert) — treat as maximally evictable
                if (Double.MAX_VALUE > bestScore) {
                    bestScore = Double.MAX_VALUE;
                    bestKey = key;
                }
                sampled++;
                continue;
            }

            // Update running maxima lazily (only from the sample, not every GET)
            long recencyNs = nowNanos - keyStats.lastAccessNanos;
            long freq = keyStats.getWindowedFrequency(nowNanos);

            if (recencyNs > localMaxRecency) {
                localMaxRecency = recencyNs;
                maxObservedRecencyNs.updateAndGet(v -> Math.max(v, recencyNs));
            }
            if (freq > localMaxFreq) {
                localMaxFreq = freq;
                maxObservedFrequency.updateAndGet(v -> Math.max(v, freq));
            }

            double score = AdaptiveScorer.score(keyStats, nowNanos, localMaxRecency, localMaxFreq);
            if (score > bestScore) {
                bestScore = score;
                bestKey = key;
            }
            sampled++;
        }

        return bestKey;
    }

    /** For testing: returns the number of keys currently being tracked. */
    int trackedKeyCount() {
        return stats.size();
    }
}
