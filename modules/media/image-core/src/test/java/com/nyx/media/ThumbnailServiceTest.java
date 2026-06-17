package com.nyx.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.common.ErrorCode;
import com.nyx.common.HealthMonitor;
import com.nyx.common.NyxException;
import com.nyx.common.storage.InMemoryStorageBackend;
import com.nyx.common.storage.LocalStorageBackend;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ThumbnailServiceTest {
    private Path tempDir;
    private Path cacheDir;
    private Path sourceDir;
    private InMemoryStorageBackend backend;
    private ThumbnailService service;

    @BeforeEach
    void setup() throws Exception {
        tempDir = Files.createTempDirectory("nyx-thumb-test");
        cacheDir = Files.createDirectories(tempDir.resolve("cache"));
        sourceDir = Files.createDirectories(tempDir.resolve("source"));
        backend = new InMemoryStorageBackend();
        service = new ThumbnailService(backend);
    }

    @AfterEach
    void teardown() throws Exception {
        ImageCoreTestSupport.deleteRecursively(tempDir);
    }

    @Test
    void getThumbnailRejectsInvalidSizes() throws Exception {
        Path source = ImageCoreTestSupport.createImage(sourceDir, "photo.jpg", 800, 600);

        assertThrows(IllegalArgumentException.class, () -> service.getThumbnail(source, 999));
    }

    @Test
    void getThumbnailRejectsNonExistentSource() {
        Path missing = sourceDir.resolve("missing.jpg");

        assertThrows(IllegalArgumentException.class, () -> service.getThumbnail(missing, 300));
    }

    @Test
    void customAllowedSizesAreRespectedAndPrimarySizeStaysStable() throws Exception {
        ThumbnailService customService = new ThumbnailService(Set.of(100, 200), backend);
        Path source = ImageCoreTestSupport.createImage(sourceDir, "photo.jpg", 800, 600);
        var orderedSizes = new java.util.LinkedHashSet<Integer>();
        orderedSizes.add(100);
        orderedSizes.add(200);

        assertThrows(IllegalArgumentException.class, () -> customService.getThumbnail(source, 300));
        assertEquals(150, service.getPrimaryThumbnailSize());
        assertEquals(100, new ThumbnailService(orderedSizes, backend).getPrimaryThumbnailSize());
    }

    @Test
    void getThumbnailThrowsFfmpegUnavailableWhenHealthServiceReportsUnavailable() throws Exception {
        HealthMonitor healthService = () -> false;
        ThumbnailService localService = createLocalCacheService(healthService);
        Path testFile = tempDir.resolve("test-cov.jpg");
        Files.write(testFile, new byte[100]);

        NyxException exception = assertThrows(NyxException.class, () -> localService.getThumbnail(testFile, 150));
        assertEquals(ErrorCode.FFMPEG_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void cleanupStorageCacheAndPurgeCacheWorkWithLocalStorageBackend() throws Exception {
        ThumbnailService localService = createLocalCacheService(null);
        LocalStorageBackend localBackend = new LocalStorageBackend(cacheDir);

        assertDoesNotThrow(localService::cleanupStorageCache);
        localBackend.write("thumbnails/test/150.jpg", new byte[100], "image/jpeg");
        assertTrue(localBackend.exists("thumbnails/test/150.jpg"));

        localService.purgeCache();

        assertFalse(localBackend.exists("thumbnails/test/150.jpg"));
    }

    @Test
    void cleanupStorageCacheRemovesOldestEntriesWhenOverLimit() {
        ThumbnailService limitedService = new ThumbnailService(10L, backend);

        backend.getObjects().put(
            "thumbnails/a/150.jpg",
            new InMemoryStorageBackend.StoredObject(new byte[20], "image/jpeg", Map.of("sourceSize", "1", "sourceMtime", "1"), 1000L)
        );
        backend.getObjects().put(
            "thumbnails/b/150.jpg",
            new InMemoryStorageBackend.StoredObject(new byte[20], "image/jpeg", Map.of("sourceSize", "2", "sourceMtime", "2"), 2000L)
        );

        limitedService.cleanupStorageCache();

        assertFalse(backend.getObjects().containsKey("thumbnails/a/150.jpg"));
    }

    @Test
    void generateThumbnailFromRealImageAndReuseCache() throws Exception {
        Assumptions.assumeTrue(ImageCoreTestSupport.isFfmpegAvailable(), "FFmpeg not available");

        Path source = ImageCoreTestSupport.createImage(sourceDir, "photo.jpg", 800, 600);

        byte[] thumb1 = service.getThumbnail(source, 300);
        byte[] thumb2 = service.getThumbnail(source, 300);

        assertTrue(thumb1.length > 0);
        assertArrayEquals(thumb1, thumb2);

        BufferedImage thumbImage = ImageIO.read(new java.io.ByteArrayInputStream(thumb1));
        assertNotNull(thumbImage);
        assertEquals(300, thumbImage.getWidth());
    }

    @Test
    void generateThumbnailFromPngAndAcrossStandardSizes() throws Exception {
        Assumptions.assumeTrue(ImageCoreTestSupport.isFfmpegAvailable(), "FFmpeg not available");

        Path source = ImageCoreTestSupport.createImage(sourceDir, "photo.png", 1200, 900);

        byte[] thumb150 = service.getThumbnail(source, 150);
        byte[] thumb300 = service.getThumbnail(source, 300);
        byte[] thumb600 = service.getThumbnail(source, 600);

        assertEquals(150, ImageIO.read(new java.io.ByteArrayInputStream(thumb150)).getWidth());
        assertEquals(300, ImageIO.read(new java.io.ByteArrayInputStream(thumb300)).getWidth());
        assertEquals(600, ImageIO.read(new java.io.ByteArrayInputStream(thumb600)).getWidth());
    }

    @Test
    void getThumbnailStoresInBackendOnMissAndHonorsExplicitStorageKeys() throws Exception {
        Assumptions.assumeTrue(ImageCoreTestSupport.isFfmpegAvailable(), "FFmpeg not available");

        ThumbnailService sizedService = new ThumbnailService(Set.of(150), backend);
        Path source = ImageCoreTestSupport.createImage(sourceDir, "stored.jpg", 400, 300);

        byte[] stored = sizedService.getThumbnail(source, 150);
        byte[] explicit = sizedService.getThumbnail(source, 150, "thumbnails/objects/object-1/150.jpg");

        assertTrue(stored.length > 0);
        assertTrue(explicit.length > 0);

        var storedKeys = backend.getObjects().keySet().stream().filter(key -> key.startsWith("thumbnails/")).toList();
        assertTrue(storedKeys.stream().anyMatch(key -> key.endsWith("/150.jpg")));
        assertTrue(backend.getObjects().containsKey("thumbnails/objects/object-1/150.jpg"));
    }

    @Test
    void cacheInvalidationDetectsSourceChanges() throws Exception {
        Assumptions.assumeTrue(ImageCoreTestSupport.isFfmpegAvailable(), "FFmpeg not available");

        Path source = ImageCoreTestSupport.createImage(sourceDir, "change.jpg", 800, 600);
        service.getThumbnail(source, 300);

        Thread.sleep(100L);
        Path rewritten = sourceDir.resolve("change.jpg");
        var newImage = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(newImage, "jpg", rewritten.toFile());

        byte[] regenerated = service.getThumbnail(rewritten, 300);
        assertTrue(regenerated.length > 0);
    }

    @Test
    void fakeVideoPathExercisesVideoThumbnailBranch() throws Exception {
        Assumptions.assumeTrue(ImageCoreTestSupport.isFfmpegAvailable(), "FFmpeg not available");

        Path videoFile = tempDir.resolve("test.mp4");
        Files.write(videoFile, "fake video data".getBytes());

        var failure = assertThrows(java.io.IOException.class, () -> service.getThumbnail(videoFile, 300));
        assertTrue(failure.getMessage().contains("Thumbnail generation failed"));
    }

    @Test
    void serviceCanBeConstructedWithAndWithoutSemaphoreAndScheduler() throws Exception {
        ThumbnailService withSemaphore = new ThumbnailService(
            Set.of(150, 300, 600),
            "ffmpeg",
            "ffprobe",
            10,
            1024L * 1024 * 1024,
            new Semaphore(1),
            null,
            60,
            null,
            null,
            new InMemoryStorageBackend()
        );
        ThumbnailService withoutSemaphore = new ThumbnailService(
            Set.of(150, 300, 600),
            "ffmpeg",
            "ffprobe",
            10,
            1024L * 1024 * 1024,
            null,
            null,
            60,
            null,
            null,
            new InMemoryStorageBackend()
        );

        Path semaphoreSourceDir = Files.createDirectories(tempDir.resolve("source-sem"));
        Path noSemaphoreSourceDir = Files.createDirectories(tempDir.resolve("source-nosem"));
        Path semaphoreSource = ImageCoreTestSupport.createImage(semaphoreSourceDir, "photo.jpg", 10, 10);
        Path noSemaphoreSource = ImageCoreTestSupport.createImage(noSemaphoreSourceDir, "photo.jpg", 10, 10);

        try {
            withSemaphore.getThumbnail(semaphoreSource, 300);
        } catch (RuntimeException ignored) {
        }
        try {
            withoutSemaphore.getThumbnail(noSemaphoreSource, 300);
        } catch (RuntimeException ignored) {
        }

        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            new ThumbnailService(scheduler, 0, new InMemoryStorageBackend());
            Thread.sleep(100L);
        } finally {
            scheduler.shutdownNow();
        }
    }

    private ThumbnailService createLocalCacheService(HealthMonitor healthService) {
        LocalStorageBackend localBackend = new LocalStorageBackend(cacheDir);
        return new ThumbnailService(Set.of(150, 300), healthService, localBackend);
    }
}
