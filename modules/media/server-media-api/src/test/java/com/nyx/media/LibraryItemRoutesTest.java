package com.nyx.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.http.ContentType;
import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import com.nyx.json.NyxJson;
import com.nyx.media.contracts.CreateLibraryRequest;
import com.nyx.media.contracts.LibraryArtworkSource;
import com.nyx.media.contracts.LibraryItemType;
import com.nyx.media.contracts.LibrarySourceRootWriteRequest;
import com.nyx.media.contracts.LibraryType;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryItemRoutesTest {
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
        MediaObjectResolver resolver = new MediaObjectResolver(
            mediaObjectService,
            new com.nyx.ffmpeg.ProbeService(),
            new AudioMetadataService(new com.nyx.ffmpeg.ProbeService())
        );

        return new TestServices(
            libraryService,
            catalogService,
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

    private JsonNode findArtworkByKind(JsonNode artwork, String kind) {
        for (JsonNode item : artwork) {
            if (kind.equals(item.path("kind").asText())) {
                return item;
            }
        }
        throw new AssertionError("Missing artwork with kind " + kind);
    }

    @Test
    void libraryItemRoutesExposeRootAndChildItemListings() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            TestServices services = createServices();
            Path root = Files.createDirectories(tempDir.resolve("shows"));
            writeMediaFile(root.resolve("Space Show/Season 01/Space.Show.S01E01.mkv"));
            var library = services.libraryService().createLibrary(
                new CreateLibraryRequest(
                    "Shows",
                    LibraryType.SHOW,
                    List.of(new LibrarySourceRootWriteRequest(root.toString()))
                )
            );
            services.scanService().runScanNow(library.libraryId(), LibraryScanMode.IMPORT);

            app.routing(route -> LibraryRoutes.libraryRoutes(route, services.libraryService(), services.catalogService()));

            try (Response rootResponse = app.client().get("/api/v1/libraries/" + library.libraryId() + "/items")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(rootResponse));
                JsonNode rootListing = readBody(rootResponse);
                JsonNode showItem = rootListing.path("items").get(0);
                assertEquals(LibraryItemType.SHOW.name(), showItem.path("type").asText());

                String showId = showItem.path("libraryItemId").asText();
                try (Response seasonsResponse = app.client().get(
                    "/api/v1/libraries/" + library.libraryId() + "/items?parentItemId=" + showId
                )) {
                    assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(seasonsResponse));
                    JsonNode seasonListing = readBody(seasonsResponse);
                    JsonNode seasonItem = seasonListing.path("items").get(0);
                    assertEquals(LibraryItemType.SEASON.name(), seasonItem.path("type").asText());

                    String seasonId = seasonItem.path("libraryItemId").asText();
                    try (Response episodesResponse = app.client().get(
                        "/api/v1/libraries/" + library.libraryId() + "/items?parentItemId=" + seasonId
                    )) {
                        assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(episodesResponse));
                        JsonNode episodeListing = readBody(episodesResponse);
                        JsonNode episodeItem = episodeListing.path("items").get(0);
                        assertEquals(LibraryItemType.EPISODE.name(), episodeItem.path("type").asText());

                        try (Response itemResponse = app.client().get(
                            "/api/v1/libraries/" + library.libraryId() + "/items/" + episodeItem.path("libraryItemId").asText()
                        )) {
                            assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(itemResponse));
                            assertTrue(MediaApiTestSupport.bodyAsText(itemResponse).contains("\"episodeNumber\":1"));
                        }
                    }
                }
            }
        });
    }

    @Test
    void libraryItemRoutesKeepUnmatchedEntriesVisibleAtTheRootLevel() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            TestServices services = createServices();
            Path root = Files.createDirectories(tempDir.resolve("shows"));
            writeMediaFile(root.resolve("bonus-featurette.mkv"));
            var library = services.libraryService().createLibrary(
                new CreateLibraryRequest(
                    "Shows",
                    LibraryType.SHOW,
                    List.of(new LibrarySourceRootWriteRequest(root.toString()))
                )
            );
            services.scanService().runScanNow(library.libraryId(), LibraryScanMode.IMPORT);

            app.routing(route -> LibraryRoutes.libraryRoutes(route, services.libraryService(), services.catalogService()));

            try (Response response = app.client().get("/api/v1/libraries/" + library.libraryId() + "/items")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(response));
                JsonNode unmatched = readBody(response).path("items").get(0);
                assertEquals(LibraryItemType.UNMATCHED.name(), unmatched.path("type").asText());
                assertTrue(unmatched.path("unmatchedReason").asText().contains("season or episode pattern missing"));
            }
        });
    }

    @Test
    void libraryItemRoutesSupportMetadataArtworkAndCollectionManagement() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            TestServices services = createServices();
            Path root = Files.createDirectories(tempDir.resolve("movies"));
            Path movieDir = Files.createDirectories(root.resolve("Example Movie"));
            Path movieFile = writeMediaFile(movieDir.resolve("Example.Movie.2024.mp4"));
            Path importedPoster = writeMediaFile(movieDir.resolve("poster.jpg"));
            writeMediaFile(movieDir.resolve("fanart.jpg"));
            writeMediaFile(movieDir.resolve("thumb.jpg"));
            Files.writeString(
                movieDir.resolve("Example.Movie.2024.nfo"),
                """
                <movie>
                  <title>Imported Title</title>
                  <sorttitle>Imported Sort</sorttitle>
                  <plot>Imported overview</plot>
                  <tag>Drama</tag>
                </movie>
                """.trim()
            );
            Path manualPoster = writeMediaFile(movieDir.resolve("manual-poster.png"));
            var library = services.libraryService().createLibrary(
                new CreateLibraryRequest(
                    "Movies",
                    LibraryType.MOVIE,
                    List.of(new LibrarySourceRootWriteRequest(root.toString()))
                )
            );
            services.scanService().runScanNow(library.libraryId(), LibraryScanMode.IMPORT);

            app.routing(route -> LibraryRoutes.libraryRoutes(route, services.libraryService(), services.catalogService()));

            String itemId;
            try (Response initialListing = app.client().get("/api/v1/libraries/" + library.libraryId() + "/items")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(initialListing));
                JsonNode item = readBody(initialListing).path("items").get(0);
                itemId = item.path("libraryItemId").asText();
                assertEquals(movieFile.toString(), item.path("primaryPath").asText());
                assertEquals("Imported Title", item.path("displayTitle").asText());
                assertEquals(importedPoster.toString(), item.path("artwork").get(0).path("path").asText());
            }

            try (Response metadataResponse = app.client().put(
                "/api/v1/libraries/" + library.libraryId() + "/items/" + itemId + "/metadata",
                request -> {
                    request.header(HttpHeaders.ContentType, ContentType.Application.Json.getValue());
                    request.setBody("""
                        {
                          "displayTitle": "Manual Title",
                          "sortTitle": "Manual Sort",
                          "overview": "Manual overview",
                          "tags": ["Favorite"]
                        }
                        """.trim());
                }
            )) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(metadataResponse));
                assertTrue(MediaApiTestSupport.bodyAsText(metadataResponse).contains("\"displayTitle\":\"Manual Title\""));
            }

            try (Response clearMetadataResponse = app.client().delete(
                "/api/v1/libraries/" + library.libraryId() + "/items/" + itemId + "/metadata"
            )) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(clearMetadataResponse));
                JsonNode revertedItem = readBody(clearMetadataResponse);
                assertEquals("Imported Title", revertedItem.path("displayTitle").asText());
            }

            try (Response artworkResponse = app.client().put(
                "/api/v1/libraries/" + library.libraryId() + "/items/" + itemId + "/artwork",
                request -> {
                    request.header(HttpHeaders.ContentType, ContentType.Application.Json.getValue());
                    request.setBody("""
                        {
                          "posterPath": "%s"
                        }
                        """.formatted(manualPoster).trim());
                }
            )) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(artworkResponse));
                JsonNode updatedItem = readBody(artworkResponse);
                JsonNode poster = findArtworkByKind(updatedItem.path("artwork"), "POSTER");
                assertEquals(LibraryArtworkSource.MANUAL.name(), poster.path("source").asText());
                assertEquals(manualPoster.toString(), poster.path("path").asText());
            }

            String collectionId;
            try (Response createdCollection = app.client().post(
                "/api/v1/libraries/" + library.libraryId() + "/collections",
                request -> {
                    request.header(HttpHeaders.ContentType, ContentType.Application.Json.getValue());
                    request.setBody("""
                        {
                          "title": "Weekend Movies",
                          "itemIds": ["%s"]
                        }
                        """.formatted(itemId).trim());
                }
            )) {
                assertEquals(HttpStatusCode.Created, MediaApiTestSupport.status(createdCollection));
                collectionId = readBody(createdCollection).path("collectionId").asText();
            }

            try (Response collectionsResponse = app.client().get("/api/v1/libraries/" + library.libraryId() + "/collections")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(collectionsResponse));
                assertTrue(MediaApiTestSupport.bodyAsText(collectionsResponse).contains(collectionId));
            }

            try (Response updatedCollection = app.client().put(
                "/api/v1/libraries/" + library.libraryId() + "/collections/" + collectionId,
                request -> {
                    request.header(HttpHeaders.ContentType, ContentType.Application.Json.getValue());
                    request.setBody("""
                        {
                          "title": "Friday Movies",
                          "itemIds": ["%s"]
                        }
                        """.formatted(itemId).trim());
                }
            )) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(updatedCollection));
                assertEquals("Friday Movies", readBody(updatedCollection).path("title").asText());
            }

            try (Response deleteArtworkResponse = app.client().delete(
                "/api/v1/libraries/" + library.libraryId() + "/items/" + itemId + "/artwork"
            )) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(deleteArtworkResponse));
                JsonNode revertedItem = readBody(deleteArtworkResponse);
                JsonNode poster = findArtworkByKind(revertedItem.path("artwork"), "POSTER");
                assertEquals(LibraryArtworkSource.FOLDER.name(), poster.path("source").asText());
            }

            try (Response deleteCollectionResponse = app.client().delete(
                "/api/v1/libraries/" + library.libraryId() + "/collections/" + collectionId
            )) {
                assertEquals(HttpStatusCode.NoContent, MediaApiTestSupport.status(deleteCollectionResponse));
            }

            try (Response collectionsAfterDelete = app.client().get("/api/v1/libraries/" + library.libraryId() + "/collections")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(collectionsAfterDelete));
                assertEquals(0, readBody(collectionsAfterDelete).path("items").size());
            }
        });
    }
}
