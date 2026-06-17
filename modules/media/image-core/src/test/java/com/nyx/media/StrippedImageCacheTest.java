package com.nyx.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.common.storage.InMemoryStorageBackend;
import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StrippedImageCacheTest {
    private Path tempDir;
    private Path sourceDir;
    private InMemoryStorageBackend backend;

    @BeforeEach
    void setup() throws Exception {
        tempDir = Files.createTempDirectory("nyx-stripped-cache");
        sourceDir = Files.createDirectories(tempDir.resolve("source"));
        backend = new InMemoryStorageBackend();
    }

    @AfterEach
    void teardown() throws Exception {
        ImageCoreTestSupport.deleteRecursively(tempDir);
    }

    @Test
    void getStrippedImageReturnsBytesForJpegAndPng() throws Exception {
        StrippedImageCache cache = new StrippedImageCache(new ExifExtractor(), backend);
        Path jpeg = ImageCoreTestSupport.createImage(sourceDir, "test.jpg", 10, 10, Color.BLUE);
        Path png = ImageCoreTestSupport.createImage(sourceDir, "test.png", 10, 10, Color.RED);

        assertTrue(cache.getStrippedImage(jpeg).length > 0);
        assertTrue(cache.getStrippedImage(png).length > 0);
    }

    @Test
    void getStrippedImageReturnsCachedResultOnSecondCall() throws Exception {
        StrippedImageCache cache = new StrippedImageCache(new ExifExtractor(), backend);
        Path source = ImageCoreTestSupport.createImage(sourceDir, "cached.jpg", 10, 10);

        byte[] first = cache.getStrippedImage(source);
        byte[] second = cache.getStrippedImage(source);

        assertArrayEquals(first, second);
    }

    @Test
    void getStrippedImageInvalidatesWhenSourceChanges() throws Exception {
        StrippedImageCache cache = new StrippedImageCache(new ExifExtractor(), backend);
        Path source = ImageCoreTestSupport.createImage(sourceDir, "change.jpg", 10, 10);

        cache.getStrippedImage(source);
        Thread.sleep(50L);
        ImageCoreTestSupport.createImage(sourceDir, "change.jpg", 20, 20);

        byte[] result = cache.getStrippedImage(source);
        assertTrue(result.length > 0);
    }

    @Test
    void getStrippedImageStoresInBackendAndPreservesExtension() throws Exception {
        StrippedImageCache cache = new StrippedImageCache(new ExifExtractor(), backend);
        Path source = ImageCoreTestSupport.createImage(sourceDir, "stored.png", 10, 10);

        cache.getStrippedImage(source);

        var storedKeys = backend.getObjects().keySet().stream().filter(key -> key.startsWith("stripped/")).toList();
        assertEquals(1, storedKeys.size());
        assertTrue(storedKeys.getFirst().endsWith("/stripped.png"));
    }

    @Test
    void cleanupStorageCacheRemovesOldestWhenOverLimit() {
        StrippedImageCache cache = new StrippedImageCache(new ExifExtractor(), 10L, null, 60, backend);

        backend.getObjects().put(
            "stripped/a/stripped.jpg",
            new InMemoryStorageBackend.StoredObject(new byte[20], null, java.util.Map.of(), 1000L)
        );
        backend.getObjects().put(
            "stripped/b/stripped.jpg",
            new InMemoryStorageBackend.StoredObject(new byte[20], null, java.util.Map.of(), 2000L)
        );

        cache.cleanupStorageCache();

        assertFalse(backend.getObjects().containsKey("stripped/a/stripped.jpg"));
    }

    @Test
    void schedulerConstructionLaunchesCleanupTask() throws Exception {
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            new StrippedImageCache(new ExifExtractor(), 1024L * 1024 * 1024, scheduler, 0, new InMemoryStorageBackend());
            Thread.sleep(100L);
        } finally {
            scheduler.shutdownNow();
        }
    }
}
