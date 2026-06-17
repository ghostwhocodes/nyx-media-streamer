package com.nyx.common;

public interface ManagedService {
    default void start() {
    }

    void shutdown();
}
