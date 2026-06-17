package com.nyx.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.nyx.common.DatabaseResources;
import com.nyx.config.DatabaseConfig;
import com.nyx.media.contracts.MediaKind;
import com.nyx.media.contracts.MediaThumbnail;
import com.nyx.media.contracts.MediaThumbnailKind;
import com.nyx.media.contracts.MediaThumbnailStatus;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaThumbnailServiceTest {
    @TempDir
    Path tempDir;

    private final List<HikariDataSource> dataSources = new ArrayList<>();

    @AfterEach
    void teardown() {
        ModuleMediaTestSupport.closeDataSources(dataSources);
    }

    private TestServices createServices(int poolSize) {
        DatabaseResources resources = MediaObjectService.createDatabase(tempDir, new DatabaseConfig(tempDir, poolSize));
        dataSources.add(resources.getDataSource());
        return new TestServices(new MediaObjectService(resources.getJdbi()), new MediaThumbnailService(resources.getJdbi()));
    }

    private TestServices createServices() {
        return createServices(1);
    }

    @Test
    void ensurePlaceholderSeedsMediaKindSpecificPrimaryThumbnailState() throws Exception {
        TestServices services = createServices();
        var imageObject = services.mediaObjectService.upsertPrimaryPath(
            ModuleMediaTestSupport.mediaObjectUpsertRequest(
                ModuleMediaTestSupport.writeMediaFile(tempDir.resolve("library").resolve("poster.jpg")),
                MediaKind.IMAGE
            )
        );
        var audioObject = services.mediaObjectService.upsertPrimaryPath(
            ModuleMediaTestSupport.mediaObjectUpsertRequest(
                ModuleMediaTestSupport.writeMediaFile(tempDir.resolve("library").resolve("song.flac")),
                MediaKind.AUDIO
            )
        );

        var imagePlaceholder = services.mediaThumbnailService.ensurePlaceholder(imageObject);
        var audioPlaceholder = services.mediaThumbnailService.ensurePlaceholder(audioObject);

        assertEquals(MediaThumbnailKind.PLACEHOLDER, imagePlaceholder.kind());
        assertEquals(MediaThumbnailStatus.PENDING, imagePlaceholder.status());
        assertEquals(MediaThumbnailKind.PLACEHOLDER, audioPlaceholder.kind());
        assertEquals(MediaThumbnailStatus.MISSING, audioPlaceholder.status());
    }

    @Test
    void markReadyPromotesThePrimaryObjectThumbnailWithoutChangingIdentity() throws Exception {
        TestServices services = createServices();
        var imageObject = services.mediaObjectService.upsertPrimaryPath(
            ModuleMediaTestSupport.mediaObjectUpsertRequest(
                ModuleMediaTestSupport.writeMediaFile(tempDir.resolve("library").resolve("poster.jpg")),
                MediaKind.IMAGE
            )
        );

        var placeholder = services.mediaThumbnailService.ensurePlaceholder(imageObject);
        String storageKey = MediaThumbnailService.buildStorageKey(imageObject.objectId(), 150);
        var ready = services.mediaThumbnailService.markReady(imageObject.objectId(), storageKey, 150);
        var reference = services.mediaThumbnailService.primaryThumbnailReference(
            imageObject.objectId(),
            "/api/v1/images/thumb?path=library/poster.jpg&size=150"
        );

        assertEquals(placeholder.thumbnailId(), ready.thumbnailId());
        assertEquals(MediaThumbnailKind.THUMBNAIL, ready.kind());
        assertEquals(MediaThumbnailStatus.READY, ready.status());
        assertEquals(storageKey, ready.storageKey());
        assertNotNull(reference);
        assertEquals(MediaThumbnailStatus.READY, reference.status());
        assertEquals("/api/v1/images/thumb?path=library/poster.jpg&size=150", reference.url());
    }

    @Test
    void primaryThumbnailCreationIsSafeUnderConcurrentFirstAccess() throws Exception {
        TestServices services = createServices(4);
        var imageObject = services.mediaObjectService.upsertPrimaryPath(
            ModuleMediaTestSupport.mediaObjectUpsertRequest(
                ModuleMediaTestSupport.writeMediaFile(tempDir.resolve("library").resolve("poster-race.jpg")),
                MediaKind.IMAGE
            )
        );
        String storageKey = MediaThumbnailService.buildStorageKey(imageObject.objectId(), 150);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var placeholderFutures = executor.invokeAll(List.<Callable<MediaThumbnail>>of(
                () -> services.mediaThumbnailService.ensurePlaceholder(imageObject),
                () -> services.mediaThumbnailService.ensurePlaceholder(imageObject)
            ));
            List<MediaThumbnail> placeholders = List.of(placeholderFutures.get(0).get(), placeholderFutures.get(1).get());
            assertEquals(1, placeholders.stream().map(item -> item.thumbnailId()).distinct().count());

            var readyFutures = executor.invokeAll(List.<Callable<MediaThumbnail>>of(
                () -> services.mediaThumbnailService.markReady(imageObject.objectId(), storageKey, 150),
                () -> services.mediaThumbnailService.markReady(imageObject.objectId(), storageKey, 150)
            ));
            List<MediaThumbnail> readyResults = List.of(readyFutures.get(0).get(), readyFutures.get(1).get());

            var primary = services.mediaThumbnailService.getPrimaryThumbnail(imageObject.objectId());
            assertNotNull(primary);
            assertEquals(1, readyResults.stream().map(item -> item.thumbnailId()).distinct().count());
            assertEquals(primary.thumbnailId(), readyResults.get(0).thumbnailId());
            assertEquals(MediaThumbnailStatus.READY, primary.status());
            assertEquals(storageKey, primary.storageKey());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void markFailedPreservesADifferentReadyPrimaryThumbnail() throws Exception {
        TestServices services = createServices();
        var imageObject = services.mediaObjectService.upsertPrimaryPath(
            ModuleMediaTestSupport.mediaObjectUpsertRequest(
                ModuleMediaTestSupport.writeMediaFile(tempDir.resolve("library").resolve("poster-stable.jpg")),
                MediaKind.IMAGE
            )
        );

        String readyStorageKey = MediaThumbnailService.buildStorageKey(imageObject.objectId(), 150);
        services.mediaThumbnailService.ensurePlaceholder(imageObject);
        services.mediaThumbnailService.markReady(imageObject.objectId(), readyStorageKey, 150);

        var failed = services.mediaThumbnailService.markFailed(
            imageObject.objectId(),
            MediaThumbnailService.buildStorageKey(imageObject.objectId(), 600)
        );

        assertEquals(MediaThumbnailStatus.READY, failed.status());
        assertEquals(readyStorageKey, failed.storageKey());
        assertEquals(150, failed.width());
        assertNull(failed.height());
    }

    @Test
    void markFailedDemotesTheCurrentReadyPrimaryThumbnailWhenTheSameKeyFails() throws Exception {
        TestServices services = createServices();
        var imageObject = services.mediaObjectService.upsertPrimaryPath(
            ModuleMediaTestSupport.mediaObjectUpsertRequest(
                ModuleMediaTestSupport.writeMediaFile(tempDir.resolve("library").resolve("poster-canonical-fail.jpg")),
                MediaKind.IMAGE
            )
        );

        String canonicalStorageKey = MediaThumbnailService.buildStorageKey(imageObject.objectId(), 150);
        var placeholder = services.mediaThumbnailService.ensurePlaceholder(imageObject);
        var ready = services.mediaThumbnailService.markReady(imageObject.objectId(), canonicalStorageKey, 150);
        var failed = services.mediaThumbnailService.markFailed(imageObject.objectId(), canonicalStorageKey);

        assertEquals(placeholder.thumbnailId(), ready.thumbnailId());
        assertEquals(ready.thumbnailId(), failed.thumbnailId());
        assertEquals(MediaThumbnailStatus.FAILED, failed.status());
        assertEquals(canonicalStorageKey, failed.storageKey());
        assertEquals(150, failed.width());
    }

    private record TestServices(MediaObjectService mediaObjectService, MediaThumbnailService mediaThumbnailService) {
    }
}
