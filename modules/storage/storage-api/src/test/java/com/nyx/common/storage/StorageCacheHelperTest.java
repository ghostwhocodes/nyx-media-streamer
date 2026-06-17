package com.nyx.common.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;

class StorageCacheHelperTest {
    @TempDir
    Path tempDir;

    private InMemoryStorageBackend backend;

    @BeforeEach
    void setUp() {
        backend = new InMemoryStorageBackend();
    }

    @Test
    void getOrGenerateReturnsGeneratedDataOnCacheMiss() throws Exception {
        Path source = createTestFile("source.dat", 100);

        StorageCacheHelper.CacheResult result = StorageCacheHelper.getOrGenerate(
            backend,
            "test/cache-miss",
            source,
            () -> "generated".getBytes(StandardCharsets.UTF_8)
        );

        assertThat(result.cacheHit()).isFalse();
        assertThat(result.data()).containsExactly("generated".getBytes(StandardCharsets.UTF_8));
        assertThat(backend.read("test/cache-miss")).isNotNull();
    }

    @Test
    void getOrGenerateReturnsCachedDataOnCacheHit() throws Exception {
        Path source = createTestFile("source.dat", 100);

        StorageCacheHelper.CacheResult first = StorageCacheHelper.getOrGenerate(
            backend,
            "test/cache-hit",
            source,
            () -> "first".getBytes(StandardCharsets.UTF_8)
        );
        AtomicBoolean generateCalled = new AtomicBoolean(false);
        StorageCacheHelper.CacheResult second = StorageCacheHelper.getOrGenerate(
            backend,
            "test/cache-hit",
            source,
            () -> {
                generateCalled.set(true);
                return "second".getBytes(StandardCharsets.UTF_8);
            }
        );

        assertThat(first.cacheHit()).isFalse();
        assertThat(second.cacheHit()).isTrue();
        assertThat(generateCalled.get()).isFalse();
        assertThat(second.data()).containsExactly(first.data());
    }

    @Test
    void getOrGenerateInvalidatesWhenSourceFileChanges() throws Exception {
        Path source = createTestFile("source.dat", 100);
        StorageCacheHelper.getOrGenerate(backend, "test/invalidate", source, () -> "v1".getBytes(StandardCharsets.UTF_8));

        Thread.sleep(50L);
        Files.write(source, new byte[200]);

        AtomicBoolean generateCalled = new AtomicBoolean(false);
        StorageCacheHelper.CacheResult result = StorageCacheHelper.getOrGenerate(
            backend,
            "test/invalidate",
            source,
            () -> {
                generateCalled.set(true);
                return "v2".getBytes(StandardCharsets.UTF_8);
            }
        );

        assertThat(result.cacheHit()).isFalse();
        assertThat(generateCalled.get()).isTrue();
        assertThat(new String(result.data(), StandardCharsets.UTF_8)).isEqualTo("v2");
    }

