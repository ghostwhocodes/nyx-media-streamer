package com.nyx.http;

@FunctionalInterface
public interface TriConsumer<T, U, V> {
    void accept(T first, U second, V third);
}
