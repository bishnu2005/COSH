package com.cosh.eviction;

import java.util.ArrayDeque;
import java.util.HashMap;

/**
 * Sliding-window, frequency-aware second-chance eviction whose window length tunes itself
 * by hill-climbing on the observed hit rate.
 *
 * <h2>Mechanics</h2>
 * <ol>
 *   <li><b>Recency order:</b> an intrusive LRU list. The tail is the default victim.</li>
 *   <li><b>Sliding window:</b> the last {@code windowSize} requested keys (hits, misses,
 *       updates) are kept in a FIFO ring, with a {@code HashMap<K, count>} of how often each
 *       key appears in it. Lookup is O(1). Each slide is amortized O(1): one append and
 *       at most one expiry per request, plus a one-off drain when the window shrinks.</li>
 *   <li><b>Second chance:</b> when evicting, if the LRU tail was requested at least
 *       {@code protectThreshold} times inside the window, it is moved to the head instead of
 *       being evicted. At most {@code maxProbes} entries are rescued per eviction, so
 *       eviction is bounded O(maxProbes) = O(1) even if everything is hot.</li>
 *   <li><b>Adaptation (hill climbing):</b> every {@code samplePeriod} read requests, the
 *       policy compares that period's hit rate with the previous period's. If the hit rate
 *       dropped, the climb direction reverses. The window then moves by {@code step},
 *       clamped to [{@code minWindow}, {@code maxWindow}]. The step decays geometrically so
 *       the window settles, and it resets to full size when the hit rate jumps by more than
 *       {@code restartThreshold} (a sign the workload changed phase). This is the
 *       same scheme Caffeine uses to size its W-TinyLFU admission window.</li>
 * </ol>
 *
 * <p>Short window: only keys requested very recently are protected, so the policy behaves
 * close to LRU and drops stale hot keys quickly after a phase change. Long window: more
 * history counts, so it behaves close to LFU and resists one-hit-wonder pollution under
 * skewed (Zipfian) traffic. The climber searches between the two at runtime.
 *
 * <p>Not thread-safe. It is invoked under the owning cache's lock (see {@link EvictionPolicy}).
 */
public final class SlidingWindowAdaptivePolicy<K, V> implements EvictionPolicy<K, V> {

    public static final int DEFAULT_PROTECT_THRESHOLD = 2;
    public static final int DEFAULT_MAX_PROBES = 4;
    public static final double STEP_DECAY = 0.90;
    public static final double RESTART_THRESHOLD = 0.05;

    private final DoublyLinkedList<K, V> lru = new DoublyLinkedList<>();
    private final ArrayDeque<K> window = new ArrayDeque<>();
    private final HashMap<K, int[]> windowCounts = new HashMap<>();

    private final int minWindow;
    private final int maxWindow;
    private final int protectThreshold;
    private final int maxProbes;
    private final int samplePeriod;
    private final int initialStep;

    private int windowSize;
    private int step;
    private int direction = +1;
    private int sampleHits;
    private int sampleMisses;
    private double previousHitRate = -1;

    // observability
    private long adaptations;
    private long secondChances;

    /** Fixed-length window (no adaptation). Kept for backward compatibility. */
    public SlidingWindowAdaptivePolicy(int windowSize) {
        this(windowSize, windowSize, windowSize, DEFAULT_PROTECT_THRESHOLD, DEFAULT_MAX_PROBES, Integer.MAX_VALUE);
    }

    /**
     * @param initialWindow    starting window length, in requests
     * @param minWindow        lower clamp for adaptation (near-LRU end)
     * @param maxWindow        upper clamp for adaptation (near-LFU end)
     * @param protectThreshold in-window request count that earns a second chance (at least 1)
     * @param maxProbes        maximum second chances per eviction (bounds eviction cost)
     * @param samplePeriod     read requests per hill-climbing step
     */
    public SlidingWindowAdaptivePolicy(int initialWindow, int minWindow, int maxWindow,
                                       int protectThreshold, int maxProbes, int samplePeriod) {
        if (minWindow < 1 || maxWindow < minWindow || initialWindow < minWindow || initialWindow > maxWindow) {
            throw new IllegalArgumentException("require 1 <= minWindow <= initialWindow <= maxWindow");
        }
        if (protectThreshold < 1 || maxProbes < 0 || samplePeriod < 1) {
            throw new IllegalArgumentException("protectThreshold >= 1, maxProbes >= 0, samplePeriod >= 1");
        }
        this.windowSize = initialWindow;
        this.minWindow = minWindow;
        this.maxWindow = maxWindow;
        this.protectThreshold = protectThreshold;
        this.maxProbes = maxProbes;
        this.samplePeriod = samplePeriod;
        this.initialStep = Math.max(1, (maxWindow - minWindow) / 16);
        this.step = initialStep;
    }

