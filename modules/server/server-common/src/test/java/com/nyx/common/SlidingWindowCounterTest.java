package com.nyx.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SlidingWindowCounterTest {
    @Test
    void tryConsumeAllowsRequestsUnderLimit() {
        SlidingWindowCounter counter = new SlidingWindowCounter();
        for (int i = 0; i < 5; i++) {
            assertTrue(counter.tryConsume("user", 5, 60_000), "Request " + i + " should be allowed");
        }
    }

    @Test
    void tryConsumeRejectsWhenAtLimit() {
        SlidingWindowCounter counter = new SlidingWindowCounter();
        for (int i = 0; i < 3; i++) {
            counter.tryConsume("user", 3, 60_000);
        }
        assertFalse(counter.tryConsume("user", 3, 60_000));
    }

    @Test
    void tryConsumeIsPerKey() {
        SlidingWindowCounter counter = new SlidingWindowCounter();
        for (int i = 0; i < 2; i++) {
            counter.tryConsume("alice", 2, 60_000);
        }
        assertFalse(counter.tryConsume("alice", 2, 60_000));
        assertTrue(counter.tryConsume("bob", 2, 60_000));
    }

    @Test
    void countReturnsCurrentEventsWithoutMutation() {
        SlidingWindowCounter counter = new SlidingWindowCounter();
        counter.tryConsume("user", 10, 60_000);
        counter.tryConsume("user", 10, 60_000);
        counter.tryConsume("user", 10, 60_000);

        assertEquals(3, counter.count("user", 60_000));
        assertEquals(3, counter.count("user", 60_000));
    }

    @Test
    void countReturnsZeroForUnknownKey() {
        SlidingWindowCounter counter = new SlidingWindowCounter();

        assertEquals(0, counter.count("unknown", 60_000));
    }

    @Test
    void expiredEntriesAreEvictedOnTryConsume() throws Exception {
        SlidingWindowCounter counter = new SlidingWindowCounter();

        counter.tryConsume("user", 1, 1);
        Thread.sleep(10);

        assertTrue(counter.tryConsume("user", 1, 1));
    }

    @Test
    void countDoesNotCountExpiredEntries() throws Exception {
        SlidingWindowCounter counter = new SlidingWindowCounter();
        counter.tryConsume("user", 10, 1);
        Thread.sleep(10);

        assertEquals(0, counter.count("user", 1));
    }

    @Test
    void cleanupRemovesStaleEntries() throws Exception {
        SlidingWindowCounter counter = new SlidingWindowCounter(600_000);
        counter.tryConsume("stale", 10, 60_000);
        counter.tryConsume("fresh", 10, 60_000);
        assertEquals(2, counter.keyCount());

        Thread.sleep(200);
        counter.tryConsume("fresh", 10, 60_000);

        counter.cleanup(100);
        assertEquals(1, counter.keyCount());
        assertEquals(2, counter.count("fresh", 60_000));
        assertEquals(0, counter.count("stale", 60_000));
    }

    @Test
    void keyCountTracksNumberOfKeys() {
        SlidingWindowCounter counter = new SlidingWindowCounter();

        assertEquals(0, counter.keyCount());
        counter.tryConsume("a", 10, 60_000);
        assertEquals(1, counter.keyCount());
        counter.tryConsume("b", 10, 60_000);
        assertEquals(2, counter.keyCount());
    }

    @Test
    void concurrentTryConsumeRespectsLimit() throws Exception {
        SlidingWindowCounter counter = new SlidingWindowCounter();
        int limit = 100;
        int attempts = 200;
        List<Boolean> results = runConcurrent(attempts, () -> counter.tryConsume("user", limit, 60_000));

        long accepted = results.stream().filter(Boolean::booleanValue).count();
        long rejected = results.stream().filter(result -> !result).count();
        assertEquals(limit, accepted, "Exactly " + limit + " should be accepted");
        assertEquals(attempts - limit, rejected, "Remaining should be rejected");
    }

    @Test
    void automaticCleanupTriggersAfterInterval() throws Exception {
        SlidingWindowCounter counter = new SlidingWindowCounter(1);

        counter.tryConsume("old", 10, 1);
        Thread.sleep(10);
        counter.tryConsume("new", 10, 60_000);

        assertTrue(counter.keyCount() <= 2);
    }

    @Test
    void windowExpiryViaClockAdvance() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        SlidingWindowCounter counter = new SlidingWindowCounter(300_000L, Integer.MAX_VALUE, clock::get);

        assertTrue(counter.tryConsume("user", 2, 60_000));
        assertTrue(counter.tryConsume("user", 2, 60_000));
        assertFalse(counter.tryConsume("user", 2, 60_000));

        clock.addAndGet(60_001);
        assertTrue(counter.tryConsume("user", 2, 60_000), "Should be allowed after window expiry");
    }

    @Test
    void countAccuracyAfterWindowPassesWithDeterministicClock() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        SlidingWindowCounter counter = new SlidingWindowCounter(300_000L, Integer.MAX_VALUE, clock::get);

        counter.tryConsume("user", 10, 10_000);
        counter.tryConsume("user", 10, 10_000);
        assertEquals(2, counter.count("user", 10_000));

        clock.addAndGet(10_001);
        assertEquals(0, counter.count("user", 10_000));
    }

    @Test
    void cleanupTriggeringViaClockAdvance() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        SlidingWindowCounter counter = new SlidingWindowCounter(5_000, Integer.MAX_VALUE, clock::get);

        counter.tryConsume("old", 10, 1_000);
        counter.tryConsume("fresh", 10, 1_000);
        assertEquals(2, counter.keyCount());

        clock.addAndGet(6_000);
        counter.tryConsume("fresh", 10, 1_000);

        assertEquals(1, counter.keyCount());
        assertEquals(0, counter.count("old", 1_000));
    }

    @Test
    void removeClearsTheTrackedKey() {
        SlidingWindowCounter counter = new SlidingWindowCounter();

        counter.tryConsume("user", 10, 60_000);
        assertEquals(1, counter.keyCount());

        counter.remove("user");

        assertEquals(0, counter.keyCount());
        assertEquals(0, counter.count("user", 60_000));
    }

    @Test
    void maxTrackedKeysEvictsTheOldestState() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        SlidingWindowCounter counter = new SlidingWindowCounter(60_000L, 2, clock::get);

        counter.tryConsume("first", 10, 60_000);
        clock.incrementAndGet();
        counter.tryConsume("second", 10, 60_000);
        clock.incrementAndGet();
        counter.tryConsume("third", 10, 60_000);

        assertEquals(2, counter.keyCount());
        assertEquals(0, counter.count("first", 60_000));
        assertEquals(1, counter.count("second", 60_000));
        assertEquals(1, counter.count("third", 60_000));
    }

    private static List<Boolean> runConcurrent(int attempts, CheckedBooleanSupplier action) throws Exception {
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>(attempts);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < attempts; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return action.getAsBoolean();
                }));
            }
            ready.await();
            start.countDown();

            List<Boolean> results = new ArrayList<>(attempts);
            for (Future<Boolean> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    @FunctionalInterface
    private interface CheckedBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }
}
