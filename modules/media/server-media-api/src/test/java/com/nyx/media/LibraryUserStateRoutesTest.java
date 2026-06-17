package com.nyx.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import com.nyx.http.UserIdPrincipal;
import com.nyx.json.NyxJson;
import com.nyx.media.contracts.CreateLibraryRequest;
import com.nyx.media.contracts.LibraryItemType;
import com.nyx.media.contracts.LibrarySourceRootWriteRequest;
import com.nyx.media.contracts.LibraryType;
import com.nyx.media.contracts.MediaKind;
import com.nyx.media.contracts.UserMediaStateWriteRequest;
import com.nyx.playback.contracts.MediaSessionPlaybackEvent;
import com.nyx.playback.contracts.MediaSessionPlaybackReport;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryUserStateRoutesTest {
    @TempDir
    Path tempDir;

    private final List<HikariDataSource> dataSources = new ArrayList<>();
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final ObjectMapper json = NyxJson.newMapper();

    @AfterEach
    void tearDown() {
        backgroundExecutor.shutdownNow();
        MediaApiTestSupport.closeDataSources(dataSources);
    }

    private record TestServices(
        LibraryService libraryService,
        LibraryCatalogService catalogService,
        UserMediaStateService userMediaStateService,
        LibraryUserStateService libraryUserStateService,
        LibraryScanService scanService
    ) {
    }

    private TestServices createServices() {
        var libraryResources = LibraryService.createDatabase(tempDir.resolve("libraries-db"));
        var mediaResources = MediaObjectService.createDatabase(tempDir.resolve("media-db"));
        dataSources.add(libraryResources.getDataSource());
        dataSources.add(mediaResources.getDataSource());

        MediaObjectService mediaObjectService = new MediaObjectService(mediaResources.getJdbi());
        LibraryService libraryService = new LibraryService(libraryResources.getJdbi());
        LibraryInterpretationService interpretationService = new LibraryInterpretationService(
            libraryResources.getJdbi(),
            libraryService,
            mediaObjectService
        );
        LibraryCatalogService catalogService = new LibraryCatalogService(
            libraryResources.getJdbi(),
            libraryService
        );
        UserMediaStateService userMediaStateService = new UserMediaStateService(mediaResources.getJdbi());
        LibraryUserStateService libraryUserStateService = new LibraryUserStateService(catalogService, userMediaStateService);
        MediaObjectResolver resolver = new MediaObjectResolver(
            mediaObjectService,
            new com.nyx.ffmpeg.ProbeService(),
            new AudioMetadataService(new com.nyx.ffmpeg.ProbeService())
        );

        return new TestServices(
            libraryService,
            catalogService,
            userMediaStateService,
            libraryUserStateService,
            new LibraryScanService(
                libraryResources.getJdbi(),
                libraryService,
                mediaObjectService,
                resolver,
                backgroundExecutor,
                interpretationService,
                catalogService
            )
        );
    }

    private void installLibraryAuth(MediaApiTestSupport.ApplicationHarness app) {
        app.installBearerAuth(
            "api-token",
            credential -> "admin-token".equals(credential.token()) ? new UserIdPrincipal("alice") : null
        );
    }

    private Path writeMediaFile(Path path) throws Exception {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.write(path, new byte[2_048]);
        return path;
    }

    private JsonNode readBody(Response response) throws Exception {
        return json.readTree(MediaApiTestSupport.bodyAsText(response));
    }

    @Test
    void libraryUserStateRoutesExposeItemSummariesFavoritesWatchedResumeAndContinueWatching() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            TestServices services = createServices();
            Path root = Files.createDirectories(tempDir.resolve("shows"));
            writeMediaFile(root.resolve("Space Show/Season 01/Space.Show.S01E01.mkv"));
            writeMediaFile(root.resolve("Space Show/Season 01/Space.Show.S01E02.mkv"));
            var library = services.libraryService().createLibrary(
                new CreateLibraryRequest(
                    "Shows",
                    LibraryType.SHOW,
                    List.of(new LibrarySourceRootWriteRequest(root.toString()))
                )
            );
            services.scanService().runScanNow(library.libraryId(), LibraryScanMode.IMPORT);

            var showItem = services.catalogService().listLibraryItems(library.libraryId()).items().getFirst();
            var seasonItem = services.catalogService().listLibraryItems(library.libraryId(), showItem.libraryItemId()).items().getFirst();
            var episodes = new ArrayList<>(services.catalogService().listLibraryItems(library.libraryId(), seasonItem.libraryItemId()).items());
            episodes.sort(Comparator.comparing(item -> item.episodeNumber()));
            services.userMediaStateService().putState(
                "alice",
                episodes.getFirst().sourceObjectId(),
                new UserMediaStateWriteRequest(42_000L, false, true, null)
            );
            services.userMediaStateService().putState(
                "alice",
                episodes.getLast().sourceObjectId(),
                new UserMediaStateWriteRequest(null, true, false, null)
            );

            installLibraryAuth(app);
            app.routing(route -> LibraryRoutes.libraryRoutes(
                route,
                services.libraryService(),
                services.catalogService(),
                services.libraryUserStateService(),
                List.of("api-token")
            ));

            try (Response unauthorized = app.client().get("/api/v1/libraries/" + library.libraryId() + "/state/items")) {
                assertEquals(HttpStatusCode.Unauthorized, MediaApiTestSupport.status(unauthorized));
            }

            try (Response itemStates = app.client().get(
                "/api/v1/libraries/" + library.libraryId() + "/state/items",
                request -> request.header(HttpHeaders.Authorization, "Bearer admin-token")
            )) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(itemStates));
                JsonNode rootEntry = readBody(itemStates).path("items").get(0);
                assertEquals(LibraryItemType.SHOW.name(), rootEntry.path("item").path("type").asText());
                assertEquals(2, rootEntry.path("state").path("leafCount").asInt());
                assertEquals(1, rootEntry.path("state").path("watchedLeafCount").asInt());
                assertEquals(1, rootEntry.path("state").path("favoriteLeafCount").asInt());
            }

            try (Response itemState = app.client().get(
                "/api/v1/libraries/" + library.libraryId() + "/items/" + showItem.libraryItemId() + "/state",
                request -> request.header(HttpHeaders.Authorization, "Bearer admin-token")
            )) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(itemState));
                assertTrue(MediaApiTestSupport.bodyAsText(itemState).contains("\"resumePositionMillis\":42000"));
            }

            try (Response favorites = app.client().get(
                "/api/v1/libraries/" + library.libraryId() + "/state/favorites",
                request -> request.header(HttpHeaders.Authorization, "Bearer admin-token")
            )) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(favorites));
                JsonNode favoriteEntry = readBody(favorites).path("items").get(0);
                assertEquals(LibraryItemType.EPISODE.name(), favoriteEntry.path("item").path("type").asText());
            }

            try (Response watched = app.client().get(
                "/api/v1/libraries/" + library.libraryId() + "/state/watched",
                request -> request.header(HttpHeaders.Authorization, "Bearer admin-token")
            )) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(watched));
                JsonNode watchedEntry = readBody(watched).path("items").get(0);
                assertEquals(LibraryItemType.EPISODE.name(), watchedEntry.path("item").path("type").asText());
            }

            try (Response resume = app.client().get(
                "/api/v1/libraries/" + library.libraryId() + "/state/resume",
                request -> request.header(HttpHeaders.Authorization, "Bearer admin-token")
            )) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(resume));
                JsonNode resumeEntry = readBody(resume).path("items").get(0);
                assertEquals("42000", resumeEntry.path("state").path("resumePositionMillis").asText());
            }

            try (Response continueWatching = app.client().get(
                "/api/v1/libraries/" + library.libraryId() + "/state/continue-watching",
                request -> request.header(HttpHeaders.Authorization, "Bearer admin-token")
            )) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(continueWatching));
                JsonNode continueEntry = readBody(continueWatching).path("items").get(0);
                assertEquals("42000", continueEntry.path("state").path("resumePositionMillis").asText());
            }
        });
    }

    @Test
    void libraryWatchedAndResumeRoutesFollowPlaybackReportProjections() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            TestServices services = createServices();
            Path root = Files.createDirectories(tempDir.resolve("projection-shows"));
            writeMediaFile(root.resolve("Signal Show/Season 01/Signal.Show.S01E01.mkv"));
            var library = services.libraryService().createLibrary(
                new CreateLibraryRequest(
                    "Projection Shows",
                    LibraryType.SHOW,
                    List.of(new LibrarySourceRootWriteRequest(root.toString()))
                )
            );
            services.scanService().runScanNow(library.libraryId(), LibraryScanMode.IMPORT);

            var showItem = services.catalogService().listLibraryItems(library.libraryId()).items().getFirst();
            var seasonItem = services.catalogService().listLibraryItems(library.libraryId(), showItem.libraryItemId()).items().getFirst();
            var episodeItem = services.catalogService().listLibraryItems(library.libraryId(), seasonItem.libraryItemId()).items().getFirst();
            String objectId = episodeItem.sourceObjectId();

            services.userMediaStateService().projectPlaybackState(
                "alice",
                objectId,
                new MediaSessionPlaybackReport(
                    MediaSessionPlaybackEvent.HEARTBEAT,
                    objectId,
                    MediaKind.VIDEO,
                    24_000L,
                    90_000L,
                    "2026-04-15T12:00:00Z",
                    null,
                    null,
                    null
                )
            );

            installLibraryAuth(app);
            app.routing(route -> LibraryRoutes.libraryRoutes(
                route,
                services.libraryService(),
                services.catalogService(),
                services.libraryUserStateService(),
                List.of("api-token")
            ));

            try (Response resumeBeforeComplete = app.client().get(
                "/api/v1/libraries/" + library.libraryId() + "/state/resume",
                request -> request.header(HttpHeaders.Authorization, "Bearer admin-token")
            )) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(resumeBeforeComplete));
                JsonNode resumeBeforeBody = readBody(resumeBeforeComplete).path("items");
                assertEquals(1, resumeBeforeBody.size());
                assertEquals("24000", resumeBeforeBody.get(0).path("state").path("resumePositionMillis").asText());
            }

            services.userMediaStateService().projectPlaybackState(
                "alice",
                objectId,
                new MediaSessionPlaybackReport(
                    MediaSessionPlaybackEvent.COMPLETED,
                    objectId,
                    MediaKind.VIDEO,
                    90_000L,
                    90_000L,
                    "2026-04-15T12:10:00Z",
                    null,
                    null,
                    null
                )
            );

            try (Response watchedAfterComplete = app.client().get(
                "/api/v1/libraries/" + library.libraryId() + "/state/watched",
                request -> request.header(HttpHeaders.Authorization, "Bearer admin-token")
            )) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(watchedAfterComplete));
                JsonNode watchedAfterBody = readBody(watchedAfterComplete).path("items");
                assertEquals(1, watchedAfterBody.size());
                assertEquals(
                    episodeItem.libraryItemId(),
                    watchedAfterBody.get(0).path("item").path("libraryItemId").asText()
                );
            }

            try (Response resumeAfterComplete = app.client().get(
                "/api/v1/libraries/" + library.libraryId() + "/state/resume",
                request -> request.header(HttpHeaders.Authorization, "Bearer admin-token")
            )) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(resumeAfterComplete));
                JsonNode resumeAfterBody = readBody(resumeAfterComplete).path("items");
                assertEquals(0, resumeAfterBody.size());
            }
        });
    }
}
