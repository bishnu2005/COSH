package com.cosh.benchmark.workload;

import java.util.Random;

/**
 * Generates reproducible key sequences for each {@link WorkloadProfile}.
 *
 * <h2>Seed guarantee</h2>
 * <p>
 * A fixed seed produces identical access patterns across executions — important
 * for paper reproducibility.
 *
 * <h2>Zipf implementation</h2>
 * <p>
 * Uses an inverse-CDF approach: pre-computes the Zipf CDF table for the
 * given key space at construction, then samples via binary-search lookup.
 * This is O(N) to build, O(log N) per sample, and works for any s ≥ 0.
 * Multiple CDF tables are built for the different skew levels (s=1.0, s=1.2,
 * s=1.5) so that all three Zipf profiles can be served from one generator
 * instance.
 *
 * <h2>Key space</h2>
 * <p>
 * Keys are strings of the form {@code "key:<N>"} where N is the rank (1-based).
 * Hot keys for HOT_KEY_SPIKE are always keys 1–5.
 */
public class WorkloadGenerator {

    private static final int NUM_HOT_KEYS = 5;
    private static final double HOT_KEY_FRACTION = 0.70;

    private final int keySpaceSize;
    private final long seed;
    private final Random rng;

    /** Pre-computed CDF tables for Zipf sampling via inverse CDF. */
    private final double[] zipfCdf10; // s=1.0
    private final double[] zipfCdf12; // s=1.2
    private final double[] zipfCdf15; // s=1.5

    /**
     * Constructs a generator using the default Zipf skew (s=1.0) for the
     * {@link WorkloadProfile#ZIPF} profile. All three Zipf CDF tables are still
     * built so that ZIPF_S12 and ZIPF_S15 profiles can be served from this
     * instance.
     */
    public WorkloadGenerator(int keySpaceSize, long seed) {
        this(keySpaceSize, seed, 1.0);
    }

    /**
     * Constructs a generator. The {@code zipfSkew} parameter is the default skew
     * used when profiled as {@link WorkloadProfile#ZIPF}. All three CDF tables
     * (s=1.0, s=1.2, s=1.5) are pre-built regardless.
     *
     * @param keySpaceSize number of distinct keys in the universe
     * @param seed         RNG seed for reproducibility
     * @param zipfSkew     skew exponent used for the baseline ZIPF profile
     *                     (ignored for ZIPF_S12 / ZIPF_S15 which use fixed s)
     */
    public WorkloadGenerator(int keySpaceSize, long seed, double zipfSkew) {
        this.keySpaceSize = keySpaceSize;
        this.seed = seed;
        this.rng = new Random(seed);
        this.zipfCdf10 = buildZipfCdf(keySpaceSize, 1.0);
        this.zipfCdf12 = buildZipfCdf(keySpaceSize, 1.2);
        this.zipfCdf15 = buildZipfCdf(keySpaceSize, 1.5);
    }

    /**
     * Returns the next key for the given workload profile.
     */
    public String nextKey(WorkloadProfile profile, int opIndex, int totalOps) {
        return switch (profile) {
            case UNIFORM -> uniformKey();
            case ZIPF -> zipfKey(zipfCdf10);
            case ZIPF_S12 -> zipfKey(zipfCdf12);
            case ZIPF_S15 -> zipfKey(zipfCdf15);
            case HOT_KEY_SPIKE -> hotKeySpikeKey(opIndex, totalOps);
        };
    }

    // ─── Workload implementations
    // ─────────────────────────────────────────────────

    private String uniformKey() {
        return "key:" + (rng.nextInt(keySpaceSize) + 1);
    }

    /**
     * Samples a key from the given Zipf CDF using inverse-CDF lookup.
     * Returns rank in [1, keySpaceSize], weighted ∝ 1/rank^s.
     */
    private String zipfKey(double[] cdf) {
        double u = rng.nextDouble();
        // Binary search for the first CDF entry ≥ u
        int lo = 0, hi = keySpaceSize - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cdf[mid] < u)
                lo = mid + 1;
            else
                hi = mid;
        }
        return "key:" + (lo + 1); // convert 0-based index to 1-based rank
    }

    /**
     * HOT_KEY_SPIKE: first 30% of ops send 70% of requests to keys 1–5.
     * After the spike the distribution falls back to Zipf s=1.0.
     */
    private String hotKeySpikeKey(int opIndex, int totalOps) {
        boolean inSpike = opIndex < (int) (totalOps * 0.30);
        if (inSpike && rng.nextDouble() < HOT_KEY_FRACTION) {
            return "key:" + (rng.nextInt(NUM_HOT_KEYS) + 1);
        }
        return zipfKey(zipfCdf10);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * Builds a Zipf CDF table of length n.
     * {@code cdf[i]} = P(rank ≤ i+1) = H(i+1) / H(n) where H(k) = Σ 1/j^s for
     * j=1..k.
     */
    private static double[] buildZipfCdf(int n, double s) {
        double[] cdf = new double[n];
        double harmonic = 0.0;
        for (int i = 1; i <= n; i++)
            harmonic += 1.0 / Math.pow(i, s);

        double cumulative = 0.0;
        for (int i = 1; i <= n; i++) {
            cumulative += 1.0 / Math.pow(i, s);
            cdf[i - 1] = cumulative / harmonic;
        }
        cdf[n - 1] = 1.0; // clamp final entry to avoid floating-point drift
        return cdf;
    }

    /** Resets the RNG to the original seed for repeat-run reproducibility. */
    public void reset() {
        rng.setSeed(seed);
    }
}
