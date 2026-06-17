package com.nyx.concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Consumer;

public final class BlockingStream<T> {
    @FunctionalInterface
    public interface Collector<T> {
        void collect(Emitter<T> emitter);
    }

    @FunctionalInterface
    public interface Emitter<T> {
        boolean emit(T item);
    }

    private final Collector<T> collector;

    public BlockingStream(Collector<T> collector) {
        this.collector = Objects.requireNonNull(collector, "collector");
    }

    public void collect(Consumer<T> action) {
        collector.collect(item -> {
            action.accept(item);
            return true;
        });
    }

    public T first() {
        final boolean[] found = {false};
        final Object[] value = {null};
        collector.collect(item -> {
            value[0] = item;
            found[0] = true;
            return false;
        });
        if (!found[0]) {
            throw new NoSuchElementException("BlockingStream completed without emitting a value");
        }
        @SuppressWarnings("unchecked")
        T cast = (T) value[0];
        return cast;
    }

    public BlockingStream<T> take(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must be non-negative");
        }
        return new BlockingStream<>(downstream -> {
            if (count == 0) {
                return;
            }
            final int[] remaining = {count};
            collector.collect(item -> {
                if (remaining[0] <= 0) {
                    return false;
                }
                remaining[0] -= 1;
                return downstream.emit(item) && remaining[0] > 0;
            });
        });
    }

    public List<T> toList() {
        List<T> values = new ArrayList<>();
        collect(values::add);
        return List.copyOf(values);
    }
}
