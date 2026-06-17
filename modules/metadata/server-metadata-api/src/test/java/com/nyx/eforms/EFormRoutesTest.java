package com.nyx.eforms;

import static com.nyx.eforms.MetadataApiTestSupport.bodyAsText;
import static com.nyx.eforms.MetadataApiTestSupport.contentType;
import static com.nyx.eforms.MetadataApiTestSupport.readRawBytes;
import static com.nyx.eforms.MetadataApiTestSupport.status;
import static com.nyx.eforms.MetadataApiTestSupport.testApplication;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.nyx.common.DatabaseResources;
import com.nyx.common.PathSecurity;
import com.nyx.common.VirtualPathResolver;
import com.nyx.config.MediaRootConfig;
import com.nyx.eforms.MetadataApiTestSupport.ApplicationHarness;
import com.nyx.eforms.MetadataApiTestSupport.HttpTestClient;
import com.nyx.eforms.model.FieldDefinition;
import com.nyx.eforms.model.FieldType;
import com.nyx.eforms.model.FormDefinition;
import com.nyx.eforms.model.MediaMetadata;
import com.nyx.eforms.model.MediaType;
import com.nyx.http.ContentType;
import com.nyx.http.HttpStatusCode;
import com.nyx.json.NyxJson;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EFormRoutesTest {
    private static final String DEFAULT_MOVIE_FORM_BODY = """
        {"name":"Movies","mediaTypes":["VIDEO"],"fields":[{"name":"title","type":"TEXT","required":true}]}
        """;

    private final ObjectMapper mapper = NyxJson.newMapper();
    private final List<HikariDataSource> datasources = new ArrayList<>();

    private Path tempDir;
    private Path mediaDir;
    private int dbCounter;
    private final String virtualName = "media";

    @BeforeEach
    void setup() throws IOException {
        tempDir = Files.createTempDirectory("nyx-eform-routes-test");
        mediaDir = Files.createDirectories(tempDir.resolve("media"));
        dbCounter = 0;
    }

    @AfterEach
    void teardown() throws IOException {
        for (HikariDataSource dataSource : datasources) {
            dataSource.close();
        }
        datasources.clear();
        deleteTree(tempDir);
    }

    @Test
    void postCreateForm() throws Exception {
        testApplication(app -> {
            TestServices services = createServices();
            installPluginsAndRoutes(app, services);

            Response response = postJson(app.client(), "/api/v1/forms", DEFAULT_MOVIE_FORM_BODY);

            assertEquals(HttpStatusCode.Created, status(response));
            JsonNode body = json(response);
            assertEquals("Movies", required(body, "name").asText());
            assertEquals(1, required(body, "currentVersion").asInt());
        });
    }

    @Test
    void getListForms() throws Exception {
        testApplication(app -> {
            TestServices services = createServices();
            installPluginsAndRoutes(app, services);

            postJson(app.client(), "/api/v1/forms", DEFAULT_MOVIE_FORM_BODY);

            Response response = app.client().get("/api/v1/forms");
            assertEquals(HttpStatusCode.OK, status(response));
            JsonNode forms = json(response);
            assertEquals(1, forms.size());
        });
    }

    @Test
    void getFormById() throws Exception {
        testApplication(app -> {
            TestServices services = createServices();
            installPluginsAndRoutes(app, services);

            String formId = createDefaultMovieForm(app.client());

            Response response = app.client().get("/api/v1/forms/" + formId);
            assertEquals(HttpStatusCode.OK, status(response));
            JsonNode body = json(response);
            assertEquals("Movies", required(body, "name").asText());
        });
    }

    @Test
    void getFormNotFound() throws Exception {
        testApplication(app -> {
            TestServices services = createServices();
            installPluginsAndRoutes(app, services);

            Response response = app.client().get("/api/v1/forms/nonexistent");
            assertEquals(HttpStatusCode.NotFound, status(response));
        });
    }

    @Test
    void putUpdateForm() throws Exception {
        testApplication(app -> {
            TestServices services = createServices();
            installPluginsAndRoutes(app, services);

            String formId = createDefaultMovieForm(app.client());

            Response response = putJson(
                app.client(),
                "/api/v1/forms/" + formId,
                """
                {"fields":[{"name":"title","type":"TEXT","required":true},{"name":"year","type":"NUMBER"}]}
                """
            );
            assertEquals(HttpStatusCode.OK, status(response));
            JsonNode body = json(response);
            assertEquals(2, required(required(body, "form"), "currentVersion").asInt());
        });
    }

    @Test
    void deleteForm() throws Exception {
        testApplication(app -> {
            TestServices services = createServices();
            installPluginsAndRoutes(app, services);

            String formId = createDefaultMovieForm(app.client());

            Response response = app.client().delete("/api/v1/forms/" + formId + "?delete_metadata=true");
            assertEquals(HttpStatusCode.NoContent, status(response));

            Response getResponse = app.client().get("/api/v1/forms/" + formId);
            assertEquals(HttpStatusCode.NotFound, status(getResponse));
        });
    }

    @Test
    void postFormWithValidationError() throws Exception {
        testApplication(app -> {
            TestServices services = createServices();
            installPluginsAndRoutes(app, services);

            Response response = postJson(
                app.client(),
                "/api/v1/forms",
                """
                {"name":"Bad","mediaTypes":["VIDEO"],"fields":[]}
                """
            );
            assertEquals(HttpStatusCode.BadRequest, status(response));
        });
    }

    @Test
    void postAttachMetadataAndGetItBack() throws Exception {
        testApplication(app -> {
            TestServices services = createServices();
            installPluginsAndRoutes(app, services);

            String formId = createDefaultMovieForm(app.client());

            Path testFile = writeFile(mediaDir.resolve("test.mkv"), "dummy");

            Response attachResponse = postJson(
                app.client(),
                "/api/v1/metadata",
                """
                {"mediaPath":"%s","formId":"%s","values":{"title":"Test Movie"}}
                """.formatted(testFile.toRealPath(), formId)
            );
            assertEquals(HttpStatusCode.Created, status(attachResponse));
            String metadataId = id(attachResponse);

            Response getResponse = app.client().get("/api/v1/metadata/" + metadataId);
            assertEquals(HttpStatusCode.OK, status(getResponse));
            JsonNode body = json(getResponse);
            assertEquals("Test Movie", required(required(body, "values"), "title").asText());
        });
    }

    @Test
    void postAttachMetadataCanonicalizesSymlinkPath() throws Exception {
        testApplication(app -> {
            TestServices services = createServices();
            installPluginsAndRoutes(app, services);

            String formId = createDefaultMovieForm(app.client());
            Path testFile = writeFile(mediaDir.resolve("canonical-source.mkv"), "dummy");
            Path symlink = Files.createSymbolicLink(mediaDir.resolve("canonical-link.mkv"), testFile);

            Response attachResponse = postJson(
                app.client(),
                "/api/v1/metadata",
                """
                {"mediaPath":"%s","formId":"%s","values":{"title":"Canonical Movie"}}
                """.formatted(symlink.toString(), formId)
            );
            assertEquals(HttpStatusCode.Created, status(attachResponse));

            JsonNode body = json(attachResponse);
            assertEquals(testFile.toRealPath().toString(), required(body, "mediaPath").asText());
            assertEquals(1, services.eformService().getMetadata(testFile.toRealPath().toString()).size());
        });
    }

    @Test
    void getMetadataBySymlinkPathReturnsCanonicalRecord() throws Exception {
        testApplication(app -> {
            TestServices services = createServices();
            installPluginsAndRoutes(app, services);

            FormDefinition form = services.eformService().createForm(
                "Lookup Form",
                Set.of(MediaType.VIDEO),
                List.of(fieldDefinition("title", FieldType.TEXT))
            );
            Path testFile = writeFile(mediaDir.resolve("lookup-source.mkv"), "dummy");
            Path symlink = Files.createSymbolicLink(mediaDir.resolve("lookup-link.mkv"), testFile);
            services.eformService().attachMetadata(testFile.toRealPath().toString(), form.id(), Map.of("title", textNode("Lookup")));

            Response response = app.client().get("/api/v1/metadata?path=" + symlink);
            assertEquals(HttpStatusCode.OK, status(response));

            JsonNode body = json(response);
            assertEquals(1, body.size());
            assertEquals(testFile.toRealPath().toString(), required(body.get(0), "mediaPath").asText());
        });
    }

    @Test
    void putUpdateMetadata() throws Exception {
        testApplication(app -> {
            TestServices services = createServices();
            installPluginsAndRoutes(app, services);

            String formId = createDefaultMovieForm(app.client());
            Path testFile = writeFile(mediaDir.resolve("test2.mkv"), "dummy");

            Response attachResponse = postJson(
                app.client(),
                "/api/v1/metadata",
                """
                {"mediaPath":"%s","formId":"%s","values":{"title":"Original"}}
                """.formatted(testFile.toRealPath(), formId)
            );
            String metadataId = id(attachResponse);

            Response updateResponse = putJson(
                app.client(),
                "/api/v1/metadata/" + metadataId,
                """
                {"values":{"title":"Updated"}}
                """
            );
            assertEquals(HttpStatusCode.OK, status(updateResponse));
            JsonNode body = json(updateResponse);
            assertEquals("Updated", required(required(body, "values"), "title").asText());
        });
    }

    @Test
    void postMetadataWithPathNotAllowedReturnsForbidden() throws Exception {
        testApplication(app -> {
            TestServices services = createServices();
            installPluginsAndRoutes(app, services);

            String formId = createDefaultMovieForm(app.client());
            Path outsideFile = writeFile(tempDir.resolve("outside.mkv"), "dummy");

            Response response = postJson(
                app.client(),
                "/api/v1/metadata",
                """
                {"mediaPath":"%s","formId":"%s","values":{"title":"Test"}}
                """.formatted(outsideFile.toRealPath(), formId)
            );
            assertEquals(HttpStatusCode.Forbidden, status(response));
        });
    }

    @Test
    void getMetadataNotFound() throws Exception {
        testApplication(app -> {
            TestServices services = createServices();
            installPluginsAndRoutes(app, services);

            Response response = app.client().get("/api/v1/metadata/nonexistent");
            assertEquals(HttpStatusCode.NotFound, status(response));
        });
    }

    @Test
    void getSearchWithQueryParams() throws Exception {
        testApplication(app -> {
            TestServices services = createServices();
            installPluginsAndRoutes(app, services);

            String formId = createDefaultMovieForm(app.client());
            Path testFile = writeFile(mediaDir.resolve("search_test.mkv"), "dummy");

            postJson(
                app.client(),
                "/api/v1/metadata",
                """
                {"mediaPath":"%s","formId":"%s","values":{"title":"The Matrix"}}
                """.formatted(testFile.toRealPath(), formId)
            );

            Response response = app.client().get("/api/v1/search/meta?q=Matrix&limit=10");
            assertEquals(HttpStatusCode.OK, status(response));
            JsonNode body = json(response);
            JsonNode results = required(body, "results");
            assertTrue(results.isArray() && !results.isEmpty());
        });
    }

    @Test
    void getExportReturnsZip() throws Exception {
        testApplication(app -> {
            TestServices services = createServices();
            installPluginsAndRoutes(app, services);

            Response response = app.client().get("/api/v1/forms/export");
            assertEquals(HttpStatusCode.OK, status(response));
            assertEquals(ContentType.Application.Zip, contentType(response));
        });
    }

    @Test
    void postImportRoundTrip() throws Exception {
        testApplication(app -> {
            TestServices services = createServices();
            installPluginsAndRoutes(app, services);

            postJson(app.client(), "/api/v1/forms", DEFAULT_MOVIE_FORM_BODY);

            Response exportResponse = app.client().get("/api/v1/forms/export");
            byte[] zipBytes = readRawBytes(exportResponse);

            Response importResponse = app.client().post("/api/v1/forms/import", request -> {
                request.contentType(ContentType.Application.OctetStream);
                request.setBody(zipBytes);
            });
            assertEquals(HttpStatusCode.OK, status(importResponse));
            JsonNode body = json(importResponse);
            assertEquals(0, required(body, "formsCreated").asInt());
            assertEquals(1, required(body, "formsSkipped").asInt());
        });
    }

    @Test
    void postRelocateEndpoint() throws Exception {
        testApplication(app -> {
            TestServices services = createServices();
            installPluginsAndRoutes(app, services);

            String formId = createDefaultMovieForm(app.client());
            Path sourceFile = writeFile(mediaDir.resolve("old.mkv"), "dummy");
            Path destinationFile = writeFile(mediaDir.resolve("new.mkv"), "dummy");

            postJson(
                app.client(),
                "/api/v1/metadata",
                """
                {"mediaPath":"%s","formId":"%s","values":{"title":"Test"}}
                """.formatted(sourceFile.toRealPath(), formId)
            );

            Response response = postJson(
                app.client(),
                "/api/v1/metadata/relocate",
                """
                {"from":"%s","to":"%s"}
                """.formatted(sourceFile.toRealPath(), destinationFile.toRealPath())
            );
            assertEquals(HttpStatusCode.OK, status(response));
            JsonNode body = json(response);
            assertEquals(1, required(body, "updated").asInt());
        });
    }

    @Test
    void postHashEndpoint() throws Exception {
        testApplication(app -> {
            TestServices services = createServices();
            installPluginsAndRoutes(app, services);

            String formId = createDefaultMovieForm(app.client());
            Path testFile = writeFile(mediaDir.resolve("hash_test.mkv"), "some content for hashing");

            postJson(
                app.client(),
                "/api/v1/metadata",
                """
                {"mediaPath":"%s","formId":"%s","values":{"title":"Hash Test"}}
                """.formatted(testFile.toRealPath(), formId)
            );

            Response response = postJson(
                app.client(),
                "/api/v1/metadata/hash",
                """
                {"path":"%s"}
                """.formatted(testFile.toRealPath())
            );
            assertEquals(HttpStatusCode.OK, status(response));
            JsonNode body = json(response);
            assertEquals(64, required(body, "hash").asText().length());
            assertEquals(1, required(body, "updatedCount").asInt());
        });
    }

    @Test
    void postHashWithSymlinkPathUpdatesCanonicalMetadataRecord() throws Exception {
        testApplication(app -> {
            TestServices services = createServices();
            installPluginsAndRoutes(app, services);

            String formId = createDefaultMovieForm(app.client());
            Path testFile = writeFile(mediaDir.resolve("hash-symlink-target.mkv"), "hash me through symlink");
            Path symlink = Files.createSymbolicLink(mediaDir.resolve("hash-symlink-link.mkv"), testFile);

            postJson(
                app.client(),
                "/api/v1/metadata",
                """
                {"mediaPath":"%s","formId":"%s","values":{"title":"Hash Canonical"}}
                """.formatted(testFile.toRealPath(), formId)
            );

            Response response = postJson(
                app.client(),
                "/api/v1/metadata/hash",
                """
                {"path":"%s"}
                """.formatted(symlink.toString())
            );
            assertEquals(HttpStatusCode.OK, status(response));

            JsonNode body = json(response);
            assertEquals(1, required(body, "updatedCount").asInt());
            MediaMetadata metadata = services.eformService().getMetadata(testFile.toRealPath().toString()).getFirst();
            assertEquals(required(body, "hash").asText(), metadata.contentHash());
        });
    }

    @Test
    void postHashWithPathNotAllowedReturnsForbidden() throws Exception {
        testApplication(app -> {
            TestServices services = createServices();
            installPluginsAndRoutes(app, services);

            Path outsideFile = writeFile(tempDir.resolve("outside_hash.mkv"), "dummy");

            Response response = postJson(
                app.client(),
                "/api/v1/metadata/hash",
                """
                {"path":"%s"}
                """.formatted(outsideFile.toRealPath())
            );
            assertEquals(HttpStatusCode.Forbidden, status(response));
        });
    }

    @Test
    void postBatchRelocateWithDryRunReturnsPreview() throws Exception {
        testApplication(app -> {
            TestEnv env = createEnv("batch-reloc1.db");
            installEnvPlugins(app, env);

            Path subDir = Files.createDirectories(mediaDir.resolve("photos"));
            Path newDir = Files.createDirectories(mediaDir.resolve("newphotos"));
            FormDefinition form = env.eformService().createForm(
                "Test Form",
                Set.of(MediaType.IMAGE),
                List.of(fieldDefinition("title", FieldType.TEXT))
            );
            Path testFile = Files.createFile(subDir.resolve("photo.jpg"));
            env.eformService().attachMetadata(testFile.toString(), form.id(), Map.of());

            Response response = postJson(
                app.client(),
                "/api/v1/metadata/relocate/batch?dry_run=true",
                """
                {"fromPattern":"%s","toPattern":"%s"}
                """.formatted(subDir, newDir)
            );
            assertEquals(HttpStatusCode.OK, status(response));
        });
    }

    @Test
    void postBatchRelocateWithoutDryRunPerformsRelocation() throws Exception {
        testApplication(app -> {
            TestEnv env = createEnv("batch-reloc2.db");
            installEnvPlugins(app, env);

            FormDefinition form = env.eformService().createForm(
                "Test Form",
                Set.of(MediaType.IMAGE),
                List.of(fieldDefinition("title", FieldType.TEXT))
            );
            Path subDir = Files.createDirectories(mediaDir.resolve("old"));
            Path newDir = Files.createDirectories(mediaDir.resolve("new"));
            Path testFile = Files.createFile(subDir.resolve("photo.jpg"));
            env.eformService().attachMetadata(testFile.toString(), form.id(), Map.of());

            Response response = postJson(
                app.client(),
                "/api/v1/metadata/relocate/batch",
                """
                {"fromPattern":"%s/","toPattern":"%s/"}
                """.formatted(subDir, newDir)
            );
            assertEquals(HttpStatusCode.OK, status(response));
        });
    }

    @Test
    void postBatchRelocateCanonicalizesSymlinkPatterns() throws Exception {
        testApplication(app -> {
            TestEnv env = createEnv("batch-reloc-symlink.db");
            installEnvPlugins(app, env);

            FormDefinition form = env.eformService().createForm(
                "Batch Canonical Form",
                Set.of(MediaType.IMAGE),
                List.of(fieldDefinition("title", FieldType.TEXT))
            );
            Path oldDir = Files.createDirectories(mediaDir.resolve("batch-real-old"));
            Path newDir = Files.createDirectories(mediaDir.resolve("batch-real-new"));
            Path oldAlias = Files.createSymbolicLink(mediaDir.resolve("batch-alias-old"), oldDir);
            Path newAlias = Files.createSymbolicLink(mediaDir.resolve("batch-alias-new"), newDir);
            Path testFile = Files.createFile(oldDir.resolve("photo.jpg"));
            env.eformService().attachMetadata(testFile.toRealPath().toString(), form.id(), Map.of());

            Response response = postJson(
                app.client(),
                "/api/v1/metadata/relocate/batch",
                """
                {"fromPattern":"%s/","toPattern":"%s/"}
                """.formatted(oldAlias, newAlias)
            );
            assertEquals(HttpStatusCode.OK, status(response));
            assertEquals(1, env.eformService().getMetadata(newDir.resolve("photo.jpg").toString()).size());
            assertTrue(env.eformService().getMetadata(testFile.toRealPath().toString()).isEmpty());
        });
    }

    @Test
    void postBatchRelocateWithInvalidPathReturns403() throws Exception {
        testApplication(app -> {
            TestEnv env = createEnv("batch-reloc3.db");
            installEnvPlugins(app, env);

            Response response = postJson(
                app.client(),
                "/api/v1/metadata/relocate/batch",
                """
                {"fromPattern":"/etc/","toPattern":"/tmp/"}
                """
            );
            assertTrue(List.of(403, 404).contains(status(response).getValue()));
        });
    }

    @Test
    void getSearchWithAllParameters() throws Exception {
        testApplication(app -> {
            TestEnv env = createEnv("search1.db");
            installEnvPlugins(app, env);

            FormDefinition form = env.eformService().createForm(
                "Search Form",
                Set.of(MediaType.IMAGE),
                List.of(fieldDefinition("title", FieldType.TEXT))
            );

            Response response = app.client().get(
                "/api/v1/search/meta?q=test&form=" + form.id() + "&type=IMAGE&sort=date&limit=10&offset=0"
            );
            assertEquals(HttpStatusCode.OK, status(response));
        });
    }

    @Test
    void getSearchWithMinimalParameters() throws Exception {
        testApplication(app -> {
            TestEnv env = createEnv("search2.db");
            installEnvPlugins(app, env);

            Response response = app.client().get("/api/v1/search/meta");
            assertEquals(HttpStatusCode.OK, status(response));
        });
    }

    @Test
    void getSearchWithTextQuery() throws Exception {
        testApplication(app -> {
            TestEnv env = createEnv("search3.db");
            installEnvPlugins(app, env);

            Response response = app.client().get("/api/v1/search/meta?q=hello+world");
            assertEquals(HttpStatusCode.OK, status(response));
        });
    }

    @Test
    void postImportWithInvalidDataReturnsResultWithZeroCounts() throws Exception {
        testApplication(app -> {
            TestEnv env = createEnv("import1.db");
            installEnvPlugins(app, env);

            Response response = app.client().post("/api/v1/forms/import", request -> {
                request.setBody("not a zip file");
                request.contentType(ContentType.Text.Plain);
            });
            assertEquals(HttpStatusCode.OK, status(response));
            assertTrue(bodyAsText(response).contains("\"formsCreated\":0"));
        });
    }

    @Test
    void postHashComputesHashForValidFile() throws Exception {
        testApplication(app -> {
            TestEnv env = createEnv("hash1.db");
            installEnvPlugins(app, env);

            Path testFile = writeFile(mediaDir.resolve("hashfile.jpg"), "file content for hashing");

            Response response = postJson(
                app.client(),
                "/api/v1/metadata/hash",
                """
                {"path":"%s"}
                """.formatted(testFile)
            );
            assertEquals(HttpStatusCode.OK, status(response));
            assertTrue(bodyAsText(response).contains("hash"));
        });
    }

    @Test
    void postHashWithInvalidPathReturnsError() throws Exception {
        testApplication(app -> {
            TestEnv env = createEnv("hash2.db");
            installEnvPlugins(app, env);

            Response response = postJson(
                app.client(),
                "/api/v1/metadata/hash",
                """
                {"path":"/etc/passwd"}
                """
            );
            assertTrue(List.of(403, 404).contains(status(response).getValue()));
        });
    }

    @Test
    void deleteFormWithDeleteMetadataTrueRemovesMetadata() throws Exception {
        testApplication(app -> {
            TestEnv env = createEnv("delete1.db");
            installEnvPlugins(app, env);

            FormDefinition form = env.eformService().createForm(
                "Delete Form",
                Set.of(MediaType.IMAGE),
                List.of(fieldDefinition("title", FieldType.TEXT))
            );
            Path testFile = Files.createFile(mediaDir.resolve("del.jpg"));
            env.eformService().attachMetadata(testFile.toString(), form.id(), Map.of());

            Response response = app.client().delete("/api/v1/forms/" + form.id() + "?delete_metadata=true");
            assertEquals(HttpStatusCode.NoContent, status(response));
        });
    }

    @Test
    void putMetadataUpdatesValues() throws Exception {
        testApplication(app -> {
            TestEnv env = createEnv("meta-update1.db");
            installEnvPlugins(app, env);

            FormDefinition form = env.eformService().createForm(
                "Meta Form",
                Set.of(MediaType.IMAGE),
                List.of(fieldDefinition("title", FieldType.TEXT))
            );
            Path testFile = Files.createFile(mediaDir.resolve("meta.jpg"));
            MediaMetadata metadata = env.eformService().attachMetadata(testFile.toString(), form.id(), Map.of());

            Response response = putJson(
                app.client(),
                "/api/v1/metadata/" + metadata.id(),
                """
                {"values":{}}
                """
            );
            assertEquals(HttpStatusCode.OK, status(response));
        });
    }

    @Test
    void putMetadataForNonexistentIdReturns404() throws Exception {
        testApplication(app -> {
            TestEnv env = createEnv("meta-update2.db");
            installEnvPlugins(app, env);

            Response response = putJson(
                app.client(),
                "/api/v1/metadata/nonexistent",
                """
                {"values":{}}
                """
            );
            assertEquals(HttpStatusCode.NotFound, status(response));
        });
    }

    @Test
    void postRelocateMovesMetadataPath() throws Exception {
        testApplication(app -> {
            TestEnv env = createEnv("reloc1.db");
            installEnvPlugins(app, env);

            FormDefinition form = env.eformService().createForm(
                "Reloc Form",
                Set.of(MediaType.IMAGE),
                List.of(fieldDefinition("title", FieldType.TEXT))
            );
            Path oldFile = Files.createFile(mediaDir.resolve("old.jpg"));
            Path newFile = Files.createFile(mediaDir.resolve("new.jpg"));
            env.eformService().attachMetadata(oldFile.toString(), form.id(), Map.of());

            Response response = postJson(
                app.client(),
                "/api/v1/metadata/relocate",
                """
                {"from":"%s","to":"%s"}
                """.formatted(oldFile, newFile)
            );
            assertEquals(HttpStatusCode.OK, status(response));
        });
    }

    @Test
    void postMetadataAttachWithVirtualPathResolverResolvesVirtualPathToAbsolute() throws Exception {
        testApplication(app -> {
            TestCtx ctx = createCtx();
            installWithVirtualResolver(app, ctx);

            FormDefinition form = ctx.eformService().createForm(
                "Photos",
                Set.of(MediaType.IMAGE),
                List.of(fieldDefinition("caption", FieldType.TEXT))
            );
            Files.createFile(mediaDir.resolve("photo1.jpg"));

            String virtualPath = virtualName + "/photo1.jpg";
            Response response = postJson(
                app.client(),
                "/api/v1/metadata",
                """
                {"mediaPath":"%s","formId":"%s","values":{"caption":"Nice photo"}}
                """.formatted(virtualPath, form.id())
            );
            assertEquals(HttpStatusCode.Created, status(response));

            String metadataId = id(response);
            MediaMetadata metadata = ctx.eformService().getMetadataById(metadataId);
            assertNotNull(metadata);
            assertTrue(metadata.mediaPath().startsWith("/"), "Stored path should be absolute, got: " + metadata.mediaPath());
            assertTrue(metadata.mediaPath().contains("photo1.jpg"));
        });
    }

    @Test
    void getMetadataByIdWithVirtualPathResolverConvertsAbsolutePathToVirtual() throws Exception {
        testApplication(app -> {
            TestCtx ctx = createCtx();
            installWithVirtualResolver(app, ctx);

            FormDefinition form = ctx.eformService().createForm(
                "Photos",
                Set.of(MediaType.IMAGE),
                List.of(fieldDefinition("caption", FieldType.TEXT))
            );
            Path testFile = Files.createFile(mediaDir.resolve("photo2.jpg"));

            MediaMetadata metadata = ctx.eformService().attachMetadata(
                testFile.toRealPath().toString(),
                form.id(),
                Map.of("caption", textNode("A photo"))
            );

            Response response = app.client().get("/api/v1/metadata/" + metadata.id());
            assertEquals(HttpStatusCode.OK, status(response));

            JsonNode body = json(response);
            String returnedPath = required(body, "mediaPath").asText();
            assertTrue(
                returnedPath.startsWith(virtualName),
                "Expected virtual path starting with '" + virtualName + "', got: " + returnedPath
            );
            assertTrue(returnedPath.contains("photo2.jpg"));
        });
    }

    @Test
    void getSearchMetaWithVirtualPathResolverConvertsPathsInResults() throws Exception {
        testApplication(app -> {
            TestCtx ctx = createCtx();
            installWithVirtualResolver(app, ctx);

            FormDefinition form = ctx.eformService().createForm(
                "Videos",
                Set.of(MediaType.VIDEO),
                List.of(fieldDefinition("title", FieldType.TEXT, true))
            );
            Path testFile = Files.createFile(mediaDir.resolve("clip.mp4"));

            ctx.eformService().attachMetadata(
                testFile.toRealPath().toString(),
                form.id(),
                Map.of("title", textNode("My Clip"))
            );

            Response response = app.client().get("/api/v1/search/meta?q=Clip");
            assertEquals(HttpStatusCode.OK, status(response));

            JsonNode body = json(response);
            JsonNode results = required(body, "results");
            assertTrue(results.isArray() && !results.isEmpty(), "Search should return at least one result");

            String firstPath = required(results.get(0), "mediaPath").asText();
            assertTrue(
                firstPath.startsWith(virtualName),
                "Expected virtual path starting with '" + virtualName + "', got: " + firstPath
            );
        });
    }

    @Test
    void getSearchMetaWithTypeImageFilter() throws Exception {
        testApplication(app -> {
            TestCtx ctx = createCtx();
            installWithVirtualResolver(app, ctx);

            FormDefinition form = ctx.eformService().createForm(
                "Images",
                Set.of(MediaType.IMAGE),
                List.of(fieldDefinition("desc", FieldType.TEXT))
            );
            Path testFile = Files.createFile(mediaDir.resolve("typed.png"));
            ctx.eformService().attachMetadata(
                testFile.toRealPath().toString(),
                form.id(),
                Map.of("desc", textNode("typed image"))
            );

            Response response = app.client().get("/api/v1/search/meta?type=IMAGE");
            assertEquals(HttpStatusCode.OK, status(response));

            JsonNode body = json(response);
            assertNotNull(body.get("results"));
        });
    }

    @Test
    void getSearchMetaWithTypeVideoFilter() throws Exception {
        testApplication(app -> {
            TestCtx ctx = createCtx();
            installWithVirtualResolver(app, ctx);

            Response response = app.client().get("/api/v1/search/meta?type=VIDEO&limit=5&offset=0");
            assertEquals(HttpStatusCode.OK, status(response));
        });
    }

    @Test
    void getSearchMetaWithSortByParameter() throws Exception {
        testApplication(app -> {
            TestCtx ctx = createCtx();
            installWithVirtualResolver(app, ctx);

            FormDefinition form = ctx.eformService().createForm(
                "Sorted",
                Set.of(MediaType.IMAGE),
                List.of(fieldDefinition("name", FieldType.TEXT))
            );

            Path first = Files.createFile(mediaDir.resolve("sort1.jpg"));
            Path second = Files.createFile(mediaDir.resolve("sort2.jpg"));
            ctx.eformService().attachMetadata(first.toRealPath().toString(), form.id(), Map.of("name", textNode("Alpha")));
            ctx.eformService().attachMetadata(second.toRealPath().toString(), form.id(), Map.of("name", textNode("Beta")));

            Response response = app.client().get("/api/v1/search/meta?sort=date&form=" + form.id());
            assertEquals(HttpStatusCode.OK, status(response));

            JsonNode body = json(response);
            JsonNode results = required(body, "results");
            assertEquals(2, results.size());
        });
    }

    @Test
    void postRelocateWithVirtualPathResolverResolvesVirtualPaths() throws Exception {
        testApplication(app -> {
            TestCtx ctx = createCtx();
            installWithVirtualResolver(app, ctx);

            FormDefinition form = ctx.eformService().createForm(
                "Relocatable",
                Set.of(MediaType.IMAGE),
                List.of(fieldDefinition("title", FieldType.TEXT))
            );

            Path oldFile = Files.createFile(mediaDir.resolve("old_vp.jpg"));
            Files.createFile(mediaDir.resolve("new_vp.jpg"));
            ctx.eformService().attachMetadata(oldFile.toRealPath().toString(), form.id(), Map.of());

            Response response = postJson(
                app.client(),
                "/api/v1/metadata/relocate",
                """
                {"from":"%s/old_vp.jpg","to":"%s/new_vp.jpg"}
                """.formatted(virtualName, virtualName)
            );
            assertEquals(HttpStatusCode.OK, status(response));
            JsonNode body = json(response);
            assertEquals(1, required(body, "updated").asInt());
        });
    }

    @Test
    void postBatchRelocateWithVirtualPathResolverResolvesVirtualBasePaths() throws Exception {
        testApplication(app -> {
            TestCtx ctx = createCtx();
            installWithVirtualResolver(app, ctx);

            FormDefinition form = ctx.eformService().createForm(
                "BatchReloc",
                Set.of(MediaType.IMAGE),
                List.of(fieldDefinition("title", FieldType.TEXT))
            );

            Path subDir = Files.createDirectories(mediaDir.resolve("batch_old"));
            Files.createDirectories(mediaDir.resolve("batch_new"));
            Path testFile = Files.createFile(subDir.resolve("item.jpg"));
            ctx.eformService().attachMetadata(testFile.toRealPath().toString(), form.id(), Map.of());

            Response response = postJson(
                app.client(),
                "/api/v1/metadata/relocate/batch",
                """
                {"fromPattern":"%s/batch_old","toPattern":"%s/batch_new"}
                """.formatted(virtualName, virtualName)
            );
            assertEquals(HttpStatusCode.OK, status(response));
        });
    }

    @Test
    void postHashWithVirtualPathResolverResolvesVirtualPath() throws Exception {
        testApplication(app -> {
            TestCtx ctx = createCtx();
            installWithVirtualResolver(app, ctx);

            FormDefinition form = ctx.eformService().createForm(
                "HashForm",
                Set.of(MediaType.IMAGE),
                List.of(fieldDefinition("title", FieldType.TEXT))
            );

            Path testFile = writeFile(mediaDir.resolve("hash_vp.jpg"), "hash content data");
            ctx.eformService().attachMetadata(testFile.toRealPath().toString(), form.id(), Map.of());

            Response response = postJson(
                app.client(),
                "/api/v1/metadata/hash",
                """
                {"path":"%s/hash_vp.jpg"}
                """.formatted(virtualName)
            );
            assertEquals(HttpStatusCode.OK, status(response));
            JsonNode body = json(response);
            String hash = required(body, "hash").asText();
            assertNotNull(hash);
            assertEquals(64, hash.length(), "SHA-256 hash should be 64 hex characters");
            assertEquals(1, required(body, "updatedCount").asInt());
        });
    }

    @Test
    void getSearchMetaWithVirtualPathResolverAndNoResultsStillConverts() throws Exception {
        testApplication(app -> {
            TestCtx ctx = createCtx();
            installWithVirtualResolver(app, ctx);

            Response response = app.client().get("/api/v1/search/meta?q=nonexistent_term_xyz");
            assertEquals(HttpStatusCode.OK, status(response));

            JsonNode body = json(response);
            JsonNode results = required(body, "results");
            assertEquals(0, results.size());
        });
    }

    @Test
    void getMetadataByIdWherePathIsNotUnderAnyVirtualRootReturnsUnmodifiedPath() throws Exception {
        testApplication(app -> {
            TestCtx ctx = createCtx();

            Path otherDir = Files.createDirectories(tempDir.resolve("other"));
            Path otherFile = Files.createFile(otherDir.resolve("orphan.jpg"));

            dbCounter++;
            Path dbDir = Files.createDirectories(tempDir.resolve("db" + dbCounter));
            DatabaseResources resources = EFormsDatabase.createDatabase(dbDir);
            datasources.add(resources.getDataSource());
            EFormService eformService = new EFormService(resources.getJdbi());
            PathSecurity broadPathSecurity = new PathSecurity(List.of(mediaDir, otherDir));
            VirtualPathResolver resolver = new VirtualPathResolver(List.of(new MediaRootConfig(mediaDir)));

            EFormRoutes.eformRoutes(
                app.route(),
                eformService,
                new ExportImportService(eformService),
                new RelocationService(resources.getJdbi()),
                broadPathSecurity,
                List.of(),
                resolver
            );

            FormDefinition form = eformService.createForm(
                "Other",
                Set.of(MediaType.IMAGE),
                List.of(fieldDefinition("x", FieldType.TEXT))
            );

            MediaMetadata metadata = eformService.attachMetadata(otherFile.toRealPath().toString(), form.id(), Map.of());

            Response response = app.client().get("/api/v1/metadata/" + metadata.id());
            assertEquals(HttpStatusCode.OK, status(response));

            JsonNode body = json(response);
            String returnedPath = required(body, "mediaPath").asText();
            assertTrue(returnedPath.startsWith("/"), "Path should remain absolute when not in virtual root");
        });
    }

    @Test
    void getFormByFormIdViaServiceCoversRouteHandler() throws Exception {
        testApplication(app -> {
            TestServices services = createServices();
            installPluginsAndRoutes(app, services);

            FormDefinition form = services.eformService().createForm(
                "CoverageForm",
                Set.of(MediaType.IMAGE),
                List.of(fieldDefinition("title", FieldType.TEXT, true))
            );

            Response response = app.client().get("/api/v1/forms/" + form.id());
            assertEquals(HttpStatusCode.OK, status(response));
            JsonNode body = json(response);
            assertEquals("CoverageForm", required(body, "name").asText());
        });
    }

    @Test
    void putFormByFormIdViaServiceCoversRouteHandler() throws Exception {
        testApplication(app -> {
            TestServices services = createServices();
            installPluginsAndRoutes(app, services);

            FormDefinition form = services.eformService().createForm(
                "UpdateForm",
                Set.of(MediaType.VIDEO),
                List.of(fieldDefinition("title", FieldType.TEXT, true))
            );

            Response response = putJson(
                app.client(),
                "/api/v1/forms/" + form.id(),
                """
                {"fields":[{"name":"title","type":"TEXT","required":true},{"name":"year","type":"NUMBER"}]}
                """
            );
            assertEquals(HttpStatusCode.OK, status(response));
            JsonNode body = json(response);
            assertEquals(2, required(required(body, "form"), "currentVersion").asInt());
        });
    }

    @Test
    void deleteFormByFormIdViaServiceCoversRouteHandler() throws Exception {
        testApplication(app -> {
            TestServices services = createServices();
            installPluginsAndRoutes(app, services);

            FormDefinition form = services.eformService().createForm(
                "DeleteForm",
                Set.of(MediaType.AUDIO),
                List.of(fieldDefinition("tag", FieldType.TEXT))
            );

            Response response = app.client().delete("/api/v1/forms/" + form.id());
            assertEquals(HttpStatusCode.NoContent, status(response));

            Response getResponse = app.client().get("/api/v1/forms/" + form.id());
            assertEquals(HttpStatusCode.NotFound, status(getResponse));
        });
    }

    @Test
    void getMetadataByMetadataIdViaServiceCoversRouteHandler() throws Exception {
        testApplication(app -> {
            TestServices services = createServices();
            installPluginsAndRoutes(app, services);

            FormDefinition form = services.eformService().createForm(
                "MetaForm",
                Set.of(MediaType.IMAGE),
                List.of(fieldDefinition("title", FieldType.TEXT))
            );
            Path testFile = Files.createFile(mediaDir.resolve("meta-coverage.jpg"));
            MediaMetadata metadata = services.eformService().attachMetadata(testFile.toString(), form.id(), Map.of());

            Response response = app.client().get("/api/v1/metadata/" + metadata.id());
            assertEquals(HttpStatusCode.OK, status(response));
        });
    }

    @Test
    void putMetadataByMetadataIdViaServiceCoversRouteHandler() throws Exception {
        testApplication(app -> {
            TestServices services = createServices();
            installPluginsAndRoutes(app, services);

            FormDefinition form = services.eformService().createForm(
                "MetaUpdateForm",
                Set.of(MediaType.IMAGE),
                List.of(fieldDefinition("title", FieldType.TEXT))
            );
            Path testFile = Files.createFile(mediaDir.resolve("meta-update-coverage.jpg"));
            MediaMetadata metadata = services.eformService().attachMetadata(testFile.toString(), form.id(), Map.of());

            Response response = putJson(
                app.client(),
                "/api/v1/metadata/" + metadata.id(),
                """
                {"values":{"title":"Updated Title"}}
                """
            );
            assertEquals(HttpStatusCode.OK, status(response));
            JsonNode body = json(response);
            assertEquals("Updated Title", required(required(body, "values"), "title").asText());
        });
    }

    @Test
    void deleteMetadataByMetadataIdReturns204() throws Exception {
        testApplication(app -> {
            TestEnv env = createEnv("meta-delete1.db");
            installEnvPlugins(app, env);

            FormDefinition form = env.eformService().createForm(
                "DeleteMetaForm",
                Set.of(MediaType.IMAGE),
                List.of(fieldDefinition("title", FieldType.TEXT))
            );
            Path testFile = Files.createFile(mediaDir.resolve("del-meta.jpg"));
            MediaMetadata metadata = env.eformService().attachMetadata(testFile.toString(), form.id(), Map.of());

            Response response = app.client().delete("/api/v1/metadata/" + metadata.id());
            assertEquals(HttpStatusCode.NoContent, status(response));

            Response getResponse = app.client().get("/api/v1/metadata/" + metadata.id());
            assertEquals(HttpStatusCode.NotFound, status(getResponse));
        });
    }

    @Test
    void deleteMetadataWithNonexistentIdReturns404() throws Exception {
        testApplication(app -> {
            TestEnv env = createEnv("meta-delete2.db");
            installEnvPlugins(app, env);

            Response response = app.client().delete("/api/v1/metadata/nonexistent-id");
            assertEquals(HttpStatusCode.NotFound, status(response));
        });
    }

    @Test
    void postImportWithFormsFilterCoversFilterBranch() throws Exception {
        testApplication(app -> {
            TestServices services = createServices();
            installPluginsAndRoutes(app, services);

            services.eformService().createForm(
                "ImportFilterForm",
                Set.of(MediaType.VIDEO),
                List.of(fieldDefinition("title", FieldType.TEXT, true))
            );
            Response exportResponse = app.client().get("/api/v1/forms/export");
            byte[] zipBytes = readRawBytes(exportResponse);

            Response response = app.client().post("/api/v1/forms/import?forms=ImportFilterForm,AnotherForm", request -> {
                request.contentType(ContentType.Application.OctetStream);
                request.setBody(zipBytes);
            });
            assertEquals(HttpStatusCode.OK, status(response));
        });
    }

    @Test
    void getMetadataViaVirtualPathResolverFromRouteGaps() throws Exception {
        testApplication(app -> {
            TestCtx ctx = createCtx();
            installWithVirtualResolver(app, ctx);

            FormDefinition form = ctx.eformService().createForm(
                "VirtualMetaForm",
                Set.of(MediaType.IMAGE),
                List.of(fieldDefinition("title", FieldType.TEXT))
            );
            Path testFile = Files.createFile(mediaDir.resolve("virt-meta.jpg"));
            MediaMetadata metadata = ctx.eformService().attachMetadata(testFile.toString(), form.id(), Map.of());

            Response response = app.client().get("/api/v1/metadata/" + metadata.id());
            assertEquals(HttpStatusCode.OK, status(response));
        });
    }

    @Test
    void postAttachMetadataViaVirtualPathResolverFromRouteGaps() throws Exception {
        testApplication(app -> {
            TestCtx ctx = createCtx();
            installWithVirtualResolver(app, ctx);

            FormDefinition form = ctx.eformService().createForm(
                "VirtualAttachForm",
                Set.of(MediaType.IMAGE),
                List.of(fieldDefinition("title", FieldType.TEXT))
            );
            Path testFile = Files.createFile(mediaDir.resolve("virt-attach.jpg"));
            String virtualPath = ctx.virtualPathResolver().toVirtualPath(testFile);
            assertNotNull(virtualPath);

            Response response = postJson(
                app.client(),
                "/api/v1/metadata",
                """
                {"mediaPath":"%s","formId":"%s","values":{}}
                """.formatted(virtualPath, form.id())
            );
            assertEquals(HttpStatusCode.Created, status(response));
        });
    }

    private TestServices createServices() throws IOException {
        dbCounter++;
        Path dbDir = Files.createDirectories(tempDir.resolve("db" + dbCounter));
        DatabaseResources resources = EFormsDatabase.createDatabase(dbDir);
        datasources.add(resources.getDataSource());
        EFormService eformService = new EFormService(resources.getJdbi());
        return new TestServices(
            eformService,
            new ExportImportService(eformService),
            new RelocationService(resources.getJdbi()),
            new PathSecurity(List.of(mediaDir))
        );
    }

    private TestEnv createEnv(String dbName) throws IOException {
        Path dbDir = Files.createDirectories(tempDir.resolve(dbName));
        DatabaseResources resources = EFormsDatabase.createDatabase(dbDir);
        datasources.add(resources.getDataSource());
        EFormService eformService = new EFormService(resources.getJdbi());
        return new TestEnv(
            eformService,
            new ExportImportService(eformService),
            new RelocationService(resources.getJdbi()),
            new PathSecurity(List.of(mediaDir))
        );
    }

    private TestCtx createCtx() throws IOException {
        dbCounter++;
        Path dbDir = Files.createDirectories(tempDir.resolve("db" + dbCounter));
        DatabaseResources resources = EFormsDatabase.createDatabase(dbDir);
        datasources.add(resources.getDataSource());
        EFormService eformService = new EFormService(resources.getJdbi());
        return new TestCtx(
            eformService,
            new ExportImportService(eformService),
            new RelocationService(resources.getJdbi()),
            new PathSecurity(List.of(mediaDir)),
            new VirtualPathResolver(List.of(new MediaRootConfig(mediaDir)))
        );
    }

    private void installPluginsAndRoutes(ApplicationHarness app, TestServices services) {
        EFormRoutes.eformRoutes(
            app.route(),
            services.eformService(),
            services.exportImportService(),
            services.relocationService(),
            services.pathSecurity(),
            List.of(),
            null
        );
    }

    private void installEnvPlugins(ApplicationHarness app, TestEnv env) {
        EFormRoutes.eformRoutes(
            app.route(),
            env.eformService(),
            env.exportImportService(),
            env.relocationService(),
            env.pathSecurity(),
            List.of(),
            null
        );
    }

    private void installWithVirtualResolver(ApplicationHarness app, TestCtx ctx) {
        EFormRoutes.eformRoutes(
            app.route(),
            ctx.eformService(),
            ctx.exportImportService(),
            ctx.relocationService(),
            ctx.pathSecurity(),
            List.of(),
            ctx.virtualPathResolver()
        );
    }

    private Response postJson(HttpTestClient client, String path, String body) throws IOException {
        return client.post(path, request -> {
            request.contentType(ContentType.Application.Json);
            request.setBody(body);
        });
    }

    private Response putJson(HttpTestClient client, String path, String body) throws IOException {
        return client.put(path, request -> {
            request.contentType(ContentType.Application.Json);
            request.setBody(body);
        });
    }

    private String createDefaultMovieForm(HttpTestClient client) throws IOException {
        Response response = postJson(client, "/api/v1/forms", DEFAULT_MOVIE_FORM_BODY);
        assertEquals(HttpStatusCode.Created, status(response));
        return id(response);
    }

    private JsonNode json(Response response) throws IOException {
        return mapper.readTree(bodyAsText(response));
    }

    private String id(Response response) throws IOException {
        return required(json(response), "id").asText();
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) {
            fail("Missing " + field);
        }
        return value;
    }

    private static TextNode textNode(String value) {
        return TextNode.valueOf(value);
    }

    private static FieldDefinition fieldDefinition(String name, FieldType type) {
        return fieldDefinition(name, type, false);
    }

    private static FieldDefinition fieldDefinition(String name, FieldType type, boolean required) {
        return new FieldDefinition(name, type, required, null, null);
    }

    private static Path writeFile(Path path, String contents) throws IOException {
        Files.write(path, contents.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || Files.notExists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new IllegalStateException("Failed to delete " + path, exception);
                }
            });
        }
    }

    private record TestServices(
        EFormService eformService,
        ExportImportService exportImportService,
        RelocationService relocationService,
        PathSecurity pathSecurity
    ) {
    }

    private record TestEnv(
        EFormService eformService,
        ExportImportService exportImportService,
        RelocationService relocationService,
        PathSecurity pathSecurity
    ) {
    }

    private record TestCtx(
        EFormService eformService,
        ExportImportService exportImportService,
        RelocationService relocationService,
        PathSecurity pathSecurity,
        VirtualPathResolver virtualPathResolver
    ) {
    }
}
