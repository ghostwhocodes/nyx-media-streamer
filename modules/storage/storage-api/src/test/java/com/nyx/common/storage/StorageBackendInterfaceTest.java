package com.nyx.common.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StorageBackendInterfaceTest {
    private InMemoryStorageBackend backend;

    @BeforeEach
    void setUp() {
        backend = new InMemoryStorageBackend();
    }

    @Test
    void listWithMetadataReturnsEmptyListForEmptyBackend() {
        assertThat(backend.listWithMetadata("any/prefix/")).isEmpty();
    }

    @Test
    void listWithMetadataReturnsEntriesWithSizeAndTimestamp() {
        long now = System.currentTimeMillis();
        backend.getObjects().put("cache/a.jpg", new InMemoryStorageBackend.StoredObject(
            new byte[42],
            "image/jpeg",
            Map.of(),
            now
        ));
        backend.getObjects().put("cache/b.jpg", new InMemoryStorageBackend.StoredObject(
            new byte[100],
            "image/jpeg",
            Map.of(),
            now - 5_000
        ));

        List<StorageEntryInfo> result = backend.listWithMetadata("cache/");

        assertThat(result).hasSize(2);
        assertThat(result).extracting(StorageEntryInfo::key).containsExactlyInAnyOrder("cache/a.jpg", "cache/b.jpg");
        assertThat(result).anySatisfy(entry -> {
            assertThat(entry.key()).isEqualTo("cache/a.jpg");
            assertThat(entry.sizeBytes()).isEqualTo(42L);
            assertThat(entry.lastModifiedEpochMillis()).isEqualTo(now);
        });
    }

    @Test
    void listWithMetadataSkipsKeysWithNullMetadata() {
        StorageBackend trickyBackend = new StorageBackend() {
            @Override
            public byte[] read(String key) {
                return null;
            }

            @Override
            public void write(String key, byte[] data, String contentType, Map<String, String> metadata) {
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
                return List.of("phantom/a.dat", "phantom/b.dat");
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
        };

        assertThat(trickyBackend.listWithMetadata("phantom/")).isEmpty();
    }

    @Test
    void storageRecordsRemainValueBased() {
        StorageEntryInfo first = new StorageEntryInfo("key1", 100L, 5_000L);
        StorageEntryInfo second = new StorageEntryInfo("key1", 100L, 5_000L);
        StorageMetadata metadata = new StorageMetadata(10L, 20L, "text/plain", Map.of("k", "v"));

        assertThat(first).isEqualTo(second).hasToString("StorageEntryInfo[key=key1, sizeBytes=100, lastModifiedEpochMillis=5000]");
        assertThat(metadata).isEqualTo(new StorageMetadata(10L, 20L, "text/plain", Map.of("k", "v")));
        assertThat(metadata.userMetadata()).containsEntry("k", "v");
    }

    @Test
    void listWithMetadataViaWriteApiReturnsCorrectEntries() {
        backend.write("ns/file1.dat", new byte[50], "application/octet-stream");
        backend.write("ns/file2.dat", new byte[75], "text/plain");
        backend.write("other/file3.dat", new byte[25]);

        List<StorageEntryInfo> entries = backend.listWithMetadata("ns/");

        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(StorageEntryInfo::key).containsExactlyInAnyOrder("ns/file1.dat", "ns/file2.dat");
        assertThat(entries).allSatisfy(entry -> assertThat(entry.lastModifiedEpochMillis()).isPositive());
    }
}
