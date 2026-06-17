package com.nyx.media;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.nyx.common.DatabaseResources;
import com.nyx.config.DatabaseConfig;
import com.nyx.ffmpeg.ProbeService;
import com.nyx.media.contracts.MediaKind;
import com.nyx.media.contracts.MediaObject;
import com.nyx.media.contracts.MediaThumbnail;
import com.nyx.media.contracts.MediaThumbnailKind;
import com.nyx.media.contracts.MediaThumbnailReference;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaObjectResolverTest {
    @TempDir
    Path tempDir;

    private final List<HikariDataSource> dataSources = new ArrayList<>();

    @AfterEach
    void teardown() {
        ModuleMediaTestSupport.closeDataSources(dataSources);
    }

    private TestServices createServices() {
        ProbeService probeService = new ProbeService();
        AudioMetadataService audioMetadataService = new AudioMetadataService(probeService);
        DatabaseResources resources = MediaObjectService.createDatabase(tempDir, new DatabaseConfig(tempDir, 1));
        dataSources.add(resources.getDataSource());
        MediaObjectService mediaObjectService = new MediaObjectService(resources.getJdbi());
        MediaObjectResolver resolver = new MediaObjectResolver(mediaObjectService, probeService, audioMetadataService);
        return new TestServices(mediaObjectService, resolver);
    }

    @Test
    void resolveOrCreateSkipsRegistryPersistenceForDisallowedMediaKinds() throws Exception {
        TestServices services = createServices();
        Path textFile = ModuleMediaTestSupport.writeTextFile(tempDir.resolve("readme.txt"), "not-media");

        var mediaObject = services.resolver.resolveOrCreate(
            textFile,
            new MediaObjectResolveOptions(java.util.Set.of(MediaKind.IMAGE))
        );

        assertNull(mediaObject);
        assertNull(services.mediaObjectService.getByPath(textFile.toString()));
    }

    @Test
    void resolveOrCreateReturnsMediaObjectWhenPlaceholderBootstrapFails() throws Exception {
        ProbeService probeService = new ProbeService();
        AudioMetadataService audioMetadataService = new AudioMetadataService(probeService);
        DatabaseResources resources = MediaObjectService.createDatabase(tempDir, new DatabaseConfig(tempDir, 1));
        dataSources.add(resources.getDataSource());
        MediaObjectService mediaObjectService = new MediaObjectService(resources.getJdbi());
        MediaObjectResolver resolver = new MediaObjectResolver(
            mediaObjectService,
            probeService,
            audioMetadataService,
            new BestEffortMediaThumbnailLifecycle(new FailingThumbnailPersistence())
        );
        Path imageFile = ModuleMediaTestSupport.createImageFile(tempDir.resolve("poster.jpg"), 32, 32, "jpg");

        var mediaObject = resolver.resolveOrCreate(imageFile, MediaObjectResolveOptions.IMAGE_ONLY);

        assertNotNull(mediaObject);
        assertNotNull(mediaObjectService.getByPath(imageFile.toString()));
    }

    @Test
    void primaryThumbnailReferenceReturnsNullWhenThumbnailLookupFails() throws Exception {
        TestServices services = createServices();
        Path imageFile = ModuleMediaTestSupport.createImageFile(tempDir.resolve("lookup.jpg"), 32, 32, "jpg");
        var mediaObject = services.mediaObjectService.upsertPrimaryPath(
            new MediaObjectUpsertRequest(
                MediaKind.IMAGE,
                imageFile.toString(),
                "image/jpeg",
                Files.size(imageFile),
                "2026-04-10T12:00:00Z",
                imageFile.getFileName().toString(),
                null,
                32,
                32,
                null,
                "2026-04-10T12:00:00Z",
                null,
                null,
                null,
                com.nyx.media.contracts.MediaObjectContracts.MEDIA_OBJECT_HASH_ALGORITHM_NONE,
                null,
                com.nyx.media.contracts.MediaObjectStatus.ACTIVE
            )
        );
        MediaObjectResolver resolver = new MediaObjectResolver(
            services.mediaObjectService,
            new ProbeService(),
            new AudioMetadataService(new ProbeService()),
            new BestEffortMediaThumbnailLifecycle(new FailingThumbnailPersistence())
        );

        var reference = resolver.primaryThumbnailReference(mediaObject);

        assertNull(reference);
    }

    private record TestServices(MediaObjectService mediaObjectService, MediaObjectResolver resolver) {
    }

    private static final class FailingThumbnailPersistence implements MediaThumbnailPersistence {
        @Override
        public MediaThumbnail ensurePlaceholder(MediaObject mediaObject) {
            throw new IllegalStateException("thumbnail persistence unavailable");
        }

        @Override
        public MediaThumbnailReference primaryThumbnailReference(String objectId, String url) {
            throw new IllegalStateException("thumbnail persistence unavailable");
        }

        @Override
        public MediaThumbnail markReady(
            String objectId,
            String storageKey,
            Integer width,
            Integer height,
            String format,
            MediaThumbnailKind kind,
            Long sourcePositionMillis
        ) {
            throw new IllegalStateException("thumbnail persistence unavailable");
        }

        @Override
        public MediaThumbnail markFailed(
            String objectId,
            String storageKey,
            MediaThumbnailKind kind,
            String format
        ) {
            throw new IllegalStateException("thumbnail persistence unavailable");
        }
    }
}
