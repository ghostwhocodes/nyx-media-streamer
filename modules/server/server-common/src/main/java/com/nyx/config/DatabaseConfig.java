package com.nyx.config;

import java.nio.file.Path;
import java.util.Objects;

public record DatabaseConfig(
    Path dir,
    int poolSize,
    long idleTimeoutMs,
    long maxLifetimeMs
) {
    public DatabaseConfig {
        dir = Objects.requireNonNull(dir, "dir");
    }

    public DatabaseConfig(Path dir) {
        this(dir, 4, 600_000L, 1_800_000L);
    }

    public DatabaseConfig(Path dir, int poolSize) {
        this(dir, poolSize, 600_000L, 1_800_000L);
    }

    public Path getDir() {
        return dir;
    }

    public int getPoolSize() {
        return poolSize;
    }

    public long getIdleTimeoutMs() {
        return idleTimeoutMs;
    }

    public long getMaxLifetimeMs() {
        return maxLifetimeMs;
    }
}
