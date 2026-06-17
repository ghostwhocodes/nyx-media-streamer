package com.nyx.browse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.common.DatabaseResources;
import com.nyx.common.PathSecurity;
import com.nyx.common.VirtualPathResolver;
import com.nyx.config.MediaRootConfig;
import com.nyx.ffmpeg.MediaProber;
import com.nyx.ffmpeg.ProbeService;
import com.nyx.ffmpeg.model.ProbeResult;
import com.nyx.ffmpeg.model.ProbeStreams;
import com.nyx.ffmpeg.model.VideoStream;
import com.nyx.http.HttpStatusCode;
import com.nyx.json.NyxJson;
import com.nyx.media.AudioMetadataService;
import com.nyx.media.MediaApiTestSupport;
import com.nyx.media.MediaObjectResolver;
import com.nyx.media.MediaObjectService;
import com.nyx.media.MediaThumbnailService;
import com.zaxxer.hikari.HikariDataSource;
import java.awt.image.BufferedImage;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrowseRoutesTest {
    @TempDir
    Path tempDir;

    private Path moviesRoot;
    private Path musicRoot;
    private final List<HikariDataSource> dataSources = new ArrayList<>();
    private final ObjectMapper json = NyxJson.newMapper();

    @BeforeEach
    void setup() throws Exception {
        moviesRoot = Files.createDirectories(tempDir.resolve("movies"));
        musicRoot = Files.createDirectories(tempDir.resolve("music"));
    }

    @AfterEach
    void teardown() {
        MediaApiTestSupport.closeDataSources(dataSources);
    }

    private BrowseService createBrowseService() {
        VirtualPathResolver resolver = new VirtualPathResolver(List.of(
            new MediaRootConfig(moviesRoot),
            new MediaRootConfig(musicRoot)
        ));
        PathSecurity pathSecurity = new PathSecurity(List.of(moviesRoot, musicRoot));
        return new BrowseService(resolver, pathSecurity, List.of(150, 300), null);
    }

    private BrowseService createBrowseServiceWithObjects() throws Exception {
        return createBrowseServiceWithObjects(new ProbeService());
    }

    private BrowseService createBrowseServiceWithObjects(MediaProber probeService) throws Exception {
        VirtualPathResolver resolver = new VirtualPathResolver(List.of(
            new MediaRootConfig(moviesRoot),
            new MediaRootConfig(musicRoot)
        ));
        PathSecurity pathSecurity = new PathSecurity(List.of(moviesRoot, musicRoot));
        AudioMetadataService audioMetadataService = new AudioMetadataService(probeService);
        DatabaseResources resources = MediaObjectService.createDatabase(Files.createDirectories(tempDir.resolve("objects-db")));
        dataSources.add(resources.getDataSource());
        MediaObjectResolver mediaObjectResolver = new MediaObjectResolver(
            new MediaObjectService(resources.getJdbi()),
            probeService,
            audioMetadataService,
            new MediaThumbnailService(resources.getJdbi())
        );
        return new BrowseService(
            resolver,
            pathSecurity,
            List.of(150, 300),
            probeService,
            audioMetadataService,
            mediaObjectResolver
        );
    }

    private void installRoutes(MediaApiTestSupport.ApplicationHarness app, BrowseService browseService) {
        app.routing(route -> BrowseRoutes.browseRoutes(route, browseService));
    }

    private Path createImage(Path dir, String name) throws Exception {
        Path file = dir.resolve(name);
        BufferedImage image = new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, name.substring(name.lastIndexOf('.') + 1), file.toFile());
        return file;
    }

    private Path createVideo(Path dir, String name) throws Exception {
        Path file = dir.resolve(name);
        Files.write(file, new byte[2_048]);
        return file;
    }

    private Path createAudio(Path dir, String name) throws Exception {
        Path file = dir.resolve(name);
        Files.write(file, new byte[1_024]);
        return file;
    }

    private static String encodeQueryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String encodePathQueryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
            .replace("%2F", "/")
            .replace("%2f", "/");
    }

    private JsonNode readBody(Response response) throws Exception {
        return json.readTree(MediaApiTestSupport.bodyAsText(response));
    }

    private static JsonNode firstItemOfType(JsonNode items, String type) {
        for (JsonNode item : items) {
            if (type.equals(item.path("type").asText())) {
                return item;
            }
        }
        throw new AssertionError("Missing item of type " + type);
    }

    @Test
    void browseWithoutPathReturnsRoots() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            installRoutes(app, createBrowseService());

            try (Response response = app.client().get("/api/v1/browse")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(response));
                JsonNode body = readBody(response);
                assertEquals(2, body.path("total").asInt());
                for (JsonNode item : body.path("items")) {
                    assertEquals("folder", item.path("type").asText());
                }
            }
        });
    }

    @Test
    void browseRootDirectoryListsContents() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            installRoutes(app, createBrowseService());
            createVideo(moviesRoot, "film.mp4");
            createImage(moviesRoot, "poster.jpg");

            try (Response response = app.client().get("/api/v1/browse?path=movies")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(response));
                assertEquals(2, readBody(response).path("total").asInt());
            }
        });
    }

    @Test
    void browseAcceptsDirAliasForPath() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            installRoutes(app, createBrowseService());
            createVideo(moviesRoot, "alias-film.mp4");

            try (Response response = app.client().get("/api/v1/browse?dir=movies")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(response));
                assertEquals(1, readBody(response).path("total").asInt());
            }
        });
    }

    @Test
    void browseFileItemsExposeStableObjectIdentity() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            installRoutes(app, createBrowseServiceWithObjects());
            createVideo(moviesRoot, "film.mp4");

            String firstObjectId;
            try (Response firstResponse = app.client().get("/api/v1/browse?path=movies")) {
                JsonNode firstVideo = firstItemOfType(readBody(firstResponse).path("items"), "video");
                firstObjectId = firstVideo.path("objectId").asText(null);
                assertNotNull(firstObjectId);
                assertEquals("VIDEO", firstVideo.path("mediaKind").asText());
            }

            try (Response secondResponse = app.client().get("/api/v1/browse?path=movies")) {
                JsonNode secondVideo = firstItemOfType(readBody(secondResponse).path("items"), "video");
                assertEquals(firstObjectId, secondVideo.path("objectId").asText());
            }
        });
    }

    @Test
    void browseImageItemsExposeAdditivePrimaryThumbnailReferencesWithoutBlockingGeneration() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            installRoutes(app, createBrowseServiceWithObjects());
            createImage(moviesRoot, "poster.jpg");

            try (Response response = app.client().get("/api/v1/browse?path=movies")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(response));
                JsonNode image = firstItemOfType(readBody(response).path("items"), "image");
                JsonNode primaryThumbnail = image.path("primaryThumbnail");
                assertNotNull(image.path("objectId").asText(null));
                assertEquals("PENDING", primaryThumbnail.path("status").asText());
                assertEquals("/api/v1/images/thumb?path=movies/poster.jpg&size=150", primaryThumbnail.path("url").asText());
            }
        });
    }

    @Test
    void browseVideoItemsExposeDereferenceablePrimaryThumbnailUrls() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            installRoutes(app, createBrowseServiceWithObjects());
            createVideo(moviesRoot, "film-thumb.mp4");

            try (Response response = app.client().get("/api/v1/browse?path=movies")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(response));
                JsonNode video = firstItemOfType(readBody(response).path("items"), "video");
                JsonNode primaryThumbnail = video.path("primaryThumbnail");
                assertNotNull(video.path("objectId").asText(null));
                assertEquals("PENDING", primaryThumbnail.path("status").asText());
                assertEquals("/api/v1/images/thumb?path=movies/film-thumb.mp4&size=150", primaryThumbnail.path("url").asText());
            }
        });
    }

    @Test
    void browseResponsesExposeRouteTemplatesLinksAndCapabilityHints() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            installRoutes(app, createBrowseServiceWithObjects());
            createVideo(moviesRoot, "film-contract.mp4");
            createImage(moviesRoot, "poster-contract.jpg");
            createAudio(musicRoot, "song-contract.mp3");

            try (Response response = app.client().get("/api/v1/browse?path=movies")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(response));
                JsonNode body = readBody(response);
                assertEquals("/api/v1/stream.m3u8?path={path}&quality={quality}", body.path("routeTemplates").path("playback").asText());
                assertEquals("/api/v1/search/files?query={query}", body.path("routeTemplates").path("search").asText());
                JsonNode video = firstItemOfType(body.path("items"), "video");
                assertEquals("/api/v1/stream.m3u8?path=movies/film-contract.mp4", video.path("links").path("playbackUrl").asText());
                assertEquals("video/mp4", video.path("capabilities").path("mimeType").asText());
                assertEquals(video.path("objectId").asText(), video.path("capabilities").path("objectId").asText());
                assertTrue(video.path("capabilities").path("transcodeRequired").asBoolean());
                assertEquals(
                    "/api/v1/stream.m3u8?path=movies/film-contract.mp4",
                    video.path("capabilities").path("preferredPlaybackEndpoint").asText()
                );
            }

            try (Response response = app.client().get("/api/v1/browse?path=music")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(response));
                JsonNode audio = firstItemOfType(readBody(response).path("items"), "music");
                assertEquals("/api/v1/audio/file?path=music/song-contract.mp3", audio.path("links").path("audioUrl").asText());
                assertTrue(audio.path("capabilities").path("directPlayAvailable").asBoolean());
                assertFalse(audio.path("capabilities").path("transcodeRequired").asBoolean());
            }
        });
    }

    @Test
    void browseResponsesUseForwardedOriginForClientRouteLinks() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            installRoutes(app, createBrowseServiceWithObjects());
            createVideo(moviesRoot, "proxy-film.mp4");
            createImage(moviesRoot, "proxy-poster.jpg");

            try (Response response = app.client().get("/api/v1/browse?path=movies", request -> {
                request.header("X-Forwarded-Proto", "https");
                request.header("X-Forwarded-Host", "media.example.test");
                request.header("X-Forwarded-Port", "9443");
            })) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(response));
                JsonNode body = readBody(response);
                String origin = "https://media.example.test:9443";
                assertEquals(origin + "/api/v1/search/files?query={query}", body.path("routeTemplates").path("search").asText());

                JsonNode video = firstItemOfType(body.path("items"), "video");
                assertEquals(origin + "/api/v1/stream.m3u8?path=movies/proxy-film.mp4", video.path("links").path("playbackUrl").asText());
                assertEquals(
                    origin + "/api/v1/images/thumb?path=movies/proxy-film.mp4&size=150",
                    video.path("links").path("thumbnailUrl").asText()
                );

                JsonNode image = firstItemOfType(body.path("items"), "image");
                assertEquals(origin + "/api/v1/images/file?path=movies/proxy-poster.jpg", image.path("links").path("imageUrl").asText());
                assertEquals(
                    origin + "/api/v1/images/view?path=movies/proxy-poster.jpg",
                    image.path("viewing").path("transformUrl").asText()
                );
            }
        });
    }

    @Test
    void browseAndSearchMediaLinksEncodePathQueryValues() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            installRoutes(app, createBrowseServiceWithObjects());
            createVideo(moviesRoot, "Fast & Furious #1.mp4");
            createImage(moviesRoot, "Hero & Poster #1.jpg");
            createAudio(musicRoot, "Rock & Roll?.mp3");

            String encodedVideoPath = encodePathQueryValue("movies/Fast & Furious #1.mp4");
            String encodedImagePath = encodePathQueryValue("movies/Hero & Poster #1.jpg");
            String encodedAudioPath = encodePathQueryValue("music/Rock & Roll?.mp3");

            try (Response response = app.client().get("/api/v1/browse?path=movies")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(response));
                JsonNode items = readBody(response).path("items");
                JsonNode video = firstItemOfType(items, "video");
                assertEquals("/api/v1/stream.m3u8?path=" + encodedVideoPath, video.path("links").path("playbackUrl").asText());
                assertEquals("/api/v1/images/thumb?path=" + encodedVideoPath + "&size=150", video.path("links").path("thumbnailUrl").asText());
                assertEquals(
                    "/api/v1/images/thumb?path=" + encodedVideoPath + "&size=150",
                    video.path("capabilities").path("primaryThumbnailUrl").asText()
                );

                JsonNode image = firstItemOfType(items, "image");
                assertEquals("/api/v1/images/file?path=" + encodedImagePath, image.path("links").path("imageUrl").asText());
                assertEquals("/api/v1/images/thumb?path=" + encodedImagePath + "&size=150", image.path("links").path("thumbnailUrl").asText());
                assertEquals("/api/v1/images/thumb?path=" + encodedImagePath + "&size=300", image.path("thumbnails").path("300").asText());
            }

            try (Response response = app.client().get("/api/v1/search/files?query=Rock")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(response));
                JsonNode audio = firstItemOfType(readBody(response).path("items"), "music");
                assertEquals("/api/v1/audio/file?path=" + encodedAudioPath, audio.path("links").path("audioUrl").asText());
                assertEquals(
                    "/api/v1/audio/file?path=" + encodedAudioPath,
                    audio.path("capabilities").path("preferredPlaybackEndpoint").asText()
                );
            }
        });
    }

    @Test
    void browseAndSearchVideoCapabilityHintsExposeKnownDuration() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            Path movie = createVideo(moviesRoot, "duration-contract.mp4");
            installRoutes(app, createBrowseServiceWithObjects(new FixedMediaProber(videoProbe(movie, 125.5))));

            try (Response response = app.client().get("/api/v1/browse?path=movies")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(response));
                JsonNode video = firstItemOfType(readBody(response).path("items"), "video");
                assertEquals(125.5, video.path("capabilities").path("durationSeconds").asDouble(), 0.001);
            }

            try (Response response = app.client().get("/api/v1/search/files?query=duration-contract")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(response));
                JsonNode video = firstItemOfType(readBody(response).path("items"), "video");
                assertEquals(125.5, video.path("capabilities").path("durationSeconds").asDouble(), 0.001);
            }
        });
    }

    @Test
    void browseReturnsTypeDiscriminatorInJson() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            installRoutes(app, createBrowseService());
            createVideo(moviesRoot, "film.mp4");
            createImage(moviesRoot, "photo.jpg");
            Files.createDirectories(moviesRoot.resolve("subfolder"));

            try (Response response = app.client().get("/api/v1/browse?path=movies")) {
                JsonNode items = readBody(response).path("items");
                java.util.Set<String> types = new java.util.HashSet<>();
                for (JsonNode item : items) {
                    types.add(item.path("type").asText());
                }
                assertTrue(types.contains("video"));
                assertTrue(types.contains("image"));
                assertTrue(types.contains("folder"));
            }
        });
    }

    @Test
    void browseVideoItemsIncludePreviewAndTrickplayDiscoveryMetadata() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            installRoutes(app, createBrowseService());
            createVideo(moviesRoot, "film.mp4");

            try (Response response = app.client().get("/api/v1/browse?path=movies")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(response));
                JsonNode viewing = firstItemOfType(readBody(response).path("items"), "video").path("viewing");
                assertEquals(
                    "/api/v1/images/preview?path=" + encodeQueryValue("movies/film.mp4"),
                    viewing.path("previewUrl").asText()
                );
                assertEquals(
                    "/api/v1/images/trickplay?path=" + encodeQueryValue("movies/film.mp4"),
                    viewing.path("trickplay").path("manifestUrl").asText()
                );
            }
        });
    }

    @Test
    void browseVideoDiscoveryMetadataEncodesReservedCharacters() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            installRoutes(app, createBrowseService());
            createVideo(moviesRoot, "Fast & Furious #1.mp4");

            try (Response response = app.client().get("/api/v1/browse?path=movies")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(response));
                JsonNode viewing = firstItemOfType(readBody(response).path("items"), "video").path("viewing");
                String encodedPath = encodeQueryValue("movies/Fast & Furious #1.mp4");
                assertEquals("/api/v1/images/preview?path=" + encodedPath, viewing.path("previewUrl").asText());
                assertEquals(
                    "/api/v1/images/trickplay?path=" + encodedPath,
                    viewing.path("trickplay").path("manifestUrl").asText()
                );
            }
        });
    }

    @Test
    void browseWithPaginationParams() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            installRoutes(app, createBrowseService());
            for (int i = 1; i <= 5; i++) {
                createVideo(moviesRoot, "vid_" + i + ".mp4");
            }

            try (Response response = app.client().get("/api/v1/browse?path=movies&page=2&limit=2")) {
                JsonNode body = readBody(response);
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(response));
                assertEquals(5, body.path("total").asInt());
                assertEquals(2, body.path("page").asInt());
                assertEquals(2, body.path("items").size());
            }
        });
    }

    @Test
    void browseUnknownRootReturns404() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            installRoutes(app, createBrowseService());
            try (Response response = app.client().get("/api/v1/browse?path=nonexistent")) {
                assertEquals(HttpStatusCode.NotFound, MediaApiTestSupport.status(response));
            }
        });
    }

    @Test
    void browseWithInvalidSortReturns400() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            installRoutes(app, createBrowseService());
            try (Response response = app.client().get("/api/v1/browse?path=movies&sort=invalid")) {
                assertEquals(HttpStatusCode.BadRequest, MediaApiTestSupport.status(response));
            }
        });
    }

    @Test
    void searchFilesReturnsMatches() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            installRoutes(app, createBrowseService());
            createVideo(moviesRoot, "test_movie.mp4");
            Files.write(moviesRoot.resolve("other.mp4"), new byte[100]);

            try (Response response = app.client().get("/api/v1/search/files?query=test")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(response));
                JsonNode body = readBody(response);
                assertEquals(1, body.path("total").asInt());
                assertEquals("test", body.path("query").asText());
            }
        });
    }

    @Test
    void searchFilesExposeObjectIdentityForFileResults() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            installRoutes(app, createBrowseServiceWithObjects());
            createVideo(moviesRoot, "test_movie.mp4");

            try (Response response = app.client().get("/api/v1/search/files?query=test_movie")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(response));
                JsonNode item = readBody(response).path("items").get(0);
                assertNotNull(item.path("objectId").asText(null));
                assertEquals("VIDEO", item.path("mediaKind").asText());
            }
        });
    }

    @Test
    void searchFilesExposeRouteTemplatesAndMediaLinks() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            installRoutes(app, createBrowseServiceWithObjects());
            createImage(moviesRoot, "hero-contract.jpg");

            try (Response response = app.client().get("/api/v1/search/files?query=hero-contract")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(response));
                JsonNode body = readBody(response);
                assertEquals("/api/v1/images/file?path={path}", body.path("routeTemplates").path("image").asText());
                assertEquals("/api/v1/search/files?query={query}", body.path("routeTemplates").path("search").asText());
                JsonNode item = body.path("items").get(0);
                assertEquals("/api/v1/images/file?path=movies/hero-contract.jpg", item.path("links").path("imageUrl").asText());
                assertEquals(
                    "/api/v1/images/file?path=movies/hero-contract.jpg",
                    item.path("capabilities").path("preferredPlaybackEndpoint").asText()
                );
            }
        });
    }

    @Test
    void searchFilesWithoutQueryReturns400() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            installRoutes(app, createBrowseService());
            try (Response response = app.client().get("/api/v1/search/files")) {
                assertEquals(HttpStatusCode.BadRequest, MediaApiTestSupport.status(response));
            }
        });
    }

    @Test
    void searchFilesReturnsVirtualPaths() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            installRoutes(app, createBrowseService());
            Path sub = Files.createDirectories(moviesRoot.resolve("action"));
            createVideo(sub, "hero.mp4");

            try (Response response = app.client().get("/api/v1/search/files?query=hero")) {
                JsonNode items = readBody(response).path("items");
                assertEquals("movies/action/hero.mp4", items.get(0).path("path").asText());
            }
        });
    }

    @Test
    void searchFilesWithPagination() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            installRoutes(app, createBrowseService());
            for (int i = 1; i <= 5; i++) {
                createVideo(moviesRoot, "match_" + i + ".mp4");
            }

            try (Response response = app.client().get("/api/v1/search/files?query=match&page=1&limit=2")) {
                JsonNode body = readBody(response);
                assertEquals(5, body.path("total").asInt());
                assertEquals(2, body.path("items").size());
            }
        });
    }

    @Test
    void browseWithSortDateReturnsResultsSortedByDate() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            installRoutes(app, createBrowseService());
            Files.write(moviesRoot.resolve("a.mp4"), new byte[100]);
            Files.write(moviesRoot.resolve("b.mp4"), new byte[200]);

            try (Response response = app.client().get("/api/v1/browse?path=movies&sort=date")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(response));
                assertEquals(2, readBody(response).path("total").asInt());
            }
        });
    }

    @Test
    void browseWithSortSizeReturnsResultsSortedBySize() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            installRoutes(app, createBrowseService());
            Files.write(moviesRoot.resolve("small.mp4"), new byte[100]);
            Files.write(moviesRoot.resolve("big.mp4"), new byte[10_000]);

            try (Response response = app.client().get("/api/v1/browse?path=movies&sort=size")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(response));
                assertEquals(2, readBody(response).path("total").asInt());
            }
        });
    }

    @Test
    void browseWithSortNameReturnsResultsSortedByNameDefault() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            installRoutes(app, createBrowseService());
            Files.write(moviesRoot.resolve("z.mp4"), new byte[100]);
            Files.write(moviesRoot.resolve("a.mp4"), new byte[100]);

            try (Response response = app.client().get("/api/v1/browse?path=movies&sort=name")) {
                JsonNode items = readBody(response).path("items");
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(response));
                assertEquals("a.mp4", items.get(0).path("name").asText());
            }
        });
    }

    @Test
    void browseWithSortSizeCaseInsensitiveWorks() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            installRoutes(app, createBrowseService());
            Files.write(moviesRoot.resolve("test.mp4"), new byte[100]);

            try (Response response = app.client().get("/api/v1/browse?path=movies&sort=SIZE")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(response));
            }
        });
    }

    @Test
    void browseWithSortDateMixedCaseWorks() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            installRoutes(app, createBrowseService());
            Files.write(moviesRoot.resolve("test.mp4"), new byte[100]);

            try (Response response = app.client().get("/api/v1/browse?path=movies&sort=Date")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(response));
            }
        });
    }

    @Test
    void searchWithEmptyStringReturnsAllFiles() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            installRoutes(app, createBrowseService());
            Files.write(moviesRoot.resolve("file1.mp4"), new byte[100]);
            Files.write(moviesRoot.resolve("file2.mp4"), new byte[100]);

            try (Response response = app.client().get("/api/v1/search/files?query=")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(response));
                assertEquals(2, readBody(response).path("total").asInt());
            }
        });
    }

    private static ProbeResult videoProbe(Path path, double durationSeconds) throws Exception {
        return new ProbeResult(
            path.toString(),
            "mp4",
            durationSeconds,
            Files.size(path),
            new ProbeStreams(
                List.of(new VideoStream(0, "h264", 1920, 1080, 24.0)),
                List.of(),
                List.of()
            )
        );
    }

    private record FixedMediaProber(ProbeResult result) implements MediaProber {
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
}
