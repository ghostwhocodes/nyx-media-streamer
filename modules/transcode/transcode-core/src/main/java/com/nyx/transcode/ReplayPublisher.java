package com.nyx.transcode;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ReplayPublisher<T> implements Flow.Publisher<T>, AutoCloseable {
    private static final long DEFAULT_TIMEOUT_MS = 5_000L;

    private final SubmissionPublisher<T> delegate;
    private final AtomicReference<T> lastValue = new AtomicReference<>();
    private final AtomicBoolean hasLastValue = new AtomicBoolean(false);

    public ReplayPublisher(Executor executor, int maxBufferCapacity) {
        this.delegate = new SubmissionPublisher<>(executor, maxBufferCapacity);
    }

    public void submit(T item) {
        lastValue.set(item);
        hasLastValue.set(true);
        delegate.submit(item);
    }

    public T first() {
        return first(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    public T first(long timeout, TimeUnit unit) {
        FirstValueSubscriber<T> subscriber = new FirstValueSubscriber<>();
        subscribe(subscriber);
        return subscriber.await(timeout, unit);
    }

    @Override
    public void subscribe(Flow.Subscriber<? super T> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        boolean hasReplayValue = hasLastValue.get();
        T replaySnapshot = hasReplayValue ? lastValue.get() : null;
        delegate.subscribe(new ReplayingSubscriber<>(subscriber, replaySnapshot, hasReplayValue));
    }

    @Override
    public void close() {
        delegate.close();
    }

    private static final class ReplayingSubscriber<T> implements Flow.Subscriber<T> {
        private final Flow.Subscriber<? super T> downstream;
        private final T replayValue;
        private final AtomicBoolean replayPending;

        private ReplayingSubscriber(Flow.Subscriber<? super T> downstream, T replayValue, boolean hasReplayValue) {
            this.downstream = downstream;
            this.replayValue = replayValue;
            this.replayPending = new AtomicBoolean(hasReplayValue);
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            downstream.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    if (n <= 0L) {
                        subscription.request(n);
                        return;
                    }
                    if (replayPending.compareAndSet(true, false)) {
                        downstream.onNext(replayValue);
                        if (n != Long.MAX_VALUE) {
                            long remaining = n - 1;
                            if (remaining > 0L) {
                                subscription.request(remaining);
                            }
                            return;
                        }
                    }
                    subscription.request(n);
                }

                @Override
                public void cancel() {
                    subscription.cancel();
                }
            });
        }

        @Override
        public void onNext(T item) {
            downstream.onNext(item);
        }

        @Override
        public void onError(Throwable throwable) {
            downstream.onError(throwable);
        }

        @Override
        public void onComplete() {
            downstream.onComplete();
        }
    }

    private static final class FirstValueSubscriber<T> implements Flow.Subscriber<T> {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final AtomicReference<T> value = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean completedWithoutValue = new AtomicBoolean(false);
        private Flow.Subscription subscription;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(T item) {
            value.compareAndSet(null, item);
            if (subscription != null) {
                subscription.cancel();
            }
            latch.countDown();
        }

        @Override
        public void onError(Throwable throwable) {
            failure.set(throwable);
            latch.countDown();
        }

        @Override
        public void onComplete() {
            completedWithoutValue.set(true);
            latch.countDown();
        }

        private T await(long timeout, TimeUnit unit) {
            try {
                if (!latch.await(timeout, unit)) {
                    throw new IllegalStateException("Timed out waiting for published value");
                }
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for published value", interruptedException);
            }
            Throwable throwable = failure.get();
            if (throwable != null) {
                throw sneakyThrow(throwable);
            }
            T publishedValue = value.get();
            if (completedWithoutValue.get() && publishedValue == null) {
                throw new NoSuchElementException("Publisher completed without emitting a value");
            }
            return publishedValue;
        }

        private static RuntimeException sneakyThrow(Throwable throwable) {
            FirstValueSubscriber.<RuntimeException>throwUnchecked(throwable);
            throw new AssertionError("Unreachable");
        }

        @SuppressWarnings("unchecked")
        private static <E extends Throwable> void throwUnchecked(Throwable throwable) throws E {
            throw (E) throwable;
        }
    }
}
