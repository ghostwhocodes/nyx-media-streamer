package com.nyx.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.nyx.browse.BrowseRoutes;
import com.nyx.browse.BrowseService;
import com.nyx.common.ManagedService;
import com.nyx.common.PathSecurity;
import com.nyx.common.VirtualPathResolver;
import com.nyx.common.storage.InMemoryStorageBackend;
import com.nyx.config.MediaRootConfig;
import com.nyx.ffmpeg.ProbeService;
import com.nyx.ffmpeg.TrickplayAssetKind;
import com.nyx.ffmpeg.VideoPreviewGenerator;
import com.nyx.ffmpeg.VideoPreviewPlan;
import com.nyx.ffmpeg.VideoPreviewRequest;
import com.nyx.ffmpeg.VideoTrickplayAssetPlan;
import com.nyx.ffmpeg.VideoTrickplayGenerator;
import com.nyx.ffmpeg.VideoTrickplayPlan;
import com.nyx.ffmpeg.VideoTrickplayRequest;
import com.nyx.ffmpeg.VideoTrickplayTimelineEntry;
import com.nyx.http.OpenApiRegistry;
import com.nyx.http.Route;
import com.nyx.playback.LocalAudioSessionService;
import com.zaxxer.hikari.HikariDataSource;
import io.javalin.Javalin;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RouteOpenApiCoverageTest {
    @TempDir
    Path tempDir;

    private final List<HikariDataSource> dataSources = new ArrayList<>();
    private final List<ManagedService> managedServices = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (int index = managedServices.size() - 1; index >= 0; index--) {
            managedServices.get(index).shutdown();
        }
        for (int index = dataSources.size() - 1; index >= 0; index--) {
            dataSources.get(index).close();
        }
        managedServices.clear();
        dataSources.clear();
    }

    @Test
    void mediaApiRoutesRegisterOpenApiDocsForAllPublicEndpoints() {
        OpenApiRegistry docsRegistry = new OpenApiRegistry();
        Javalin app = Javalin.create(config -> config.startup.showJavalinBanner = false);

        PathSecurity pathSecurity = new PathSecurity(List.of(tempDir));
        VirtualPathResolver virtualPathResolver = new VirtualPathResolver(List.of(new MediaRootConfig(tempDir, "local", "media")));
        ProbeService probeService = new ProbeService();
        AudioMetadataService audioMetadataService = new AudioMetadataService(probeService);

        var mediaObjectResources = MediaObjectService.createDatabase(tempDir.resolve("media-objects-db"));
        dataSources.add(mediaObjectResources.getDataSource());
        MediaObjectService mediaObjectService = new MediaObjectService(mediaObjectResources.getJdbi());
        MediaThumbnailService mediaThumbnailService = new MediaThumbnailService(mediaObjectResources.getJdbi());
        MediaObjectResolver mediaObjectResolver = new MediaObjectResolver(
            mediaObjectService,
            probeService,
            audioMetadataService,
            mediaThumbnailService
        );
        UserMediaStateService userMediaStateService = new UserMediaStateService(mediaObjectResources.getJdbi());

        BrowseService browseService = new BrowseService(
            virtualPathResolver,
            pathSecurity,
            List.of(150, 300, 600),
            probeService,
            audioMetadataService,
            mediaObjectResolver
        );
        MediaFileService mediaFileService = new MediaFileService(
            List.of(150, 300, 600),
            List.of(tempDir),
            probeService,
            audioMetadataService,
            mediaObjectResolver
        );

        InMemoryStorageBackend imageBackend = new InMemoryStorageBackend();
        ThumbnailService thumbnailService = new ThumbnailService(imageBackend);
        ExifExtractor exifExtractor = new ExifExtractor();
        StrippedImageCache strippedImageCache = new StrippedImageCache(exifExtractor, imageBackend);
        ImageTransformService imageTransformService = new ImageTransformService(strippedImageCache, imageBackend);
        VideoPreviewService videoPreviewService = createVideoPreviewService(imageBackend);
        VideoTrickplayService videoTrickplayService = createVideoTrickplayService(imageBackend);

        AudioTranscoder audioTranscoder = new AudioTranscoder();
        LocalAudioNegotiationService audioNegotiationService = new LocalAudioNegotiationService(audioTranscoder);
        LocalAudioSessionService audioSessionService = new LocalAudioSessionService(audioNegotiationService);
        managedServices.add(audioSessionService);

        var playlistResources = PlaylistService.createDatabase(tempDir.resolve("playlists-db"));
        dataSources.add(playlistResources.getDataSource());
        PlaylistService playlistService = new PlaylistService(playlistResources.getJdbi());

        var libraryResources = LibraryService.createDatabase(tempDir.resolve("libraries-db"));
        dataSources.add(libraryResources.getDataSource());
        LibraryService libraryService = new LibraryService(libraryResources.getJdbi());
        LibraryCatalogService libraryCatalogService = new LibraryCatalogService(libraryResources.getJdbi(), libraryService);
        LibraryUserStateService libraryUserStateService = new LibraryUserStateService(libraryCatalogService, userMediaStateService);

        var chapterResources = ChapterService.createDatabase(tempDir.resolve("chapters-db"));
        dataSources.add(chapterResources.getDataSource());
        ChapterService chapterService = new ChapterService(chapterResources.getJdbi(), pathSecurity);

        Route route = new Route(app, docsRegistry);
        BrowseRoutes.browseRoutes(route, browseService);
        ChapterRoutes.chapterRoutes(route, chapterService, pathSecurity, List.of("api-token"), virtualPathResolver);
        AudioRoutes.audioRoutes(
            route,
            mediaFileService,
            audioTranscoder,
            audioNegotiationService,
            audioSessionService,
            playlistService,
            pathSecurity,
            List.of("api-token"),
            virtualPathResolver,
            browseService,
            null,
            mediaObjectResolver
        );
        ImageRoutes.imageRoutes(
            route,
            mediaFileService,
            thumbnailService,
            exifExtractor,
            strippedImageCache,
            pathSecurity,
            virtualPathResolver,
            browseService,
            probeService,
            null,
            imageTransformService,
            videoPreviewService,
            videoTrickplayService,
            mediaObjectResolver,
            mediaThumbnailService,
            null
        );
        LibraryRoutes.libraryRoutes(
            route,
            libraryService,
            libraryCatalogService,
            libraryUserStateService,
            List.of("api-token")
        );
        MediaObjectStateRoutes.mediaObjectStateRoutes(
            route,
            mediaObjectService,
            userMediaStateService,
            virtualPathResolver,
            List.of(150, 300, 600),
            List.of("api-token")
        );

        @SuppressWarnings("unchecked")
        var paths = (java.util.Map<String, java.util.Map<String, Object>>) docsRegistry.buildSpec(
            "Nyx Media API",
            "test",
            "Coverage registration"
        ).get("paths");

        assertThat(paths.keySet()).contains(
            "/api/v1/browse",
            "/api/v1/search/files",
            "/api/v1/chapters",
            "/api/v1/chapters/marks",
            "/api/v1/chapters/marks/{markId}",
            "/api/v1/audio/file",
            "/api/v1/audio/sessions",
            "/api/v1/audio/sessions/{sessionId}/content",
            "/api/v1/audio/browse",
            "/api/v1/audio/search",
            "/api/v1/audio/playlists/{id}/reorder",
            "/api/v1/images",
            "/api/v1/images/thumb",
            "/api/v1/images/file",
            "/api/v1/images/view",
            "/api/v1/images/preview",
            "/api/v1/images/trickplay",
            "/api/v1/images/trickplay/asset",
            "/api/v1/images/exif",
            "/api/v1/images/search",
            "/api/v1/libraries",
            "/api/v1/libraries/{libraryId}",
            "/api/v1/libraries/{libraryId}/items",
            "/api/v1/libraries/{libraryId}/items/{itemId}",
            "/api/v1/libraries/{libraryId}/items/{itemId}/metadata",
            "/api/v1/libraries/{libraryId}/items/{itemId}/artwork",
            "/api/v1/libraries/{libraryId}/collections",
            "/api/v1/libraries/{libraryId}/collections/{collectionId}",
            "/api/v1/libraries/{libraryId}/state/items",
            "/api/v1/libraries/{libraryId}/state/favorites",
            "/api/v1/libraries/{libraryId}/state/watched",
            "/api/v1/libraries/{libraryId}/state/resume",
            "/api/v1/libraries/{libraryId}/state/continue-watching",
            "/api/v1/media/objects/{objectId}/state",
            "/api/v1/media/state/favorites",
            "/api/v1/media/state/continue-watching"
        );

        assertThat(paths.get("/api/v1/audio/sessions")).containsKeys("post");
        assertThat(paths.get("/api/v1/audio/sessions/{sessionId}/content")).containsKeys("get");
        assertThat(paths.get("/api/v1/images/trickplay/asset")).containsKeys("get");
        assertThat(paths.get("/api/v1/libraries/{libraryId}/items/{itemId}/metadata")).containsKeys("put", "delete");
        assertThat(paths.get("/api/v1/libraries/{libraryId}/collections/{collectionId}")).containsKeys("get", "put", "delete");
        assertThat(paths.get("/api/v1/media/objects/{objectId}/state")).containsKeys("get", "put");
    }

    private VideoPreviewService createVideoPreviewService(InMemoryStorageBackend backend) {
        VideoPreviewGenerator generator = new VideoPreviewGenerator() {
            @Override
            public VideoPreviewPlan plan(Path sourcePath, VideoPreviewRequest request) {
                Long positionMillis = request.positionMillis();
                Integer percent = request.percent();
                Integer width = request.width();
                Integer height = request.height();
                return new VideoPreviewPlan(
                    1920,
                    1080,
                    positionMillis != null ? positionMillis : ((percent != null ? percent : 10) * 1_000L),
                    width != null ? width : 320,
                    height != null ? height : 180
                );
            }

            @Override
            public byte[] generate(Path sourcePath, VideoPreviewPlan plan) {
                return renderJpeg(plan.outputWidth(), plan.outputHeight());
            }
        };
        return new VideoPreviewService(generator, backend);
    }

    private VideoTrickplayService createVideoTrickplayService(InMemoryStorageBackend backend) {
        VideoTrickplayGenerator generator = new VideoTrickplayGenerator() {
            @Override
            public VideoTrickplayPlan plan(Path sourcePath, VideoTrickplayRequest request) {
                long interval = request.intervalMillis() != null ? request.intervalMillis() : 10_000L;
                int thumbnailWidth = request.thumbnailWidth() != null ? request.thumbnailWidth() : 320;
                int thumbnailHeight = request.thumbnailHeight() != null ? request.thumbnailHeight() : 180;
                int columns = request.tileColumns() != null ? request.tileColumns() : 4;
                int rows = request.tileRows() != null ? request.tileRows() : 4;
                TrickplayAssetKind assetKind = request.assetKinds().isEmpty()
                    ? TrickplayAssetKind.STORYBOARD_SHEET
                    : request.assetKinds().iterator().next();
                VideoTrickplayAssetPlan asset = new VideoTrickplayAssetPlan(
                    assetKind,
                    0,
                    0L,
                    150_000L,
                    interval,
                    16,
                    columns,
                    rows,
                    thumbnailWidth,
                    thumbnailHeight,
                    thumbnailWidth * columns,
                    thumbnailHeight * rows
                );
                return new VideoTrickplayPlan(
                    1920,
                    1080,
                    600_000L,
                    interval,
                    thumbnailWidth,
                    thumbnailHeight,
                    columns,
                    rows,
                    List.of(asset),
                    java.util.stream.IntStream.range(0, asset.frameCount())
                        .mapToObj(index -> new VideoTrickplayTimelineEntry(
                            asset.startMillis() + (asset.intervalMillis() * index),
                            asset.kind(),
                            asset.assetIndex(),
                            index % asset.tileColumns(),
                            index / asset.tileColumns()
                        ))
                        .toList()
                );
            }

            @Override
            public byte[] generate(Path sourcePath, VideoTrickplayAssetPlan plan) {
                return renderJpeg(plan.outputWidth(), plan.outputHeight());
            }
        };
        return new VideoTrickplayService(generator, backend);
    }

    private byte[] renderJpeg(int width, int height) {
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to render JPEG fixture", exception);
        }
    }
}
