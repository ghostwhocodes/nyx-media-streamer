package com.nyx.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.browse.BrowseService;
import com.nyx.common.DatabaseResources;
import com.nyx.common.HealthMonitor;
import com.nyx.common.ManagedService;
import com.nyx.common.PathSecurity;
import com.nyx.common.QuotaService;
import com.nyx.common.VirtualPathResolver;
import com.nyx.common.storage.InMemoryStorageBackend;
import com.nyx.config.MediaRootConfig;
import com.nyx.config.QuotaConfig;
import com.nyx.ffmpeg.MediaProber;
import com.nyx.ffmpeg.TrickplayAssetKind;
import com.nyx.ffmpeg.VideoPreviewGenerator;
import com.nyx.ffmpeg.VideoPreviewPlan;
import com.nyx.ffmpeg.VideoPreviewRequest;
import com.nyx.ffmpeg.VideoTrickplayAssetPlan;
import com.nyx.ffmpeg.VideoTrickplayGenerator;
import com.nyx.ffmpeg.VideoTrickplayPlan;
import com.nyx.ffmpeg.VideoTrickplayRequest;
import com.nyx.ffmpeg.VideoTrickplayTimelineEntry;
import com.nyx.ffmpeg.model.ProbeResult;
import com.nyx.ffmpeg.model.ProbeStreams;
import com.nyx.ffmpeg.model.SubtitleStream;
import com.nyx.ffmpeg.model.VideoStream;
import com.nyx.http.AuthMode;
import com.nyx.http.ContentType;
import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import com.nyx.http.UserIdPrincipal;
import com.nyx.json.NyxJson;
import com.zaxxer.hikari.HikariDataSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageRoutesTest {
    @TempDir
    Path tempDir;

    private final List<HikariDataSource> dataSources = new ArrayList<>();
    private final List<ManagedService> managedServices = new ArrayList<>();
    private final ObjectMapper json = NyxJson.newMapper();

    private Path mediaDir;

    @BeforeEach
    void setUp() throws Exception {
        mediaDir = Files.createDirectories(tempDir.resolve("media"));
    }

    @AfterEach
    void tearDown() {
        managedServices.clear();
        MediaApiTestSupport.closeDataSources(dataSources);
    }

    private record RouteServices(
        MediaFileService mediaFileService,
        ThumbnailService thumbnailService,
        ExifExtractor exifExtractor,
        StrippedImageCache strippedImageCache,
        ImageTransformService imageTransformService,
        VideoPreviewService videoPreviewService,
        VideoTrickplayService videoTrickplayService,
        PathSecurity pathSecurity,
        MediaObjectService mediaObjectService,
        MediaObjectResolver mediaObjectResolver,
        MediaThumbnailService mediaThumbnailService,
        VirtualPathResolver virtualPathResolver,
        BrowseService browseService,
        MediaProber routeProbeService
    ) {
    }

    private RouteServices createServices(
        boolean withMediaObjects,
        boolean withVirtualPaths,
        MediaProber routeProbeService,
        ThumbnailService thumbnailOverride
    ) {
        PathSecurity pathSecurity = new PathSecurity(List.of(tempDir));
        com.nyx.ffmpeg.ProbeService serviceProbe = new com.nyx.ffmpeg.ProbeService();
        AudioMetadataService audioMetadataService = new AudioMetadataService(serviceProbe);

        MediaObjectService mediaObjectService = null;
        MediaObjectResolver mediaObjectResolver = null;
        MediaThumbnailService mediaThumbnailService = null;
        if (withMediaObjects) {
            DatabaseResources mediaResources = MediaObjectService.createDatabase(tempDir.resolve("media-objects-db"));
            dataSources.add(mediaResources.getDataSource());
            mediaObjectService = new MediaObjectService(mediaResources.getJdbi());
            mediaThumbnailService = new MediaThumbnailService(mediaResources.getJdbi());
            mediaObjectResolver = new MediaObjectResolver(
                mediaObjectService,
                serviceProbe,
                audioMetadataService,
                mediaThumbnailService
            );
        }

        VirtualPathResolver virtualPathResolver = null;
        BrowseService browseService = null;
        MediaFileService mediaFileService;
        if (withVirtualPaths) {
            virtualPathResolver = new VirtualPathResolver(List.of(new MediaRootConfig(mediaDir, "local", "media")));
            mediaFileService = new MediaFileService(
                List.of(150, 300, 600),
                List.of(tempDir),
                serviceProbe,
                null,
                virtualPathResolver,
                audioMetadataService,
                mediaObjectResolver,
                null
            );
            browseService = new BrowseService(
                virtualPathResolver,
                pathSecurity,
                List.of(150, 300, 600),
                serviceProbe,
                audioMetadataService,
                mediaObjectResolver
            );
        } else {
            mediaFileService = new MediaFileService(List.of(tempDir), serviceProbe, audioMetadataService, mediaObjectResolver);
        }

        InMemoryStorageBackend backend = new InMemoryStorageBackend();
        ThumbnailService thumbnailService = thumbnailOverride != null ? thumbnailOverride : new ThumbnailService(backend);
        ExifExtractor exifExtractor = new ExifExtractor();
        StrippedImageCache strippedImageCache = new StrippedImageCache(exifExtractor, backend);
        ImageTransformService imageTransformService = new ImageTransformService(strippedImageCache, backend);
        VideoPreviewService videoPreviewService = createVideoPreviewService(backend);
        VideoTrickplayService videoTrickplayService = createVideoTrickplayService(backend);

        return new RouteServices(
            mediaFileService,
            thumbnailService,
            exifExtractor,
            strippedImageCache,
            imageTransformService,
            videoPreviewService,
            videoTrickplayService,
            pathSecurity,
            mediaObjectService,
            mediaObjectResolver,
            mediaThumbnailService,
            virtualPathResolver,
            browseService,
            routeProbeService
        );
    }

    private void installAuth(MediaApiTestSupport.ApplicationHarness app) {
        app.installBearerAuth(
            "api-token",
            credential -> "alice-token".equals(credential.token()) ? new UserIdPrincipal("alice") : null
        );
    }

    private void registerRoutes(
        MediaApiTestSupport.ApplicationHarness app,
        RouteServices services,
        QuotaService quotaService,
        boolean requireOuterAuth
    ) {
        app.routing(route -> {
            var target = requireOuterAuth ? route.withAuth(AuthMode.REQUIRED, List.of("api-token")) : route;
            ImageRoutes.imageRoutes(
                target,
                services.mediaFileService(),
                services.thumbnailService(),
                services.exifExtractor(),
                services.strippedImageCache(),
                services.pathSecurity(),
                services.virtualPathResolver(),
                services.browseService(),
                services.routeProbeService(),
                quotaService,
                services.imageTransformService(),
                services.videoPreviewService(),
                services.videoTrickplayService(),
                services.mediaObjectResolver(),
                services.mediaThumbnailService(),
                null
            );
        });
    }

    private JsonNode readBody(Response response) throws Exception {
        return json.readTree(MediaApiTestSupport.bodyAsText(response));
    }

    private Path createImageFile(String name, int width, int height) throws Exception {
        return createImageFile(mediaDir, name, width, height);
    }

    private Path createImageFile(Path dir, String name, int width, int height) throws Exception {
        Path file = dir.resolve(name);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        String format = name.substring(name.lastIndexOf('.') + 1);
        ImageIO.write(image, format, file.toFile());
        return file;
    }

    private Path createVideoFile(String name) throws Exception {
        Path file = mediaDir.resolve(name);
        Files.write(file, new byte[2_048]);
        return file;
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
                List<VideoTrickplayTimelineEntry> timeline = new ArrayList<>();
                for (int index = 0; index < asset.frameCount(); index++) {
                    timeline.add(new VideoTrickplayTimelineEntry(
                        asset.startMillis() + (asset.intervalMillis() * index),
                        asset.kind(),
                        asset.assetIndex(),
                        index % asset.tileColumns(),
                        index / asset.tileColumns()
                    ));
                }
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
                    timeline
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
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to render JPEG fixture", exception);
        }
    }

    private ProbeResult subtitleProbeResult(Path path) {
        return new ProbeResult(
            path.toString(),
            "matroska",
            120.0,
            4_096L,
            new ProbeStreams(
                List.of(new VideoStream(0, "h264", 1920, 1080, 24.0)),
                List.of(),
                List.of(new SubtitleStream(2, "subrip", "eng", "English"))
            )
        );
    }

    @Test
    void galleryAndSearchRoutesSupportSortingPagingValidationAndVirtualPaths() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            RouteServices services = createServices(false, true, null, null);
            Path zebra = createImageFile("zebra.jpg", 100, 80);
            Path apple = createImageFile("apple.png", 400, 300);
            Path mango = createImageFile("mango.bmp", 50, 40);

            registerRoutes(app, services, null, false);

            List<String> expectedSizeOrder = List.of(zebra, apple, mango)
                .stream()
                .sorted(java.util.Comparator.comparingLong((Path path) -> {
                    try {
                        return Files.size(path);
                    } catch (IOException exception) {
                        throw new IllegalStateException("Failed to read fixture size", exception);
                    }
                }).reversed())
                .limit(2)
                .map(path -> path.getFileName().toString())
                .toList();

            try (Response gallery = app.client().get("/api/v1/images?dir=media&page=1&limit=2&sort=size")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(gallery));
                JsonNode body = readBody(gallery);
                assertEquals(3, body.path("total").asInt());
                assertEquals(2, body.path("images").size());
                assertEquals(expectedSizeOrder.get(0), body.path("images").get(0).path("name").asText());
                assertEquals(expectedSizeOrder.get(1), body.path("images").get(1).path("name").asText());
            }

            try (Response search = app.client().get("/api/v1/images/search?query=app&page=1&limit=10")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(search));
                JsonNode body = readBody(search);
                assertEquals("app", body.path("query").asText());
                assertEquals(1, body.path("total").asInt());
                assertTrue(body.path("items").get(0).path("name").asText().contains("apple"));
            }

            try (Response missingDir = app.client().get("/api/v1/images")) {
                assertEquals(HttpStatusCode.BadRequest, MediaApiTestSupport.status(missingDir));
            }

            try (Response invalidSort = app.client().get("/api/v1/images?dir=media&sort=random")) {
                assertEquals(HttpStatusCode.BadRequest, MediaApiTestSupport.status(invalidSort));
            }
        });
    }

    @Test
    void thumbnailAndFileRoutesServeCacheableBytesAndRejectInvalidRequests() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            RouteServices services = createServices(false, false, null, null);
            Path image = createImageFile("poster.png", 240, 180);
            Path text = mediaDir.resolve("notes.txt");
            Files.writeString(text, "hello");

            registerRoutes(app, services, null, false);

            String thumbEtag;
            try (Response thumb = app.client().get("/api/v1/images/thumb?path=" + image + "&size=150")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(thumb));
                assertEquals(ContentType.Image.JPEG, MediaApiTestSupport.contentType(thumb));
                assertTrue(MediaApiTestSupport.readRawBytes(thumb).length > 0);
                thumbEtag = thumb.header(HttpHeaders.ETag);
                assertNotNull(thumbEtag);
            }

            try (Response cachedThumb = app.client().get(
                "/api/v1/images/thumb?path=" + image + "&size=150",
                request -> request.header(HttpHeaders.IfNoneMatch, thumbEtag)
            )) {
                assertEquals(HttpStatusCode.NotModified, MediaApiTestSupport.status(cachedThumb));
            }

            String fileEtag;
            try (Response file = app.client().get("/api/v1/images/file?path=" + image)) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(file));
                assertEquals(ContentType.parse("image/png"), MediaApiTestSupport.contentType(file));
                assertTrue(MediaApiTestSupport.readRawBytes(file).length > 0);
                fileEtag = file.header(HttpHeaders.ETag);
                assertNotNull(fileEtag);
            }

            try (Response cachedFile = app.client().get(
                "/api/v1/images/file?path=" + image,
                request -> request.header(HttpHeaders.IfNoneMatch, fileEtag)
            )) {
                assertEquals(HttpStatusCode.NotModified, MediaApiTestSupport.status(cachedFile));
            }

            try (Response missingSize = app.client().get("/api/v1/images/thumb?path=" + image)) {
                assertEquals(HttpStatusCode.BadRequest, MediaApiTestSupport.status(missingSize));
            }

            try (Response missingPath = app.client().get("/api/v1/images/file")) {
                assertEquals(HttpStatusCode.BadRequest, MediaApiTestSupport.status(missingPath));
            }

            try (Response rejected = app.client().get("/api/v1/images/file?path=" + text)) {
                assertEquals(HttpStatusCode.NotFound, MediaApiTestSupport.status(rejected));
            }
        });
    }

    @Test
    void viewPreviewAndTrickplayRoutesReturnCacheableMedia() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            RouteServices services = createServices(false, false, null, null);
            Path image = createImageFile("frame.jpg", 400, 240);
            Path video = createVideoFile("clip.mp4");

            registerRoutes(app, services, null, false);

            String viewEtag;
            try (Response view = app.client().get("/api/v1/images/view?path=" + image + "&width=120&height=80")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(view));
                assertTrue(MediaApiTestSupport.readRawBytes(view).length > 0);
                viewEtag = view.header(HttpHeaders.ETag);
                assertNotNull(viewEtag);
            }

            try (Response cachedView = app.client().get(
                "/api/v1/images/view?path=" + image + "&width=120&height=80",
                request -> request.header(HttpHeaders.IfNoneMatch, viewEtag)
            )) {
                assertEquals(HttpStatusCode.NotModified, MediaApiTestSupport.status(cachedView));
            }

            try (Response preview = app.client().get("/api/v1/images/preview?path=" + video + "&width=160&height=90")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(preview));
                assertEquals(ContentType.Image.JPEG, MediaApiTestSupport.contentType(preview));
                assertTrue(MediaApiTestSupport.readRawBytes(preview).length > 0);
            }

            String assetUrl;
            try (Response trickplay = app.client().get("/api/v1/images/trickplay?path=" + video + "&assetKinds=storyboard_sheet")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(trickplay));
                JsonNode manifest = readBody(trickplay);
                assertTrue(manifest.path("cacheable").asBoolean());
                assertTrue(manifest.path("assets").size() >= 1);
                assetUrl = manifest.path("assets").get(0).path("url").asText();
            }

            try (Response asset = app.client().get(assetUrl)) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(asset));
                assertEquals(ContentType.Image.JPEG, MediaApiTestSupport.contentType(asset));
                assertTrue(MediaApiTestSupport.readRawBytes(asset).length > 0);
            }
        });
    }

    @Test
    void exifAndSubtitlesRoutesHandleSuccessValidationAndProbeFailures() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            RouteServices successServices = createServices(true, false, new FixedMediaProber(subtitleProbeResult(mediaDir.resolve("movie.mp4"))), null);
            Path image = createImageFile("meta.png", 120, 90);
            Path video = createVideoFile("movie.mp4");
            Path audio = mediaDir.resolve("song.mp3");
            Files.write(audio, new byte[1_024]);

            registerRoutes(app, successServices, null, false);

            try (Response exif = app.client().get("/api/v1/images/exif?path=" + image)) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(exif));
                assertTrue(MediaApiTestSupport.bodyAsText(exif).startsWith("{"));
            }

            try (Response subtitles = app.client().get("/api/v1/images/subtitles?path=" + video)) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(subtitles));
                JsonNode body = readBody(subtitles);
                assertEquals("subrip", body.get(0).path("codec").asText());
                assertNotNull(successServices.mediaObjectService().getByPath(video.toString()));
            }

            try (Response missingPath = app.client().get("/api/v1/images/subtitles")) {
                assertEquals(HttpStatusCode.BadRequest, MediaApiTestSupport.status(missingPath));
            }

            try (Response nonVideo = app.client().get("/api/v1/images/subtitles?path=" + audio)) {
                assertEquals(HttpStatusCode.BadRequest, MediaApiTestSupport.status(nonVideo));
            }
            assertNull(successServices.mediaObjectService().getByPath(audio.toString()));
        });

        MediaApiTestSupport.testApplication(app -> {
            RouteServices missingProbe = createServices(false, false, null, null);
            Path video = createVideoFile("missing-probe.mp4");
            registerRoutes(app, missingProbe, null, false);
            try (Response response = app.client().get("/api/v1/images/subtitles?path=" + video)) {
                assertEquals(HttpStatusCode.BadRequest, MediaApiTestSupport.status(response));
            }
        });

        MediaApiTestSupport.testApplication(app -> {
            RouteServices failingProbe = createServices(false, false, new FailingMediaProber(), null);
            Path video = createVideoFile("failing-probe.mp4");
            registerRoutes(app, failingProbe, null, false);
            try (Response response = app.client().get("/api/v1/images/subtitles?path=" + video)) {
                assertEquals(HttpStatusCode.InternalServerError, MediaApiTestSupport.status(response));
            }
        });
    }

    @Test
    void videoThumbnailReturns503WhenFfmpegIsUnavailable() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            ThumbnailService thumbnailService = new ThumbnailService(
                java.util.Set.of(150),
                new HealthMonitor() {
                    @Override
                    public boolean isFfmpegAvailable() {
                        return false;
                    }
                },
                new InMemoryStorageBackend()
            );
            RouteServices services = createServices(false, false, null, thumbnailService);
            Path video = createVideoFile("unavailable.mp4");

            registerRoutes(app, services, null, false);

            try (Response response = app.client().get("/api/v1/images/thumb?path=" + video + "&size=150")) {
                assertEquals(HttpStatusCode.ServiceUnavailable, MediaApiTestSupport.status(response));
            }
        });
    }

    @Test
    void rateLimitedGalleryReturns429AfterTheWindowIsExhausted() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            RouteServices services = createServices(false, false, null, null);
            createImageFile("quota.jpg", 100, 80);
            installAuth(app);

            QuotaService quotaService = new QuotaService(
                new QuotaConfig(true, 4, 1, 10_737_418_240L, Map.of()),
                userId -> 0
            );
            registerRoutes(app, services, quotaService, true);

            try (Response first = app.client().get(
                "/api/v1/images?dir=" + mediaDir,
                request -> request.header(HttpHeaders.Authorization, "Bearer alice-token")
            )) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(first));
            }

            try (Response second = app.client().get(
                "/api/v1/images?dir=" + mediaDir,
                request -> request.header(HttpHeaders.Authorization, "Bearer alice-token")
            )) {
                assertEquals(HttpStatusCode.TooManyRequests, MediaApiTestSupport.status(second));
            }
        });
    }

    private static final class FixedMediaProber implements MediaProber {
        private final ProbeResult result;

        private FixedMediaProber(ProbeResult result) {
            this.result = result;
        }

        @Override
        public ProbeResult probe(Path path) {
            return result;
        }

        @Override
        public ProbeResult probeCached(Path path) {
            return result;
        }

        @Override
        public void clearCache() {
        }
    }

    private static final class FailingMediaProber implements MediaProber {
        @Override
        public ProbeResult probe(Path path) {
            throw new IllegalStateException("ffprobe failed");
        }

        @Override
        public ProbeResult probeCached(Path path) {
            throw new IllegalStateException("ffprobe failed");
        }

        @Override
        public void clearCache() {
        }
    }
}