    @Test
    void getOrGenerateStoresContentTypeWhenProvided() throws Exception {
        Path source = createTestFile("source.dat", 100);

        StorageCacheHelper.getOrGenerate(
            backend,
            "test/content-type",
            source,
            "image/jpeg",
            () -> "data".getBytes(StandardCharsets.UTF_8)
        );

        assertThat(backend.metadata("test/content-type").contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void cleanupLruRemovesOldestEntriesWhenOverLimit() {
        backend.getObjects().put("pfx/a.dat", new InMemoryStorageBackend.StoredObject(new byte[20], null, Map.of(), 1_000));
        backend.getObjects().put("pfx/b.dat", new InMemoryStorageBackend.StoredObject(new byte[20], null, Map.of(), 2_000));

        StorageCacheHelper.cleanupLRU(backend, "pfx/", 10L, LoggerFactory.getLogger("test"));

        assertThat(backend.getObjects()).doesNotContainKeys("pfx/a.dat", "pfx/b.dat");
    }

    @Test
    void getOrGenerateSucceedsWhenBackendReadFails() throws Exception {
        Path source = createTestFile("source.dat", 100);
        StorageBackend failingBackend = new StorageBackend() {
            @Override
            public byte[] read(String key) throws RuntimeException {
                throw new RuntimeException("read failed");
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
                throw new RuntimeException("metadata failed");
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
                return 0L;
            }

            @Override
            public void close() {
            }
        };

        StorageCacheHelper.CacheResult result = StorageCacheHelper.getOrGenerate(
            failingBackend,
            "test/read-fail",
            source,
            () -> "fallback".getBytes(StandardCharsets.UTF_8)
        );

        assertThat(result.cacheHit()).isFalse();
        assertThat(new String(result.data(), StandardCharsets.UTF_8)).isEqualTo("fallback");
    }

    @Test
    void getOrGenerateSucceedsWhenBackendWriteFails() throws Exception {
        Path source = createTestFile("source.dat", 100);
        StorageBackend failingBackend = new StorageBackend() {
            @Override
            public byte[] read(String key) {
                return null;
            }

            @Override
            public void write(String key, byte[] data, String contentType, Map<String, String> metadata) {
                throw new RuntimeException("write failed");
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
                return 0L;
            }

            @Override
            public void close() {
            }
        };

        StorageCacheHelper.CacheResult result = StorageCacheHelper.getOrGenerate(
            failingBackend,
            "test/write-fail",
            source,
            () -> "generated".getBytes(StandardCharsets.UTF_8)
        );

        assertThat(result.cacheHit()).isFalse();
        assertThat(new String(result.data(), StandardCharsets.UTF_8)).isEqualTo("generated");
    }

    @Test
    void getOrGenerateRegeneratesWhenMetadataExistsButReadReturnsNull() throws Exception {
        VanishingStorageBackend vanishingBackend = new VanishingStorageBackend();
        Path sourcePath = tempDir.resolve("source.jpg");
        BufferedImage image = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, "jpg", sourcePath.toFile());

        String sourceSize = Long.toString(Files.size(sourcePath));
        String sourceMtime = Long.toString(Files.getLastModifiedTime(sourcePath).toMillis());
        vanishingBackend.write(
            "vanish/thumb.jpg",
            "will-vanish".getBytes(StandardCharsets.UTF_8),
            "image/jpeg",
            Map.of("sourceSize", sourceSize, "sourceMtime", sourceMtime)
        );

        StorageCacheHelper.CacheResult result = StorageCacheHelper.getOrGenerate(
            vanishingBackend,
            "vanish/thumb.jpg",
            sourcePath,
            "image/jpeg",
            () -> "regenerated".getBytes(StandardCharsets.UTF_8)
        );

        assertThat(result.cacheHit()).isFalse();
        assertThat(new String(result.data(), StandardCharsets.UTF_8)).isEqualTo("regenerated");
    }

    @Test
    void hashPathProducesStableShortHex() {
        String hash1 = StorageCacheHelper.hashPath("/some/path/file.jpg");
        String hash2 = StorageCacheHelper.hashPath("/some/path/file.jpg");

        assertThat(hash1).isEqualTo(hash2).hasSize(32).matches("[0-9a-f]+");
    }

    @Test
    void hashPathUsesUtf8WhenDefaultCharsetDiffers() throws Exception {
        String hash = runInJvmWithEncoding("ISO-8859-1", HashPathProbe.class.getName(), "/media/naïve/東京/file.jpg");

        assertThat(hash).isEqualTo("97c772f296a1e337b290ee1e2762ea09");
    }

    private Path createTestFile(String name, int size) throws IOException {
        Path file = tempDir.resolve(name);
        Files.write(file, new byte[size]);
        return file;
    }

    private String runInJvmWithEncoding(String encoding, String mainClass, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Dfile.encoding=" + encoding);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(mainClass);
        command.addAll(List.of(args));

        Process process = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(process.waitFor()).isZero();
        return output;
    }

    private static final class VanishingStorageBackend implements StorageBackend {
        private final InMemoryStorageBackend inner = new InMemoryStorageBackend();

        @Override
        public byte[] read(String key) {
            if (key.startsWith("vanish/")) {
                return null;
            }
            return inner.read(key);
        }

        @Override
        public void write(String key, byte[] data, String contentType, Map<String, String> metadata) {
            inner.write(key, data, contentType, metadata);
        }

        @Override
        public boolean exists(String key) {
            return inner.exists(key);
        }

        @Override
        public boolean delete(String key) {
            return inner.delete(key);
        }

        @Override
        public StorageMetadata metadata(String key) {
            return inner.metadata(key);
        }

        @Override
        public List<String> list(String prefix) {
            return inner.list(prefix);
        }

        @Override
        public int deletePrefix(String prefix) {
            return inner.deletePrefix(prefix);
        }

        @Override
        public long totalSize(String prefix) {
            return inner.totalSize(prefix);
        }

        @Override
        public void close() {
            inner.close();
        }
    }

    public static final class HashPathProbe {
        public static void main(String[] args) {
            System.out.print(StorageCacheHelper.hashPath(args[0]));
        }
    }
}
