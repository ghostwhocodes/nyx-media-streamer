package com.nyx.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.common.DatabaseResources;
import com.nyx.common.PathSecurity;
import com.nyx.common.VirtualPathResolver;
import com.nyx.config.MediaRootConfig;
import com.nyx.http.ContentType;
import com.nyx.http.HttpStatusCode;
import com.nyx.json.NyxJson;
import com.nyx.media.model.ChapterSet;
import com.nyx.media.model.CreateChapterMarkRequest;
import com.nyx.media.model.UpdateChapterMarkRequest;
import com.nyx.media.model.UpsertChapterMarkRequest;
import com.nyx.media.model.UpsertChapterSetRequest;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChapterRoutesTest {
    @TempDir
    Path tempDir;

    private Path mediaDir;
    private int dbCounter;
    private final List<HikariDataSource> dataSources = new ArrayList<>();
    private final ObjectMapper json = NyxJson.newMapper();

    @BeforeEach
    void setup() throws Exception {
        mediaDir = Files.createDirectories(tempDir.resolve("media"));
        dbCounter = 0;
    }

    @AfterEach
    void teardown() {
        MediaApiTestSupport.closeDataSources(dataSources);
    }

    private record TestServices(
        ChapterService chapterService,
        PathSecurity pathSecurity,
        VirtualPathResolver virtualPathResolver
    ) {
    }

    private TestServices createServices() throws Exception {
        dbCounter++;
        PathSecurity pathSecurity = new PathSecurity(List.of(tempDir));
        Path dbDir = Files.createDirectories(tempDir.resolve("db" + dbCounter));
        DatabaseResources resources = ChapterService.createDatabase(dbDir);
        dataSources.add(resources.getDataSource());
        return new TestServices(new ChapterService(resources.getJdbi(), pathSecurity), pathSecurity, null);
    }

    private TestServices createVirtualServices() throws Exception {
        return createVirtualServices("");
    }

    private TestServices createVirtualServices(String displayName) throws Exception {
        dbCounter++;
        PathSecurity pathSecurity = new PathSecurity(List.of(tempDir));
        Path dbDir = Files.createDirectories(tempDir.resolve("db" + dbCounter));
        DatabaseResources resources = ChapterService.createDatabase(dbDir);
        dataSources.add(resources.getDataSource());
        return new TestServices(
            new ChapterService(resources.getJdbi(), pathSecurity),
            pathSecurity,
            new VirtualPathResolver(List.of(new MediaRootConfig(tempDir, "local", displayName)))
        );
    }

    private Path createMediaFile(String name) throws Exception {
        Path file = mediaDir.resolve(name);
        Files.write(file, new byte[256]);
        return file;
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize request body", exception);
        }
    }

    private void installRoutes(MediaApiTestSupport.ApplicationHarness app, TestServices services) {
        app.routing(route -> ChapterRoutes.chapterRoutes(
            route,
            services.chapterService(),
            services.pathSecurity(),
            List.of(),
            services.virtualPathResolver()
        ));
    }

    @Test
    void getChaptersReturnsEmptyResultWhenNoneExist() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            TestServices services = createServices();
            installRoutes(app, services);
            Path mediaFile = createMediaFile("movie.mkv");

            try (Response response = app.client().get("/api/v1/chapters?path=" + mediaFile)) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(response));
                ChapterSet body = json.readValue(MediaApiTestSupport.bodyAsText(response), ChapterSet.class);
                assertEquals(null, body.id());
                assertEquals(mediaFile.toRealPath().toString(), body.mediaPath());
                assertTrue(body.marks().isEmpty());
            }
        });
    }

    @Test
    void putChaptersStoresMarksAndGetChaptersReturnsThem() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            TestServices services = createServices();
            installRoutes(app, services);
            Path mediaFile = createMediaFile("movie.mkv");

            ChapterSet stored;
            try (Response putResponse = app.client().put("/api/v1/chapters", request -> {
                request.contentType(ContentType.Application.Json);
                request.setBody(writeJson(
                    new UpsertChapterSetRequest(
                        mediaFile.toString(),
                        "Movie",
                        List.of(
                            new UpsertChapterMarkRequest("Middle", 30.5, "", 2),
                            new UpsertChapterMarkRequest("Intro", 0.0, "", 0)
                        )
                    )
                ));
            })) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(putResponse));
                stored = json.readValue(MediaApiTestSupport.bodyAsText(putResponse), ChapterSet.class);
                assertEquals(List.of("Intro", "Middle"), stored.marks().stream().map(mark -> mark.label()).toList());
                assertEquals(List.of(0, 1), stored.marks().stream().map(mark -> mark.sortOrder()).toList());
            }

            try (Response getResponse = app.client().get("/api/v1/chapters?path=" + mediaFile)) {
                ChapterSet fetched = json.readValue(MediaApiTestSupport.bodyAsText(getResponse), ChapterSet.class);
                assertEquals(stored.id(), fetched.id());
                assertEquals(2, fetched.marks().size());
            }
        });
    }

    @Test
    void postMarkAppendsAndPutMarkUpdatesOrder() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            TestServices services = createServices();
            installRoutes(app, services);
            Path mediaFile = createMediaFile("movie.mkv");

            try (Response ignored = app.client().post("/api/v1/chapters/marks", request -> {
                request.contentType(ContentType.Application.Json);
                request.setBody(writeJson(new CreateChapterMarkRequest(mediaFile.toString(), "Intro", 0.0, "")));
            })) {
            }

            String markId;
            try (Response createResponse = app.client().post("/api/v1/chapters/marks", request -> {
                request.contentType(ContentType.Application.Json);
                request.setBody(writeJson(new CreateChapterMarkRequest(mediaFile.toString(), "Ending", 90.25, "")));
            })) {
                assertEquals(HttpStatusCode.Created, MediaApiTestSupport.status(createResponse));
                ChapterSet created = json.readValue(MediaApiTestSupport.bodyAsText(createResponse), ChapterSet.class);
                markId = created.marks().getLast().id();
            }

            try (Response updateResponse = app.client().put("/api/v1/chapters/marks/" + markId, request -> {
                request.contentType(ContentType.Application.Json);
                request.setBody(writeJson(new UpdateChapterMarkRequest("Ending", 90.25, "", 0)));
            })) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(updateResponse));
                ChapterSet updated = json.readValue(MediaApiTestSupport.bodyAsText(updateResponse), ChapterSet.class);
                assertEquals(List.of("Ending", "Intro"), updated.marks().stream().map(mark -> mark.label()).toList());
                assertEquals(List.of(0, 1), updated.marks().stream().map(mark -> mark.sortOrder()).toList());
            }
        });
    }

    @Test
    void deleteMarkRemovesChapterMark() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            TestServices services = createServices();
            installRoutes(app, services);
            Path mediaFile = createMediaFile("movie.mkv");

            ChapterSet created = services.chapterService().createMark(mediaFile.toString(), "Intro", 0.0, "");
            try (Response response = app.client().delete("/api/v1/chapters/marks/" + created.marks().getFirst().id())) {
                assertEquals(HttpStatusCode.NoContent, MediaApiTestSupport.status(response));
            }

            try (Response getResponse = app.client().get("/api/v1/chapters?path=" + mediaFile)) {
                ChapterSet fetched = json.readValue(MediaApiTestSupport.bodyAsText(getResponse), ChapterSet.class);
                assertTrue(fetched.marks().isEmpty());
                assertEquals(created.id(), fetched.id());
            }
        });
    }

    @Test
    void getChaptersRejectsPathOutsideAllowedRoots() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            TestServices services = createServices();
            installRoutes(app, services);
            Path outside = Files.createTempFile("nyx-outside", ".mkv");

            try {
                try (Response response = app.client().get("/api/v1/chapters?path=" + outside)) {
                    assertEquals(HttpStatusCode.Forbidden, MediaApiTestSupport.status(response));
                }
            } finally {
                Files.deleteIfExists(outside);
            }
        });
    }

    @Test
    void getChaptersWithVirtualRootsReturnsVirtualMediaPath() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            TestServices services = createVirtualServices();
            installRoutes(app, services);
            Path mediaFile = createMediaFile("movie.mkv");
            String virtualPath = services.virtualPathResolver().toVirtualPath(mediaFile);

            assertNotNull(virtualPath);
            services.chapterService().upsertForMediaPath(
                mediaFile.toString(),
                "Movie",
                List.of(new UpsertChapterMarkRequest("Intro", 0.0, "", 0))
            );

            try (Response response = app.client().get("/api/v1/chapters?path=" + virtualPath)) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(response));
                ChapterSet body = json.readValue(MediaApiTestSupport.bodyAsText(response), ChapterSet.class);
                assertEquals(virtualPath, body.mediaPath());
                assertEquals(List.of("Intro"), body.marks().stream().map(mark -> mark.label()).toList());
            }
        });
    }

    @Test
    void chapterRoutesAcceptSlashPrefixedVirtualPaths() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            TestServices services = createVirtualServices();
            installRoutes(app, services);
            Path mediaFile = createMediaFile("movie.mkv");
            String virtualPath = services.virtualPathResolver().toVirtualPath(mediaFile);

            assertNotNull(virtualPath);
            String slashPrefixedVirtualPath = "/" + virtualPath;

            try (Response putResponse = app.client().put("/api/v1/chapters", request -> {
                request.contentType(ContentType.Application.Json);
                request.setBody(writeJson(
                    new UpsertChapterSetRequest(
                        slashPrefixedVirtualPath,
                        "Movie",
                        List.of(new UpsertChapterMarkRequest("Intro", 0.0, "", 0))
                    )
                ));
            })) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(putResponse));
                ChapterSet stored = json.readValue(MediaApiTestSupport.bodyAsText(putResponse), ChapterSet.class);
                assertEquals(virtualPath, stored.mediaPath());
            }

            try (Response getResponse = app.client().get("/api/v1/chapters?path=" + slashPrefixedVirtualPath)) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(getResponse));
                ChapterSet fetched = json.readValue(MediaApiTestSupport.bodyAsText(getResponse), ChapterSet.class);
                assertEquals(virtualPath, fetched.mediaPath());
            }

            try (Response createResponse = app.client().post("/api/v1/chapters/marks", request -> {
                request.contentType(ContentType.Application.Json);
                request.setBody(writeJson(
                    new CreateChapterMarkRequest(slashPrefixedVirtualPath, "Ending", 90.25, "")
                ));
            })) {
                assertEquals(HttpStatusCode.Created, MediaApiTestSupport.status(createResponse));
                ChapterSet created = json.readValue(MediaApiTestSupport.bodyAsText(createResponse), ChapterSet.class);
                assertEquals(List.of("Intro", "Ending"), created.marks().stream().map(mark -> mark.label()).toList());
            }
        });
    }

    @Test
    void chapterRoutesAcceptAbsolutePathsWhenVirtualRootsAreEnabled() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            TestServices services = createVirtualServices();
            installRoutes(app, services);
            Path mediaFile = createMediaFile("movie.mkv");
            String virtualPath = services.virtualPathResolver().toVirtualPath(mediaFile);

            assertNotNull(virtualPath);

            try (Response putResponse = app.client().put("/api/v1/chapters", request -> {
                request.contentType(ContentType.Application.Json);
                request.setBody(writeJson(
                    new UpsertChapterSetRequest(
                        mediaFile.toString(),
                        "Movie",
                        List.of(new UpsertChapterMarkRequest("Intro", 0.0, "", 0))
                    )
                ));
            })) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(putResponse));
                ChapterSet stored = json.readValue(MediaApiTestSupport.bodyAsText(putResponse), ChapterSet.class);
                assertEquals(virtualPath, stored.mediaPath());
            }

            try (Response getResponse = app.client().get("/api/v1/chapters?path=" + mediaFile)) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(getResponse));
                ChapterSet fetched = json.readValue(MediaApiTestSupport.bodyAsText(getResponse), ChapterSet.class);
                assertEquals(virtualPath, fetched.mediaPath());
            }

            try (Response createResponse = app.client().post("/api/v1/chapters/marks", request -> {
                request.contentType(ContentType.Application.Json);
                request.setBody(writeJson(new CreateChapterMarkRequest(mediaFile.toString(), "Ending", 90.25, "")));
            })) {
                assertEquals(HttpStatusCode.Created, MediaApiTestSupport.status(createResponse));
            }
        });
    }

    @Test
    void chapterRoutesRejectExistingAbsolutePathsBeforeSlashPrefixedVirtualFallback() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            TestServices services = createVirtualServices("tmp");
            installRoutes(app, services);
            Files.write(tempDir.resolve("outside-absolute.mkv"), new byte[32]);
            Path outside = Files.createTempFile("outside-absolute", ".mkv");

            try {
                try (Response response = app.client().get("/api/v1/chapters?path=" + outside)) {
                    assertEquals(HttpStatusCode.Forbidden, MediaApiTestSupport.status(response));
                }
            } finally {
                Files.deleteIfExists(outside);
            }
        });
    }

    @Test
    void chapterRoutesRejectNonExistentSlashPrefixedAbsolutePathInsteadOfVirtualFallback() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            TestServices services = createVirtualServices("tmp");
            installRoutes(app, services);

            try (Response response = app.client().get("/api/v1/chapters?path=/tmp/nonexistent.mkv")) {
                assertNotEquals(HttpStatusCode.OK, MediaApiTestSupport.status(response));
            }
        });
    }

    @Test
    void putChaptersRejectsDuplicateMarkIdsWithValidationError() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            TestServices services = createServices();
            installRoutes(app, services);
            Path mediaFile = createMediaFile("movie.mkv");
            ChapterSet created = services.chapterService().createMark(mediaFile.toString(), "Intro", 0.0, "");
            String existingMarkId = created.marks().getFirst().id();

            try (Response response = app.client().put("/api/v1/chapters", request -> {
                request.contentType(ContentType.Application.Json);
                request.setBody(writeJson(
                    new UpsertChapterSetRequest(
                        mediaFile.toString(),
                        "Movie",
                        List.of(
                            new UpsertChapterMarkRequest(existingMarkId, "Intro", 0.0, "", 0),
                            new UpsertChapterMarkRequest(existingMarkId, "Ending", 90.25, "", 1)
                        )
                    )
                ));
            })) {
                assertEquals(HttpStatusCode.BadRequest, MediaApiTestSupport.status(response));
                assertTrue(MediaApiTestSupport.bodyAsText(response).contains("VALIDATION_ERROR"));
            }
        });
    }
}
