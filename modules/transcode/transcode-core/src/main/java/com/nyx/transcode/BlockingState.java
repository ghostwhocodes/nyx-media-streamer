package com.nyx.transcode;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

public final class BlockingState<T> {
    private static final long DEFAULT_TIMEOUT_MS = 30_000L;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private T currentValue;

    public BlockingState(T initialValue) {
        this.currentValue = initialValue;
    }

    public T getValue() {
        lock.lock();
        try {
            return currentValue;
        } finally {
            lock.unlock();
        }
    }

    public void update(T newValue) {
        lock.lock();
        try {
            currentValue = newValue;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public T first(Predicate<? super T> predicate) {
        return first(predicate, DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    public T first(Predicate<? super T> predicate, long timeout, TimeUnit unit) {
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(unit, "unit");

        long remaining = unit.toNanos(timeout);
        lock.lock();
        try {
            while (!predicate.test(currentValue)) {
                if (remaining <= 0L) {
                    throw new IllegalStateException("Timed out waiting for state transition from " + currentValue);
                }
                remaining = changed.awaitNanos(remaining);
            }
            return currentValue;
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for state transition", interruptedException);
        } finally {
            lock.unlock();
        }
    }
}
