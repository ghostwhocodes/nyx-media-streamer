package com.nyx.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.common.DatabaseResources;
import com.nyx.http.ContentType;
import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import com.nyx.http.UserIdPrincipal;
import com.nyx.json.NyxJson;
import com.nyx.media.contracts.LibraryType;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryRoutesTest {
    @TempDir
    Path tempDir;

    private final List<HikariDataSource> dataSources = new ArrayList<>();
    private final ObjectMapper json = NyxJson.newMapper();

    @AfterEach
    void tearDown() {
        MediaApiTestSupport.closeDataSources(dataSources);
    }

    private LibraryService createLibraryService() {
        DatabaseResources resources = LibraryService.createDatabase(tempDir);
        dataSources.add(resources.getDataSource());
        return new LibraryService(resources.getJdbi());
    }

    private Path sourceRoot(String name) throws Exception {
        return Files.createDirectories(tempDir.resolve(name));
    }

    @Test
    void libraryRoutesSupportCrudWithOptionalAuthProtectionOnWrites() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            LibraryService libraryService = createLibraryService();
            Path moviesRoot = sourceRoot("movies");
            Path showsRoot = sourceRoot("shows");

            app.installBearerAuth(
                "api-token",
                credential -> "admin-token".equals(credential.token()) ? new UserIdPrincipal("admin") : null
            );
            app.routing(route -> LibraryRoutes.libraryRoutes(route, libraryService, null, null, List.of("api-token")));

            try (Response unauthorizedCreate = app.client().post("/api/v1/libraries", request -> {
                request.header(HttpHeaders.ContentType, ContentType.Application.Json.getValue());
                request.setBody("""
                    {
                      "name": "Movies",
                      "type": "MOVIE",
                      "sourceRoots": [{"path": "%s"}]
                    }
                    """.formatted(moviesRoot).trim());
            })) {
                assertEquals(HttpStatusCode.Unauthorized, MediaApiTestSupport.status(unauthorizedCreate));
            }

            String libraryId;
            try (Response createResponse = app.client().post("/api/v1/libraries", request -> {
                request.header(HttpHeaders.Authorization, "Bearer admin-token");
                request.header(HttpHeaders.ContentType, ContentType.Application.Json.getValue());
                request.setBody("""
                    {
                      "name": "Movies",
                      "description": "Library",
                      "type": "MOVIE",
                      "sourceRoots": [{"path": "%s", "displayName": "Movies"}]
                    }
                    """.formatted(moviesRoot).trim());
            })) {
                assertEquals(HttpStatusCode.Created, MediaApiTestSupport.status(createResponse));
                JsonNode created = json.readTree(MediaApiTestSupport.bodyAsText(createResponse));
                libraryId = created.path("libraryId").asText();
                assertEquals(LibraryType.MOVIE.name(), created.path("type").asText());
            }

            try (Response listResponse = app.client().get("/api/v1/libraries")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(listResponse));
                JsonNode listed = json.readTree(MediaApiTestSupport.bodyAsText(listResponse));
                assertEquals(1, listed.size());
                assertEquals(libraryId, listed.get(0).path("libraryId").asText());
            }

            try (Response getResponse = app.client().get("/api/v1/libraries/" + libraryId)) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(getResponse));
                JsonNode fetched = json.readTree(MediaApiTestSupport.bodyAsText(getResponse));
                assertEquals("Movies", fetched.path("name").asText());
            }

            try (Response updateResponse = app.client().put("/api/v1/libraries/" + libraryId, request -> {
                request.header(HttpHeaders.Authorization, "Bearer admin-token");
                request.header(HttpHeaders.ContentType, ContentType.Application.Json.getValue());
                request.setBody("""
                    {
                      "name": "Shows",
                      "type": "SHOW",
                      "sourceRoots": [{"path": "%s", "displayName": "Shows"}]
                    }
                    """.formatted(showsRoot).trim());
            })) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(updateResponse));
                JsonNode updated = json.readTree(MediaApiTestSupport.bodyAsText(updateResponse));
                assertEquals(LibraryType.SHOW.name(), updated.path("type").asText());
                assertEquals(showsRoot.toString(), updated.path("sourceRoots").get(0).path("path").asText());
            }

            try (Response deleteResponse = app.client().delete("/api/v1/libraries/" + libraryId, request ->
                request.header(HttpHeaders.Authorization, "Bearer admin-token"))) {
                assertEquals(HttpStatusCode.NoContent, MediaApiTestSupport.status(deleteResponse));
            }

            try (Response missingResponse = app.client().get("/api/v1/libraries/" + libraryId)) {
                assertEquals(HttpStatusCode.NotFound, MediaApiTestSupport.status(missingResponse));
                assertTrue(MediaApiTestSupport.bodyAsText(missingResponse).contains("LIBRARY_NOT_FOUND"));
            }
        });
    }
}
