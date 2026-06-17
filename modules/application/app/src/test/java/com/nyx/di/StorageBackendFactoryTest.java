package com.nyx.di;

import com.nyx.common.storage.LocalStorageBackend;
import com.nyx.common.storage.S3StorageBackend;
import com.nyx.config.S3Config;
import com.nyx.config.StorageConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageBackendFactoryTest {
    @TempDir
    Path tempDir;

    @Test
    void createsLocalStorageBackendForLocalBackend() {
        StorageConfig config = new StorageConfig("local", tempDir);
        assertInstanceOf(LocalStorageBackend.class, StorageBackendFactory.create(config));
    }

    @Test
    void createsS3StorageBackendForS3BackendWithValidConfig() throws Exception {
        StorageConfig config = new StorageConfig(
            "s3",
            tempDir,
            new S3Config("test-bucket", "http://localhost:9000", "us-east-1", "minioadmin", "minioadmin", "", true)
        );
        var backend = StorageBackendFactory.create(config);
        assertInstanceOf(S3StorageBackend.class, backend);
        backend.close();
    }

    @Test
    void throwsForS3BackendWithMissingBucket() {
        StorageConfig config = new StorageConfig("s3", tempDir, new S3Config("", "", "us-east-1", "key", "secret", "", true));
        assertThrows(IllegalArgumentException.class, () -> StorageBackendFactory.create(config));
    }

    @Test
    void throwsForS3BackendWithMissingAccessKey() {
        StorageConfig config = new StorageConfig("s3", tempDir, new S3Config("bucket", "", "us-east-1", "", "secret", "", true));
        assertThrows(IllegalArgumentException.class, () -> StorageBackendFactory.create(config));
    }

    @Test
    void throwsForS3BackendWithMissingSecretKey() {
        StorageConfig config = new StorageConfig("s3", tempDir, new S3Config("bucket", "", "us-east-1", "key", "", "", true));
        assertThrows(IllegalArgumentException.class, () -> StorageBackendFactory.create(config));
    }

    @Test
    void throwsForS3BackendWithBlankBucket() {
        StorageConfig config = new StorageConfig("s3", tempDir, new S3Config("   ", "", "us-east-1", "key", "secret", "", true));
        assertThrows(IllegalArgumentException.class, () -> StorageBackendFactory.create(config));
    }

    @Test
    void throwsForS3BackendWithBlankAccessKey() {
        StorageConfig config = new StorageConfig("s3", tempDir, new S3Config("bucket", "", "us-east-1", "  ", "secret", "", true));
        assertThrows(IllegalArgumentException.class, () -> StorageBackendFactory.create(config));
    }

    @Test
    void throwsForS3BackendWithBlankSecretKey() {
        StorageConfig config = new StorageConfig("s3", tempDir, new S3Config("bucket", "", "us-east-1", "key", "   ", "", true));
        assertThrows(IllegalArgumentException.class, () -> StorageBackendFactory.create(config));
    }

    @Test
    void throwsForUnknownBackendType() {
        StorageConfig config = new StorageConfig("gcs", tempDir);
        assertThrows(IllegalArgumentException.class, () -> StorageBackendFactory.create(config));
    }

    @Test
    void errorMessageForUnknownBackendIncludesTheBackendName() {
        StorageConfig config = new StorageConfig("azure-blob", tempDir);
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> StorageBackendFactory.create(config));
        assertTrue(exception.getMessage().contains("azure-blob"));
    }

    @Test
    void localBackendCreatesDirectoryIfItDoesNotExist() {
        Path newDir = tempDir.resolve("sub/deep/cache");
        assertFalse(Files.exists(newDir));

        StorageConfig config = new StorageConfig("local", newDir);

        assertInstanceOf(LocalStorageBackend.class, StorageBackendFactory.create(config));
        assertTrue(Files.exists(newDir));
    }
}
