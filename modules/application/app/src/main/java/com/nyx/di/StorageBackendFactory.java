package com.nyx.di;

import com.nyx.common.storage.LocalStorageBackend;
import com.nyx.common.storage.S3StorageBackend;
import com.nyx.common.storage.StorageBackend;
import com.nyx.config.S3Config;
import com.nyx.config.StorageConfig;

public final class StorageBackendFactory {
    private StorageBackendFactory() {
    }

    public static StorageBackend create(StorageConfig config) {
        return switch (config.getBackend()) {
            case "local" -> new LocalStorageBackend(config.getLocalCacheDir());
            case "s3" -> {
                S3Config s3 = config.getS3();
                requireNotBlank(s3.getBucket(), "S3 bucket must be configured when storage.backend=s3");
                requireNotBlank(s3.getAccessKey(), "S3 access key must be configured when storage.backend=s3");
                requireNotBlank(s3.getSecretKey(), "S3 secret key must be configured when storage.backend=s3");
                yield new S3StorageBackend(s3);
            }
            default -> throw new IllegalArgumentException(
                "Unknown storage backend: '" + config.getBackend() + "'. Must be 'local' or 's3'"
            );
        };
    }

    private static void requireNotBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
