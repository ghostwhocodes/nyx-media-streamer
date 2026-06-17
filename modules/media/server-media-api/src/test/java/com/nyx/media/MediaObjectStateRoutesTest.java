package com.nyx.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.common.DatabaseResources;
import com.nyx.common.VirtualPathResolver;
import com.nyx.config.MediaRootConfig;
import com.nyx.http.ContentType;
import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import com.nyx.http.UserIdPrincipal;
import com.nyx.json.NyxJson;
import com.nyx.media.contracts.MediaKind;
import com.nyx.media.contracts.MediaObject;
import com.nyx.media.contracts.MediaObjectContracts;
import com.nyx.media.contracts.MediaObjectStatus;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaObjectStateRoutesTest {
    private static final List<Integer> CONFIGURED_THUMBNAIL_SIZES = List.of(320, 640);

    @TempDir
    Path tempDir;

    private final List<HikariDataSource> dataSources = new ArrayList<>();
    private final ObjectMapper json = NyxJson.newMapper();

    @AfterEach
    void tearDown() {
        MediaApiTestSupport.closeDataSources(dataSources);
    }

    private record TestServices(
        MediaObjectService mediaObjectService,
        UserMediaStateService userMediaStateService,
        MediaThumbnailService mediaThumbnailService
    ) {
    }

    private TestServices createServices() {
        DatabaseResources resources = MediaObjectService.createDatabase(tempDir);
        dataSources.add(resources.getDataSource());
        return new TestServices(
            new MediaObjectService(resources.getJdbi()),
            new UserMediaStateService(resources.getJdbi()),
            new MediaThumbnailService(resources.getJdbi())
        );
    }

    private void installMediaStateAuth(MediaApiTestSupport.ApplicationHarness app) {
        app.installBearerAuth(
            "api-token",
            credential -> switch (credential.token()) {
                case "alice-token" -> new UserIdPrincipal("alice");
                case "bob-token" -> new UserIdPrincipal("bob");
                default -> null;
            }
        );
    }

    private JsonNode readBody(Response response) throws Exception {
        return json.readTree(MediaApiTestSupport.bodyAsText(response));
    }

    private MediaObject createObject(MediaObjectService service, String name, MediaKind mediaKind) throws Exception {
        Path mediaPath = tempDir.resolve(name);
        if (mediaPath.getParent() != null) {
            Files.createDirectories(mediaPath.getParent());
        }
        Files.writeString(mediaPath, "fixture");
        return service.upsertPrimaryPath(new MediaObjectUpsertRequest(
            mediaKind,
            mediaPath.toString(),
            mimeType(mediaKind),
            1_024,
            "2026-04-10T12:00:00Z",
            name,
            mediaKind == MediaKind.VIDEO || mediaKind == MediaKind.AUDIO ? 120_000L : null,
            mediaKind == MediaKind.VIDEO || mediaKind == MediaKind.IMAGE ? 1920 : null,
            mediaKind == MediaKind.VIDEO || mediaKind == MediaKind.IMAGE ? 1080 : null,
            mediaKind == MediaKind.AUDIO ? 2 : null,
            null,
            null,
            null,
            null,
            MediaObjectContracts.MEDIA_OBJECT_HASH_ALGORITHM_NONE,
            null,
            MediaObjectStatus.ACTIVE
        ));
    }

    private MediaObject replaceObject(MediaObjectService service, String name, MediaKind mediaKind) {
        return service.upsertPrimaryPath(new MediaObjectUpsertRequest(
            mediaKind,
            tempDir.resolve(name).toString(),
            mimeType(mediaKind),
            9_999,
            "2026-04-10T12:30:00Z",
            name,
            mediaKind == MediaKind.VIDEO || mediaKind == MediaKind.AUDIO ? 180_000L : null,
            mediaKind == MediaKind.VIDEO || mediaKind == MediaKind.IMAGE ? 1920 : null,
            mediaKind == MediaKind.VIDEO || mediaKind == MediaKind.IMAGE ? 1080 : null,
            mediaKind == MediaKind.AUDIO ? 2 : null,
            null,
            null,
            null,
            null,
            MediaObjectContracts.MEDIA_OBJECT_HASH_ALGORITHM_NONE,
            null,
            MediaObjectStatus.ACTIVE
        ));
    }

    private String mimeType(MediaKind mediaKind) {
        return switch (mediaKind) {
            case VIDEO -> "video/mp4";
            case AUDIO -> "audio/flac";
            case IMAGE -> "image/jpeg";
            case OTHER -> "application/octet-stream";
        };
    }

    private JsonNode findItemByObjectId(JsonNode items, String objectId) {
        for (JsonNode item : items) {
            if (objectId.equals(item.path("media").path("objectId").asText())) {
                return item;
            }
        }
        throw new AssertionError("Missing objectId " + objectId);
    }

    @Test
    void mediaObjectStateRoutesGetAndReplaceAuthenticatedObjectKeyedState() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            TestServices services = createServices();
            MediaObject videoObject = createObject(services.mediaObjectService(), "movie.mp4", MediaKind.VIDEO);

            installMediaStateAuth(app);
            app.routing(route -> MediaObjectStateRoutes.mediaObjectStateRoutes(
                route,
                services.mediaObjectService(),
                services.userMediaStateService(),
                List.of("api-token")
            ));

            try (Response initial = app.client().get(
                "/api/v1/media/objects/" + videoObject.objectId() + "/state",
                request -> request.header(HttpHeaders.Authorization, "Bearer alice-token")
            )) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(initial));
                JsonNode initialBody = readBody(initial);
                assertEquals(videoObject.objectId(), initialBody.path("objectId").asText());
                assertFalse(initialBody.path("favorite").asBoolean());
            }

            try (Response updated = app.client().put(
                "/api/v1/media/objects/" + videoObject.objectId() + "/state",
                request -> {
                    request.header(HttpHeaders.Authorization, "Bearer alice-token");
                    request.header(HttpHeaders.ContentType, ContentType.Application.Json.getValue());
                    request.setBody("""
                        {
                          "resumePositionMillis": 42000,
                          "favorite": true,
                          "rating": 8
                        }
                        """.trim());
                }
            )) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(updated));
                JsonNode updatedBody = readBody(updated);
                assertEquals(42_000L, updatedBody.path("resumePositionMillis").asLong());
                assertTrue(updatedBody.path("favorite").asBoolean());
                assertEquals(8, updatedBody.path("rating").asInt());
            }

            try (Response bobState = app.client().get(
                "/api/v1/media/objects/" + videoObject.objectId() + "/state",
                request -> request.header(HttpHeaders.Authorization, "Bearer bob-token")
            )) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(bobState));
                JsonNode bobStateBody = readBody(bobState);
                assertFalse(bobStateBody.path("favorite").asBoolean());
                assertTrue(bobStateBody.path("resumePositionMillis").isNull());
            }
        });
    }

    @Test
    void mediaObjectStateRoutesRequireAuthenticationWhenConfigured() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            TestServices services = createServices();
            MediaObject videoObject = createObject(services.mediaObjectService(), "movie.mp4", MediaKind.VIDEO);

            installMediaStateAuth(app);
            app.routing(route -> MediaObjectStateRoutes.mediaObjectStateRoutes(
                route,
                services.mediaObjectService(),
                services.userMediaStateService(),
                List.of("api-token")
            ));

            try (Response response = app.client().get("/api/v1/media/objects/" + videoObject.objectId() + "/state")) {
                assertEquals(HttpStatusCode.Unauthorized, MediaApiTestSupport.status(response));
            }
        });
    }

    @Test
    void mediaObjectStateRoutesAreNotMountedWhenAuthIsDisabled() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            TestServices services = createServices();
            MediaObject videoObject = createObject(services.mediaObjectService(), "movie.mp4", MediaKind.VIDEO);

            app.routing(route -> MediaObjectStateRoutes.mediaObjectStateRoutes(
                route,
                services.mediaObjectService(),
                services.userMediaStateService(),
                List.of()
            ));

            try (Response response = app.client().get("/api/v1/media/objects/" + videoObject.objectId() + "/state")) {
                assertEquals(HttpStatusCode.NotFound, MediaApiTestSupport.status(response));
            }
        });
    }

    @Test
    void favoritesAndContinueWatchingViewsSpanAudioVideoAndImageObjectsByObjectId() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            TestServices services = createServices();
            VirtualPathResolver virtualPathResolver = new VirtualPathResolver(
                List.of(new MediaRootConfig(tempDir, "local", "library"))
            );
            MediaObject audioObject = createObject(services.mediaObjectService(), "song.flac", MediaKind.AUDIO);
            MediaObject videoObject = createObject(services.mediaObjectService(), "movie.mp4", MediaKind.VIDEO);
            MediaObject imageObject = createObject(services.mediaObjectService(), "photo.jpg", MediaKind.IMAGE);
            services.mediaThumbnailService().ensurePlaceholder(audioObject);
            services.mediaThumbnailService().ensurePlaceholder(videoObject);
            services.mediaThumbnailService().ensurePlaceholder(imageObject);

            installMediaStateAuth(app);
            app.routing(route -> MediaObjectStateRoutes.mediaObjectStateRoutes(
                route,
                services.mediaObjectService(),
                services.userMediaStateService(),
                virtualPathResolver,
                CONFIGURED_THUMBNAIL_SIZES,
                List.of("api-token")
            ));

            try (Response ignored = app.client().put(
                "/api/v1/media/objects/" + audioObject.objectId() + "/state",
                request -> {
                    request.header(HttpHeaders.Authorization, "Bearer alice-token");
                    request.header(HttpHeaders.ContentType, ContentType.Application.Json.getValue());
                    request.setBody("""
                        {
                          "resumePositionMillis": 15000,
                          "favorite": true
                        }
                        """.trim());
                }
            )) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(ignored));
            }

            try (Response ignored = app.client().put(
                "/api/v1/media/objects/" + videoObject.objectId() + "/state",
                request -> {
                    request.header(HttpHeaders.Authorization, "Bearer alice-token");
                    request.header(HttpHeaders.ContentType, ContentType.Application.Json.getValue());
                    request.setBody("""
                        {
                          "resumePositionMillis": 90000
                        }
                        """.trim());
                }
            )) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(ignored));
            }

            try (Response ignored = app.client().put(
                "/api/v1/media/objects/" + imageObject.objectId() + "/state",
                request -> {
                    request.header(HttpHeaders.Authorization, "Bearer alice-token");
                    request.header(HttpHeaders.ContentType, ContentType.Application.Json.getValue());
                    request.setBody("""
                        {
                          "watched": true,
                          "favorite": true,
                          "rating": 9
                        }
                        """.trim());
                }
            )) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(ignored));
            }

            try (
                Response favorites = app.client().get(
                    "/api/v1/media/state/favorites",
                    request -> request.header(HttpHeaders.Authorization, "Bearer alice-token")
                );
                Response continueWatching = app.client().get(
                    "/api/v1/media/state/continue-watching",
                    request -> request.header(HttpHeaders.Authorization, "Bearer alice-token")
                )
            ) {
                String favoritesBodyText = MediaApiTestSupport.bodyAsText(favorites);
                String continueBodyText = MediaApiTestSupport.bodyAsText(continueWatching);
                JsonNode favoriteItems = json.readTree(favoritesBodyText).path("items");
                JsonNode continueItems = json.readTree(continueBodyText).path("items");

                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(favorites));
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(continueWatching));

                JsonNode audioFavorite = findItemByObjectId(favoriteItems, audioObject.objectId()).path("media");
                JsonNode imageFavorite = findItemByObjectId(favoriteItems, imageObject.objectId()).path("media");
                JsonNode videoContinue = findItemByObjectId(continueItems, videoObject.objectId()).path("media");

                assertEquals(2, favoriteItems.size());
                assertEquals(2, continueItems.size());
                assertEquals("AUDIO", audioFavorite.path("mediaKind").asText());
                assertEquals("IMAGE", imageFavorite.path("mediaKind").asText());
                assertEquals("VIDEO", videoContinue.path("mediaKind").asText());
                assertEquals("library/song.flac", audioFavorite.path("path").asText());
                assertEquals("library/photo.jpg", imageFavorite.path("path").asText());
                assertEquals("library/movie.mp4", videoContinue.path("path").asText());
                assertEquals("MISSING", audioFavorite.path("primaryThumbnail").path("status").asText());
                assertTrue(audioFavorite.path("primaryThumbnail").path("url").isNull());
                assertEquals("PENDING", imageFavorite.path("primaryThumbnail").path("status").asText());
                assertEquals(
                    "/api/v1/images/thumb?path=library/photo.jpg&size=320",
                    imageFavorite.path("primaryThumbnail").path("url").asText()
                );
                assertEquals("PENDING", videoContinue.path("primaryThumbnail").path("status").asText());
                assertEquals(
                    "/api/v1/images/thumb?path=library/movie.mp4&size=320",
                    videoContinue.path("primaryThumbnail").path("url").asText()
                );
                assertTrue(favoritesBodyText.contains("\"favorite\":true"));
                assertFalse(favoritesBodyText.contains(tempDir.toString()));
                assertFalse(continueBodyText.contains(tempDir.toString()));
                assertFalse(favoritesBodyText.contains("\"mediaObject\""));
                assertFalse(favoritesBodyText.contains("\"pathKey\""));
            }
        });
    }

    @Test
    void favoritesKeepReplacedObjectsButNoLongerExposeAnOpenPath() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            TestServices services = createServices();
            VirtualPathResolver virtualPathResolver = new VirtualPathResolver(
                List.of(new MediaRootConfig(tempDir, "local", "library"))
            );
            MediaObject original = createObject(services.mediaObjectService(), "movie.mp4", MediaKind.VIDEO);

            installMediaStateAuth(app);
            app.routing(route -> MediaObjectStateRoutes.mediaObjectStateRoutes(
                route,
                services.mediaObjectService(),
                services.userMediaStateService(),
                virtualPathResolver,
                List.of(150),
                List.of("api-token")
            ));

            try (Response ignored = app.client().put(
                "/api/v1/media/objects/" + original.objectId() + "/state",
                request -> {
                    request.header(HttpHeaders.Authorization, "Bearer alice-token");
                    request.header(HttpHeaders.ContentType, ContentType.Application.Json.getValue());
                    request.setBody("""
                        {
                          "resumePositionMillis": 42000,
                          "favorite": true
                        }
                        """.trim());
                }
            )) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(ignored));
            }

            MediaObject replacement = replaceObject(services.mediaObjectService(), "movie.mp4", MediaKind.VIDEO);
            assertNotEquals(original.objectId(), replacement.objectId());

            try (Response favorites = app.client().get(
                "/api/v1/media/state/favorites",
                request -> request.header(HttpHeaders.Authorization, "Bearer alice-token")
            )) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(favorites));
                String body = MediaApiTestSupport.bodyAsText(favorites);
                JsonNode media = json.readTree(body).path("items").get(0).path("media");
                assertEquals(original.objectId(), media.path("objectId").asText());
                assertEquals("MISSING", media.path("status").asText());
                assertTrue(media.path("path").isNull());
                assertFalse(body.contains(tempDir.toString()));
                assertFalse(body.contains("\"pathKey\""));
            }
        });
    }

    @Test
    void favoritesHideActivePathsThatNoLongerMapToAConfiguredVirtualRoot() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            TestServices services = createServices();
            VirtualPathResolver virtualPathResolver = new VirtualPathResolver(
                List.of(new MediaRootConfig(tempDir, "local", "library"))
            );
            Path outsideRoot = Files.createTempDirectory("nyx-media-state-orphan");
            try {
                Path orphanPath = outsideRoot.resolve("movie.mp4");
                if (orphanPath.getParent() != null) {
                    Files.createDirectories(orphanPath.getParent());
                }
                Files.writeString(orphanPath, "fixture");
                MediaObject orphanObject = services.mediaObjectService().upsertPrimaryPath(new MediaObjectUpsertRequest(
                    MediaKind.VIDEO,
                    orphanPath.toString(),
                    "video/mp4",
                    1_024,
                    "2026-04-10T12:00:00Z",
                    "movie.mp4",
                    120_000L,
                    1920,
                    1080,
                    null,
                    null,
                    null,
                    null,
                    null,
                    MediaObjectContracts.MEDIA_OBJECT_HASH_ALGORITHM_NONE,
                    null,
                    MediaObjectStatus.ACTIVE
                ));
                services.mediaThumbnailService().ensurePlaceholder(orphanObject);

                installMediaStateAuth(app);
                app.routing(route -> MediaObjectStateRoutes.mediaObjectStateRoutes(
                    route,
                    services.mediaObjectService(),
                    services.userMediaStateService(),
                    virtualPathResolver,
                    CONFIGURED_THUMBNAIL_SIZES,
                    List.of("api-token")
                ));

                try (Response ignored = app.client().put(
                    "/api/v1/media/objects/" + orphanObject.objectId() + "/state",
                    request -> {
                        request.header(HttpHeaders.Authorization, "Bearer alice-token");
                        request.header(HttpHeaders.ContentType, ContentType.Application.Json.getValue());
                        request.setBody("""
                            {
                              "favorite": true
                            }
                            """.trim());
                    }
                )) {
                    assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(ignored));
                }

                try (Response favorites = app.client().get(
                    "/api/v1/media/state/favorites",
                    request -> request.header(HttpHeaders.Authorization, "Bearer alice-token")
                )) {
                    assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(favorites));
                    String body = MediaApiTestSupport.bodyAsText(favorites);
                    JsonNode media = json.readTree(body).path("items").get(0).path("media");
                    JsonNode thumbnail = media.path("primaryThumbnail");
                    assertEquals(orphanObject.objectId(), media.path("objectId").asText());
                    assertTrue(media.path("path").isNull());
                    assertEquals("PENDING", thumbnail.path("status").asText());
                    assertTrue(thumbnail.path("url").isNull());
                    assertFalse(body.contains(orphanPath.toString()));
                    assertFalse(body.contains(outsideRoot.toString()));
                }
            } finally {
                MediaApiTestSupport.deleteRecursively(outsideRoot);
            }
        });
    }
}
