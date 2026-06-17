package com.nyx.common.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.config.S3Config;
import com.nyx.config.StorageConfig;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class StorageApiContractCoverageTest {

    @Test
    void storageMetadataAndEntryInfoAccessorsRemainStable() {
        Map<String, String> userMetadata = new HashMap<>(Map.of("source", "camera"));

        StorageMetadata full = new StorageMetadata(12L, 34L, "image/jpeg", userMetadata);
        StorageMetadata minimal = new StorageMetadata(90L, 123L);
        StorageMetadata typed = new StorageMetadata(91L, 124L, "text/plain");
        StorageEntryInfo entry = new StorageEntryInfo("cache/file.jpg", 12L, 34L);

        userMetadata.put("ignored", "later");

        assertEquals(12L, full.getSizeBytes());
        assertEquals(34L, full.getLastModifiedEpochMillis());
        assertEquals("image/jpeg", full.getContentType());
        assertEquals(Map.of("source", "camera"), full.getUserMetadata());
        assertThrows(UnsupportedOperationException.class, () -> full.getUserMetadata().put("x", "y"));

        assertEquals(90L, minimal.sizeBytes());
        assertEquals(123L, minimal.lastModifiedEpochMillis());
        assertEquals(Map.of(), minimal.userMetadata());

        assertEquals("text/plain", typed.getContentType());
        assertEquals(Map.of(), typed.getUserMetadata());

        assertEquals("cache/file.jpg", entry.getKey());
        assertEquals(12L, entry.getSizeBytes());
        assertEquals(34L, entry.getLastModifiedEpochMillis());
    }

    @Test
    void storageBackendDefaultWriteOverloadsForwardExpectedArguments() {
        RecordingStorageBackend backend = new RecordingStorageBackend();
        byte[] payload = new byte[] {1, 2, 3};
        Map<String, String> metadata = Map.of("k", "v");

        backend.write("cache/a.bin", payload);
        assertEquals("cache/a.bin", backend.lastKey);
        assertArrayEquals(payload, backend.lastData);
        assertEquals(null, backend.lastContentType);
        assertEquals(Map.of(), backend.lastMetadata);

        backend.write("cache/b.bin", payload, "application/octet-stream");
        assertEquals("application/octet-stream", backend.lastContentType);
        assertEquals(Map.of(), backend.lastMetadata);

        backend.write("cache/c.bin", payload, metadata);
        assertEquals(null, backend.lastContentType);
        assertEquals(metadata, backend.lastMetadata);
    }

    @Test
    void storageCacheHelperCacheResultAndCleanupShortCircuitRemainStable() {
        InMemoryStorageBackend backend = new InMemoryStorageBackend();
        byte[] payload = new byte[] {9, 8, 7};
        StorageCacheHelper.CacheResult result = new StorageCacheHelper.CacheResult(payload, true);

        backend.write("cache/file.dat", new byte[5], "application/octet-stream");
        StorageCacheHelper.cleanupLRU(backend, "cache/", 10L, LoggerFactory.getLogger("storage-api-test"));

        assertSame(payload, result.getData());
        assertTrue(result.isCacheHit());
        assertTrue(backend.exists("cache/file.dat"));
    }

    @Test
    void storageAndS3ConfigConstructorsKeepHistoricalDefaults() {
        StorageConfig defaulted = new StorageConfig(null, null, null);
        StorageConfig twoArg = new StorageConfig("s3", Path.of("/tmp/cache"));
        S3Config s3Defaults = new S3Config(null, null, null, null, null, null, false);

        assertEquals("local", defaulted.getBackend());
        assertEquals(Path.of("data/cache"), defaulted.getLocalCacheDir());
        assertEquals("us-east-1", defaulted.getS3().getRegion());
        assertTrue(defaulted.getS3().getPathStyleAccess());

        assertEquals("s3", twoArg.getBackend());
        assertEquals(Path.of("/tmp/cache"), twoArg.getLocalCacheDir());
        assertEquals("", twoArg.getS3().getBucket());

        assertEquals("", s3Defaults.getBucket());
        assertEquals("", s3Defaults.getEndpoint());
        assertEquals("us-east-1", s3Defaults.getRegion());
        assertEquals("", s3Defaults.getAccessKey());
        assertEquals("", s3Defaults.getSecretKey());
        assertEquals("", s3Defaults.getPrefix());
        assertFalse(s3Defaults.getPathStyleAccess());
    }

    private static final class RecordingStorageBackend implements StorageBackend {
        private String lastKey;
        private byte[] lastData;
        private String lastContentType;
        private Map<String, String> lastMetadata = Map.of();

        @Override
        public byte[] read(String key) {
            return null;
        }

        @Override
        public void write(String key, byte[] data, String contentType, Map<String, String> metadata) {
            this.lastKey = key;
            this.lastData = data;
            this.lastContentType = contentType;
            this.lastMetadata = metadata;
        }

        @Override
        public boolean exists(String key) {
            return false;
        }

        @Override
        public boolean delete(String key) {
            return false;
        }

        @Override
        public StorageMetadata metadata(String key) {
            return null;
        }

        @Override
        public List<String> list(String prefix) {
            return List.of();
        }

        @Override
        public int deletePrefix(String prefix) {
            return 0;
        }

        @Override
        public long totalSize(String prefix) {
            return 0;
        }

        @Override
        public void close() {
        }
    }
}
