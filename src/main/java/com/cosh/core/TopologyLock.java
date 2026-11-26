package com.cosh.core;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Reader-biased lock for the cluster topology (a "big-reader" lock, as in Linux brlock or
 * percpu-rwsem). Every request takes the read side, and only add/remove-node takes the write side.
 *
 * <h2>Why not {@code ReentrantReadWriteLock}?</h2>
 * Every RRWL read acquisition CASes one shared state word and updates per-thread hold
 * counters. With 8 threads that one cache line ping-pongs between cores on every request.
 * Measured in this repo, the RRWL alone cut 8-thread sharded throughput by about 2.4x
 * (see ENGINEERING_DEEP_DIVE.md, "Bug #8").
 *
 * <h2>Design</h2>
 * <ul>
 *   <li>Readers increment a counter in their own stripe, chosen by thread id. Stripes are
 *       spaced 128 bytes apart (the cache-line size on Apple M-series, twice x86's), so
 *       readers on different stripes never share a line.</li>
 *   <li>A writer sets {@code writerActive}, then waits until every stripe reads zero.</li>
 *   <li>Correctness is a Dekker-style handshake. The reader does a volatile increment and then a
 *       volatile read of the flag. The writer does a volatile write of the flag and then volatile
 *       reads of the counters. Java volatile accesses are sequentially consistent, so at least
 *       one side always sees the other. A reader that sees the flag backs out and waits.</li>
 *   <li>Writers are serialized by a mutex and take priority over new readers. A pathological
 *       stream of back-to-back topology changes can therefore starve readers. That trade-off is
 *       deliberate, because topology changes are rare.</li>
 * </ul>
 * The thread that holds the write side may also take the read side; that call is a no-op,
 * which avoids self-deadlock.
 */
final class TopologyLock {
    private static final int PAD = 16; // 16 longs = 128 bytes between stripes
    private static final int REENTRANT_WRITER = -1;

    private final int mask;
    private final AtomicLongArray readers;
    private final ReentrantLock writerMutex = new ReentrantLock();
    private volatile boolean writerActive;

    TopologyLock() {
        int stripes = Integer.highestOneBit(Math.max(2, Runtime.getRuntime().availableProcessors() * 2 - 1)) << 1;
        this.mask = stripes - 1;
        this.readers = new AtomicLongArray((stripes + 1) * PAD); // +1: keep the last stripe off the array tail
    }

    /** @return a token to pass to {@link #readUnlock(int)} */
    @SuppressWarnings("deprecation") // Thread.getId(): threadId() needs Java 19+, we target 17
    int readLock() {
        if (writerActive && writerMutex.isHeldByCurrentThread()) {
            return REENTRANT_WRITER;
        }
        int slot = (int) ((Thread.currentThread().getId() & mask) + 1) * PAD;
        while (true) {
            readers.incrementAndGet(slot);
            if (!writerActive) {
                return slot;
            }
            readers.decrementAndGet(slot); // back out so the writer can proceed
            while (writerActive) {
                LockSupport.parkNanos(10_000);
            }
        }
    }

    void readUnlock(int token) {
        if (token != REENTRANT_WRITER) {
            readers.decrementAndGet(token);
        }
    }

    void writeLock() {
        writerMutex.lock();
        if (writerMutex.getHoldCount() > 1) {
            return; // nested write section: already exclusive
        }
        writerActive = true;
        for (int s = PAD; s < readers.length(); s += PAD) {
            int spins = 0;
            while (readers.get(s) != 0) {
                if (++spins < 100) Thread.onSpinWait(); else LockSupport.parkNanos(1_000);
            }
        }
    }

    void writeUnlock() {
        if (writerMutex.getHoldCount() == 1) {
            writerActive = false;
        }
        writerMutex.unlock();
    }
}
