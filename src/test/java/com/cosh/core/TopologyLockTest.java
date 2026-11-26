package com.cosh.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

class TopologyLockTest {

    @Test
    void writerNeverOverlapsReaders() throws Exception {
        TopologyLock lock = new TopologyLock();
        AtomicInteger activeReaders = new AtomicInteger();
        AtomicBoolean writerInside = new AtomicBoolean();
        AtomicInteger violations = new AtomicInteger();
        AtomicBoolean stop = new AtomicBoolean();
        int readers = 12;
        ExecutorService pool = Executors.newFixedThreadPool(readers + 2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(readers + 2);

        for (int r = 0; r < readers; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (!stop.get()) {
                        int t = lock.readLock();
                        try {
                            activeReaders.incrementAndGet();
                            if (writerInside.get()) violations.incrementAndGet();
                            activeReaders.decrementAndGet();
                        } finally {
                            lock.readUnlock(t);
                        }
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        for (int w = 0; w < 2; w++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 2_000; i++) {
                        lock.writeLock();
                        try {
                            if (!writerInside.compareAndSet(false, true)) violations.incrementAndGet(); // writer/writer
                            if (activeReaders.get() != 0) violations.incrementAndGet();            // writer/reader
                            writerInside.set(false);
                        } finally {
                            lock.writeUnlock();
                        }
                        LockSupport.parkNanos(20_000);
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    stop.set(true);
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "lock hung");
        pool.shutdownNow();
        assertEquals(0, violations.get(), "mutual exclusion violated");
    }

    @Test
    void writerMayReenterAndTakeReadSide() {
        TopologyLock lock = new TopologyLock();
        lock.writeLock();
        lock.writeLock();                 // nested write
        int t = lock.readLock();          // read inside write: must not self-deadlock
        lock.readUnlock(t);
        lock.writeUnlock();
        lock.writeUnlock();
        int r = lock.readLock();          // lock fully released
        lock.readUnlock(r);
    }
}
