package com.cosh.benchmark.workload;

/**
 * Defines the workload access patterns supported by the COSH benchmark.
 *
 * <ul>
 * <li>{@link #UNIFORM} — equal probability across all keys (ideal cache)</li>
 * <li>{@link #ZIPF} — power-law skew (s=1.0); top 10% of keys ≈ 65% of
 * accesses</li>
 * <li>{@link #ZIPF_S12} — Zipf with skew exponent s=1.2 (moderate skew)</li>
 * <li>{@link #ZIPF_S15} — Zipf with skew exponent s=1.5 (heavy skew)</li>
 * <li>{@link #HOT_KEY_SPIKE} — 5 fixed hot keys receive 70% of load initially,
 * then switch to Zipf distribution to simulate a flash-crowd scenario</li>
 * </ul>
 */
public enum WorkloadProfile {
    UNIFORM,
    /** Zipf s=1.0 — kept for backward compatibility. */
    ZIPF,
    /** Zipf s=1.2 — moderate skew. */
    ZIPF_S12,
    /** Zipf s=1.5 — heavy skew, more realistic hot-key concentration. */
    ZIPF_S15,
    HOT_KEY_SPIKE
}
