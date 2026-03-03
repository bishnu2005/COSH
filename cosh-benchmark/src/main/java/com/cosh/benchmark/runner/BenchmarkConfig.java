package com.cosh.benchmark.runner;

import com.cosh.benchmark.workload.WorkloadProfile;
import com.cosh.core.eviction.EvictionPolicy;

import java.util.List;

/**
 * Immutable configuration for a single benchmark run.
 *
 * <p>
 * Use the inner {@link Builder} for a fluent construction API.
 */
public final class BenchmarkConfig {

    /** Total operations in the timed measurement phase (after warmup). */
    public final int totalOps;

    /** Warmup operations executed before timing starts (not counted). */
    public final int warmupOps;

    /** Maximum number of entries the CacheStore will hold. */
    public final int cacheCapacity;

    /** Key space size — number of distinct keys in the universe. */
    public final int keySpaceSize;

    /** Reproducible seed for the workload generator. */
    public final long seed;

    /** Value written on each PUT (fixed string for simplicity). */
    public final String value;

    /** Workloads to benchmark. */
    public final List<WorkloadProfile> workloads;

    /** Eviction policies to benchmark. Named via {@link EvictionPolicy#name()}. */
    public final List<NamedPolicy> policies;

    /**
     * Number of timed repetitions per (policy × workload) combination.
     * Results are averaged across all repetitions. Default: 5.
     */
    public final int repetitions;

    private BenchmarkConfig(Builder b) {
        this.totalOps = b.totalOps;
        this.warmupOps = b.warmupOps;
        this.cacheCapacity = b.cacheCapacity;
        this.keySpaceSize = b.keySpaceSize;
        this.seed = b.seed;
        this.value = b.value;
        this.workloads = List.copyOf(b.workloads);
        this.policies = List.copyOf(b.policies);
        this.repetitions = b.repetitions;
    }

    // ─── Nested types
    // ─────────────────────────────────────────────────────────────

    /** An eviction policy paired with a human-readable label for reporting. */
    public record NamedPolicy(String name, EvictionPolicy policy) {
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int totalOps = 100_000;
        private int warmupOps = 20_000;
        private int cacheCapacity = 1_000;
        private int keySpaceSize = 2_000;
        private long seed = 42L;
        private String value = "v".repeat(64);
        private List<WorkloadProfile> workloads = List.of(WorkloadProfile.values());
        private List<NamedPolicy> policies = List.of();
        private int repetitions = 5;

        public Builder totalOps(int v) {
            this.totalOps = v;
            return this;
        }

        public Builder warmupOps(int v) {
            this.warmupOps = v;
            return this;
        }

        public Builder cacheCapacity(int v) {
            this.cacheCapacity = v;
            return this;
        }

        public Builder keySpaceSize(int v) {
            this.keySpaceSize = v;
            return this;
        }

        public Builder seed(long v) {
            this.seed = v;
            return this;
        }

        public Builder value(String v) {
            this.value = v;
            return this;
        }

        public Builder workloads(List<WorkloadProfile> v) {
            this.workloads = v;
            return this;
        }

        public Builder policies(List<NamedPolicy> v) {
            this.policies = v;
            return this;
        }

        public Builder repetitions(int v) {
            this.repetitions = v;
            return this;
        }

        public BenchmarkConfig build() {
            return new BenchmarkConfig(this);
        }
    }
}
