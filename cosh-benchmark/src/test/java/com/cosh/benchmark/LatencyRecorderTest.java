package com.cosh.benchmark;

import com.cosh.benchmark.runner.LatencyRecorder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LatencyRecorder}: verifies percentile correctness
 * with known inputs.
 */
class LatencyRecorderTest {

    @Test
    void emptyRecorderReturnsZero() {
        LatencyRecorder r = new LatencyRecorder(100);
        assertEquals(0.0, r.avgUs());
        assertEquals(0.0, r.p99Us());
        assertEquals(0.0, r.maxUs());
    }

    @Test
    void singleSamplePercentilesAreEqual() {
        LatencyRecorder r = new LatencyRecorder(10);
        r.record(5_000); // 5 μs
        assertEquals(5.0, r.avgUs(), 0.01);
        assertEquals(5.0, r.p50Us(), 0.01);
        assertEquals(5.0, r.p99Us(), 0.01);
        assertEquals(5.0, r.maxUs(), 0.01);
    }

    @Test
    void p99IsCorrectForKnownDistribution() {
        // Record 100 samples: 1 μs – 100 μs (in nanos = 1000 – 100000)
        LatencyRecorder r = new LatencyRecorder(100);
        for (int i = 1; i <= 100; i++)
            r.record(i * 1_000L);

        // avg = 50.5 μs
        assertEquals(50.5, r.avgUs(), 0.5);
        // p99 should be the 99th value = 99 μs (sorted)
        assertEquals(99.0, r.p99Us(), 1.0);
        // max = 100 μs
        assertEquals(100.0, r.maxUs(), 0.1);
    }

    @Test
    void mergeProducesCorrectCount() {
        LatencyRecorder a = new LatencyRecorder(50);
        LatencyRecorder b = new LatencyRecorder(50);
        for (int i = 0; i < 30; i++) {
            a.record(1_000L);
            b.record(2_000L);
        }

        LatencyRecorder merged = LatencyRecorder.merge(a, b);
        assertEquals(60, merged.count());
        // avg = (30×1 + 30×2) / 60 = 1.5 μs
        assertEquals(1.5, merged.avgUs(), 0.01);
    }

    @Test
    void capacityNotExceeded() {
        LatencyRecorder r = new LatencyRecorder(5);
        for (int i = 0; i < 100; i++)
            r.record(1_000L); // overflow silently dropped
        assertEquals(5, r.count());
    }
}
