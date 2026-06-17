package com.nyx.common;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Thread-safe sliding window rate counter.
 *
 * Tracks timestamped events per key (e.g. IP address, user ID) using a
 * synchronized {@link ArrayDeque} per key. Compound check-then-add operations
 * are atomic within the per-key lock, avoiding the TOCTOU bug that arises when
 * a concurrent deque is read and then mutated in a separate step.
 *
 * Stale map entries (keys whose most-recent timestamp exceeds the cleanup
 * interval) are pruned lazily: at most once per cleanup interval a
 * {@link #tryConsume(String, int, long)} call will sweep the entire map.
 */
public final class SlidingWindowCounter {
    private static final long DEFAULT_CLEANUP_INTERVAL_MS = 300_000L;

    private final long cleanupIntervalMs;
    private final int maxTrackedKeys;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, WindowState> windows = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> insertionOrder = new ConcurrentLinkedDeque<>();
    private final AtomicLong lastCleanup;

    public SlidingWindowCounter() {
        this(DEFAULT_CLEANUP_INTERVAL_MS, Integer.MAX_VALUE, System::currentTimeMillis);
    }

    public SlidingWindowCounter(long cleanupIntervalMs) {
        this(cleanupIntervalMs, Integer.MAX_VALUE, System::currentTimeMillis);
    }

    public SlidingWindowCounter(long cleanupIntervalMs, int maxTrackedKeys) {
        this(cleanupIntervalMs, maxTrackedKeys, System::currentTimeMillis);
    }

    public SlidingWindowCounter(long cleanupIntervalMs, int maxTrackedKeys, LongSupplier clock) {
        this.cleanupIntervalMs = cleanupIntervalMs;
        this.maxTrackedKeys = maxTrackedKeys;
        this.clock = clock;
        this.lastCleanup = new AtomicLong(clock.getAsLong());
    }

    /**
     * Atomically checks whether {@code key} is under {@code maxCount} events within
     * the last {@code windowMs} milliseconds and, if so, records a new event.
     */
    public boolean tryConsume(String key, int maxCount, long windowMs) {
        maybeCleanup();
        WindowState state = getOrCreateState(key);
        synchronized (state.lock) {
            long now = clock.getAsLong();
            evict(state.timestamps, now - windowMs);
            if (state.timestamps.size() >= maxCount) {
                return false;
            }
            state.timestamps.addLast(now);
            return true;
        }
    }

    /**
     * Returns the number of events for {@code key} within the last {@code windowMs}
     * milliseconds. This is read-only and does not mutate state.
     */
    public int count(String key, long windowMs) {
        WindowState state = windows.get(key);
        if (state == null) {
            return 0;
        }

        long cutoff = clock.getAsLong() - windowMs;
        synchronized (state.lock) {
            int count = 0;
            for (Long timestamp : state.timestamps) {
                if (timestamp >= cutoff) {
                    count++;
                }
            }
            return count;
        }
    }

    public void remove(String key) {
        windows.remove(key);
    }

    public int keyCount() {
        return windows.size();
    }

    /**
     * Removes map entries whose most-recent timestamp is older than
     * {@code staleThresholdMs}, or that are empty.
     */
    public void cleanup() {
        cleanup(cleanupIntervalMs);
    }

    public void cleanup(long staleThresholdMs) {
        long cutoff = clock.getAsLong() - staleThresholdMs;
        windows.entrySet().removeIf(entry -> isStale(entry, cutoff));
    }

    private boolean isStale(Map.Entry<String, WindowState> entry, long cutoff) {
        WindowState state = entry.getValue();
        synchronized (state.lock) {
            Long lastTimestamp = state.timestamps.peekLast();
            return lastTimestamp == null || lastTimestamp < cutoff;
        }
    }

    private WindowState getOrCreateState(String key) {
        WindowState existing = windows.get(key);
        if (existing != null) {
            return existing;
        }

        WindowState newState = new WindowState();
        WindowState raced = windows.putIfAbsent(key, newState);
        if (raced != null) {
            return raced;
        }

        insertionOrder.addLast(key);
        trimToMaxTrackedKeys(key);
        return newState;
    }

    private void trimToMaxTrackedKeys(String currentKey) {
        while (windows.size() > maxTrackedKeys) {
            String candidate = insertionOrder.pollFirst();
            if (candidate == null) {
                return;
            }
            if (candidate.equals(currentKey)) {
                insertionOrder.addLast(candidate);
                continue;
            }
            windows.remove(candidate);
        }
    }

    private static void evict(ArrayDeque<Long> deque, long cutoff) {
        while (true) {
            Long head = deque.peekFirst();
            if (head == null || head >= cutoff) {
                return;
            }
            deque.removeFirst();
        }
    }

    private void maybeCleanup() {
        long now = clock.getAsLong();
        long last = lastCleanup.get();
        if (now - last > cleanupIntervalMs && lastCleanup.compareAndSet(last, now)) {
            cleanup();
        }
    }

    private static final class WindowState {
        private final Object lock = new Object();
        private final ArrayDeque<Long> timestamps = new ArrayDeque<>();
    }
}
