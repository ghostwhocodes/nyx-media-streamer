package com.nyx.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.common.storage.InMemoryStorageBackend;
import com.nyx.common.storage.StorageCacheHelper;
import com.nyx.media.contracts.ImageTransformFit;
import com.nyx.media.contracts.ImageTransformRequest;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImageTransformServiceTest {
    private Path tempDir;
    private Path sourceDir;
    private InMemoryStorageBackend backend;
    private StrippedImageCache strippedImageCache;
    private ImageTransformService service;

    @BeforeEach
    void setup() throws Exception {
        tempDir = Files.createTempDirectory("nyx-image-transform");
        sourceDir = Files.createDirectories(tempDir.resolve("source"));
        backend = new InMemoryStorageBackend();
        strippedImageCache = new StrippedImageCache(new ExifExtractor(), backend);
        service = new ImageTransformService(strippedImageCache, backend);
    }

    @AfterEach
    void teardown() throws Exception {
        ImageCoreTestSupport.deleteRecursively(tempDir);
    }

    @Test
    void planResolvesContainRequestsIntoStableOutputDimensions() throws Exception {
        Path source = ImageCoreTestSupport.createImage(sourceDir, "photo.jpg", 1200, 800);

        ImageTransformPlan plan = service.plan(
            source,
            new ImageTransformRequest(null, null, 600, 600, null, ImageTransformFit.CONTAIN)
        );

        assertEquals(1200, plan.getSourceWidth());
        assertEquals(800, plan.getSourceHeight());
        assertEquals(600, plan.getOutputWidth());
        assertEquals(400, plan.getOutputHeight());
        assertEquals(600, plan.getScaledWidth());
        assertEquals(400, plan.getScaledHeight());
        assertEquals("fmt-jpg_fit-contain_out-600x400_scaled-600x400_crop-0x0_q-default", plan.getCacheKey());
        assertTrue(plan.getRequiresTransformation());
    }

    @Test
    void planResolvesCoverRequestsWithCenteredCrop() throws Exception {
        Path source = ImageCoreTestSupport.createImage(sourceDir, "cover.jpg", 1200, 800);

        ImageTransformPlan plan = service.plan(
            source,
            new ImageTransformRequest(300, 300, null, null, null, ImageTransformFit.COVER)
        );

        assertEquals(450, plan.getScaledWidth());
        assertEquals(300, plan.getScaledHeight());
        assertEquals(300, plan.getOutputWidth());
        assertEquals(300, plan.getOutputHeight());
        assertEquals(75, plan.getCropX());
        assertEquals(0, plan.getCropY());
        assertTrue(plan.getRequiresCrop());
    }

    @Test
    void getImageCachesTransformedOutputsUsingResolvedPlanKey() throws Exception {
        Path source = ImageCoreTestSupport.createImage(sourceDir, "cached.jpg", 1200, 800);

        ImageTransformOutput output = service.getImage(
            source,
            new ImageTransformRequest(300, 300, null, null, 80, ImageTransformFit.COVER)
        );

        assertTrue(output.getBytes().length > 0);
        BufferedImage transformed = ImageIO.read(new java.io.ByteArrayInputStream(output.getBytes()));
        assertEquals(300, transformed.getWidth());
        assertEquals(300, transformed.getHeight());

        String hash = StorageCacheHelper.hashPath(source.toAbsolutePath().toString());
        String storageKey = "image-transforms/" + hash + "/" + output.getPlan().getCacheKey() + ".jpg";
        assertTrue(backend.getObjects().containsKey(storageKey));
    }

    @Test
    void getImagePreservesStrippedPathForPassthroughRequests() throws Exception {
        Path source = ImageCoreTestSupport.createImage(sourceDir, "passthrough.jpg", 900, 600);

        ImageTransformOutput output = service.getImage(source, new ImageTransformRequest());

        assertTrue(output.getBytes().length > 0);
        assertFalse(output.getPlan().getRequiresTransformation());
        assertTrue(backend.getObjects().keySet().stream().anyMatch(key -> key.startsWith("stripped/")));
        assertFalse(backend.getObjects().keySet().stream().anyMatch(key -> key.startsWith("image-transforms/")));
    }

    @Test
    void cleanupStorageCacheRemovesOldestTransformedEntriesWhenOverLimit() {
        ImageTransformService limitedService = new ImageTransformService(strippedImageCache, 10L, backend);

        backend.getObjects().put(
            "image-transforms/a/first.jpg",
            new InMemoryStorageBackend.StoredObject(new byte[20], "image/jpeg", java.util.Map.of(), 1000L)
        );
        backend.getObjects().put(
            "image-transforms/b/second.jpg",
            new InMemoryStorageBackend.StoredObject(new byte[20], "image/jpeg", java.util.Map.of(), 2000L)
        );

        limitedService.cleanupStorageCache();

        assertFalse(backend.getObjects().containsKey("image-transforms/a/first.jpg"));
    }
}
