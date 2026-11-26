package com.cosh.benchmark;

import java.util.Arrays;
import java.util.Random;

/**
 * Deterministic (seeded) key-trace generators. Traces are generated up front as boxed
 * {@code Integer[]}, so neither random-number generation nor boxing ever runs inside a
 * timed region.
 */
public final class Workloads {
    private Workloads() {
    }

    public enum Kind {
        UNIFORM("Uniform"),
        ZIPFIAN("Zipfian (s=0.99)"),
        SHIFTING("Shifting Zipfian (4 phases)"),
        SCAN("Zipfian + 20% sequential scan");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    public static final double ZIPF_S = 0.99; // YCSB default skew
    public static final int PHASES = 4;

    /**
     * @param kind      workload shape
     * @param prefix    untimed requests to warm the cache before measurement
     * @param measured  measured requests
     * @param keySpace  distinct keys (for SCAN, scan keys are additional)
     * @param capacity  cache capacity (sizes the scan loop for SCAN)
     */
    public static Integer[] trace(Kind kind, int prefix, int measured, int keySpace, int capacity, long seed) {
        return trace(kind, prefix, measured, keySpace, capacity, seed, seed);
    }

    /**
     * @param requestSeed seeds the request sequence
     * @param keyMapSeed  seeds the popularity-rank-to-key mapping (share it across threads so
     *                    they agree on which keys are hot)
     */
    public static Integer[] trace(Kind kind, int prefix, int measured, int keySpace, int capacity,
                                  long requestSeed, long keyMapSeed) {
        Random rnd = new Random(requestSeed);
        int total = prefix + measured;
        int[] perm = permutation(keySpace, new Random(keyMapSeed)); // rank -> key id, hot keys scattered
        double[] cdf = kind == Kind.UNIFORM ? null : zipfCdf(keySpace, ZIPF_S);
        Integer[] boxed = new Integer[keySpace + 5 * capacity];
        for (int i = 0; i < boxed.length; i++) boxed[i] = i; // unique Integer instances, created once

        Integer[] out = new Integer[total];
        int scanPos = 0;
        int scanLen = 5 * capacity; // a loop 5x larger than the cache: pure pollution for LRU
        for (int i = 0; i < total; i++) {
            int key;
            switch (kind) {
                case UNIFORM -> key = rnd.nextInt(keySpace);
                case ZIPFIAN -> key = perm[sampleRank(cdf, rnd)];
                case SHIFTING -> {
                    int phase = i < prefix ? 0 : Math.min(PHASES - 1, (int) ((long) (i - prefix) * PHASES / measured));
                    int offset = phase * (keySpace / PHASES); // disjoint hot set each phase
                    key = perm[(sampleRank(cdf, rnd) + offset) % keySpace];
                }
                case SCAN -> key = rnd.nextDouble() < 0.80
                        ? perm[sampleRank(cdf, rnd)]
                        : keySpace + (scanPos++ % scanLen);
                default -> throw new IllegalArgumentException(kind.name());
            }
            out[i] = boxed[key];
        }
        return out;
    }

    static double[] zipfCdf(int n, double s) {
        double[] cdf = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += 1.0 / Math.pow(i + 1, s);
            cdf[i] = sum;
        }
        for (int i = 0; i < n; i++) cdf[i] /= sum;
        cdf[n - 1] = 1.0;
        return cdf;
    }

    static int sampleRank(double[] cdf, Random rnd) {
        int idx = Arrays.binarySearch(cdf, rnd.nextDouble());
        return idx >= 0 ? idx : Math.min(cdf.length - 1, -idx - 1);
    }

    private static int[] permutation(int n, Random rnd) {
        int[] p = new int[n];
        for (int i = 0; i < n; i++) p[i] = i;
        for (int i = n - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int t = p[i];
            p[i] = p[j];
            p[j] = t;
        }
        return p;
    }
}
