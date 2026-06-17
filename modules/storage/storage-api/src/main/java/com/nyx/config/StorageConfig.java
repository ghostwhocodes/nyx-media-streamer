package com.nyx.config;

import java.nio.file.Path;

public record StorageConfig(String backend, Path localCacheDir, S3Config s3) {
    public StorageConfig {
        backend = backend == null ? "local" : backend;
        localCacheDir = localCacheDir == null ? Path.of("data/cache") : localCacheDir;
        s3 = s3 == null ? new S3Config() : s3;
    }

    public StorageConfig() {
        this("local", Path.of("data/cache"), new S3Config());
    }

    public StorageConfig(String backend, Path localCacheDir) {
        this(backend, localCacheDir, new S3Config());
    }

    public String getBackend() {
        return backend;
    }

    public Path getLocalCacheDir() {
        return localCacheDir;
    }

    public S3Config getS3() {
        return s3;
    }
}