    /**
     * Defaults sized from cache capacity C: the window adapts in [C, 16C], starting at 4C,
     * with one climbing step every 2C reads (minimum 1,000). A window shorter than C can
     * never protect an LRU tail entry, because that entry was last touched at least C
     * requests ago. So C is the natural "pure LRU" end of the range.
     */
    public static <K, V> SlidingWindowAdaptivePolicy<K, V> forCapacity(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        long min = capacity;
        long max = 16L * capacity;
        long initial = 4L * capacity;
        int clampMax = (int) Math.min(max, 1 << 24);
        int clampMin = (int) Math.min(min, clampMax);
        int clampInitial = (int) Math.min(Math.max(initial, clampMin), clampMax);
        int sample = (int) Math.max(1_000, Math.min(2L * capacity, 1 << 20));
        return new SlidingWindowAdaptivePolicy<>(clampInitial, clampMin, clampMax,
                DEFAULT_PROTECT_THRESHOLD, DEFAULT_MAX_PROBES, sample);
    }

    // ---------------------------------------------------------------- callbacks

    @Override
    public void onPut(CacheEntry<K, V> entry) {
        // The miss that caused this insert was already counted in onMiss. Counting the
        // fill too would give every brand-new key a "2" and protect one-hit wonders.
        lru.addFirst(entry);
    }

    @Override
    public void onGet(CacheEntry<K, V> entry) {
        lru.moveToFirst(entry);
        recordRequest(entry.key);
        recordOutcome(true);
    }

    @Override
    public void onUpdate(CacheEntry<K, V> entry) {
        lru.moveToFirst(entry);
        recordRequest(entry.key);
    }

    @Override
    public void onMiss(K key) {
        recordRequest(key);
        recordOutcome(false);
    }

    @Override
    public void onRemove(CacheEntry<K, V> entry) {
        lru.remove(entry);
    }

    @Override
    public CacheEntry<K, V> evict() {
        for (int probe = 0; probe < maxProbes && lru.size() > 1; probe++) {
            CacheEntry<K, V> candidate = lru.peekLast();
            if (windowCount(candidate.key) < protectThreshold) {
                break;
            }
            lru.moveToFirst(candidate); // second chance
            secondChances++;
        }
        return lru.removeLast();
    }

    @Override
    public void clear() {
        lru.clear();
        window.clear();
        windowCounts.clear();
        sampleHits = 0;
        sampleMisses = 0;
        previousHitRate = -1;
    }

    @Override
    public int size() {
        return lru.size();
    }

    @Override
    public void checkInvariants() {
        lru.validate();
        if (window.size() > windowSize) throw new IllegalStateException("window exceeds windowSize");
        long counted = 0;
        for (int[] c : windowCounts.values()) {
            if (c[0] <= 0) throw new IllegalStateException("non-positive window count");
            counted += c[0];
        }
        if (counted != window.size()) throw new IllegalStateException("window counts drifted from ring");
        if (windowSize < minWindow || windowSize > maxWindow) throw new IllegalStateException("window out of bounds");
    }

    @Override
    public String name() {
        return minWindow == maxWindow ? "SlidingWindow(fixed)" : "SlidingWindowAdaptive";
    }

    // ---------------------------------------------------------------- internals

    private void recordRequest(K key) {
        window.addLast(key);
        int[] c = windowCounts.get(key);
        if (c == null) {
            windowCounts.put(key, new int[]{1});
        } else {
            c[0]++;
        }
        trimWindow();
    }

    private void trimWindow() {
        while (window.size() > windowSize) {
            K expired = window.pollFirst();
            int[] c = windowCounts.get(expired);
            if (--c[0] == 0) {
                windowCounts.remove(expired);
            }
        }
    }

    private int windowCount(K key) {
        int[] c = windowCounts.get(key);
        return c == null ? 0 : c[0];
    }

    private void recordOutcome(boolean hit) {
        if (minWindow == maxWindow) {
            return; // fixed window: nothing to adapt
        }
        if (hit) sampleHits++; else sampleMisses++;
        if (sampleHits + sampleMisses >= samplePeriod) {
            adapt();
        }
    }

    private void adapt() {
        double hitRate = (double) sampleHits / (sampleHits + sampleMisses);
        if (previousHitRate >= 0) {
            if (hitRate < previousHitRate) {
                direction = -direction; // last move hurt: climb the other way
            }
            if (Math.abs(hitRate - previousHitRate) >= RESTART_THRESHOLD) {
                step = initialStep;     // workload shifted: search widely again
            } else {
                step = Math.max(1, (int) (step * STEP_DECAY));
            }
        }
        long next = (long) windowSize + (long) direction * step;
        windowSize = (int) Math.max(minWindow, Math.min(maxWindow, next));
        if (windowSize == minWindow || windowSize == maxWindow) {
            direction = windowSize == minWindow ? +1 : -1; // bounce off the clamp
        }
        trimWindow();
        previousHitRate = hitRate;
        sampleHits = 0;
        sampleMisses = 0;
        adaptations++;
    }

    // ---------------------------------------------------------------- observability

    public int currentWindowSize() {
        return windowSize;
    }

    public long adaptations() {
        return adaptations;
    }

    public long secondChances() {
        return secondChances;
    }

    public int maxProbes() {
        return maxProbes;
    }
}
