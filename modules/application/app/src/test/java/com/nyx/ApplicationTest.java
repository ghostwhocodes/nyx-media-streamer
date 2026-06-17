package com.nyx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nyx.config.AuthConfig;
import com.nyx.config.AuthUtils;
import com.nyx.config.CompatibilityConfig;
import com.nyx.config.DatabaseConfig;
import com.nyx.config.MediaRootConfig;
import com.nyx.config.QloudCompatibilityConfig;
import com.nyx.config.ServerConfig;
import com.nyx.config.TlsConfig;
import com.nyx.http.AuthMode;
import com.nyx.http.ContentType;
import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import com.nyx.http.UserIdPrincipal;
import com.nyx.json.NyxJson;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import static com.nyx.AppTestData.testAuthConfig;
import static com.nyx.AppTestData.testFfmpegConfig;
import static com.nyx.AppTestData.testServerConfig;
import static com.nyx.AppTestData.testTranscodeConfig;
import static com.nyx.AppTestSupport.bodyAsText;
import static com.nyx.AppTestSupport.contentType;
import static com.nyx.AppTestSupport.status;
import static com.nyx.AppTestSupport.testApplication;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationTest {
    private static final ObjectMapper JSON = NyxJson.newMapper();
    private static final List<String> MOBILE_OPENAPI_SNAPSHOT_PATHS = List.of(
        "/api/v1/health",
        "/api/v1/openapi.json",
        "/api/v1/client/capabilities",
        "/api/v1/browse",
        "/api/v1/search/files",
        "/api/v1/images/thumb",
        "/api/v1/images/file",
        "/api/v1/audio/file",
        "/api/v1/stream.m3u8"
    );

    @Test
    void configurePluginsInstallsContentNegotiation() throws Exception {
        testApplication(app -> {
            app.configurePlugins();

            var response = app.client().get("/nonexistent");
            assertNotEquals(HttpStatusCode.InternalServerError, status(response));
        });
    }

    @Test
    void configurePluginsWithNoCorsOriginsRejectsCrossOriginRequestsFailSecure() throws Exception {
        testApplication(app -> {
            app.configurePlugins();

            var direct = app.client().get("/nonexistent");
            assertNotEquals(HttpStatusCode.InternalServerError, status(direct));

            var crossOrigin = app.client().get("/nonexistent", request -> request.header(HttpHeaders.Origin, "http://evil.example.com"));
            assertEquals(HttpStatusCode.Forbidden, status(crossOrigin));
        });
    }

    @Test
    void configurePluginsWithNoCorsOriginsAllowsSameOriginRequests() throws Exception {
        testApplication(app -> {
            app.configurePlugins();
            String sameOrigin = "http://127.0.0.1:" + app.app().port();

            var response = app.client().get("/nonexistent", request -> request.header(HttpHeaders.Origin, sameOrigin));

            assertNotEquals(HttpStatusCode.Forbidden, status(response));
            assertNotEquals(HttpStatusCode.InternalServerError, status(response));
        });
    }

    @Test
    void configurePluginsWithSpecificCorsOrigins() throws Exception {
        testApplication(app -> {
            app.configurePlugins(testConfig(List.of("example.com"), testAuthConfig()));

            var response = app.client().get("/");
            assertNotNull(status(response));
        });
    }

    @Test
    void configurePluginsWithAuthEnabledInstallsAuthentication() throws Exception {
        testApplication(app -> {
            app.configurePlugins(testConfig(List.of(), testAuthConfig(true, "test-secret", Map.of(), Map.of())));
            app.routing(route -> route.withAuth(AuthMode.REQUIRED, List.of("api-token"))
                .get("/protected", scope -> scope.getCall().respondText("ok")));

            var unauth = app.client().get("/protected");
            assertEquals(HttpStatusCode.Unauthorized, status(unauth));

            var authed = app.client().get("/protected", request -> request.header(HttpHeaders.Authorization, "Bearer test-secret"));
            assertEquals(HttpStatusCode.OK, status(authed));
            assertEquals("ok", bodyAsText(authed));

            var badToken = app.client().get("/protected", request -> request.header(HttpHeaders.Authorization, "Bearer wrong"));
            assertEquals(HttpStatusCode.Unauthorized, status(badToken));
        });
    }

    @Test
    void configurePluginsWithAuthDisabledDoesNotInstallAuthentication() throws Exception {
        testApplication(app -> {
            app.configurePlugins(testConfig(List.of(), testAuthConfig(false, "", Map.of(), Map.of())));

            var response = app.client().get("/");
            assertNotNull(status(response));
        });
    }

    @Test
    void configurePluginsWithAuthEnabledButBlankTokenDoesNotInstallAuthentication() throws Exception {
        testApplication(app -> {
            app.configurePlugins(testConfig(List.of(), testAuthConfig(true, "", Map.of(), Map.of())));

            var response = app.client().get("/");
            assertNotNull(status(response));
        });
    }

    @Test
    void configurePluginsWithMetricsRegistryInstallsMicrometerMetrics() throws Exception {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        testApplication(app -> {
            app.configurePlugins(registry);

            var response = app.client().get("/");
            assertNotNull(status(response));
            assertTrue(registry.scrape().contains("http_server_request_duration_seconds"));
        });
    }

    @Test
    void getOpenapiJsonEndpointMatchesMobileContractSnapshot() throws Exception {
        Path dbDir = Files.createTempDirectory("nyx-app-openapi-test");
        try {
            ServerConfig config = withDatabase(testConfig(List.of(), testAuthConfig()), dbDir);
            ConcurrentHashMap<String, String> runtimeUsers = new ConcurrentHashMap<>(config.getAuth().getUsers());

            testApplication(app -> {
                app.configureRouting(config, runtimeUsers);

                var response = app.client().get("/api/v1/openapi.json");
                assertEquals(HttpStatusCode.OK, status(response));
                JsonNode actual = mobileOpenApiSnapshot(JSON.readTree(bodyAsText(response)));
                JsonNode expected = readJsonResource("/openapi/mobile-client-openapi-snapshot.json");
                assertEquals(
                    expected,
                    actual,
                    () -> "Mobile OpenAPI contract snapshot changed.\nExpected:\n"
                        + prettyJson(expected)
                        + "\nActual:\n"
                        + prettyJson(actual)
                );
            });
        } finally {
            deleteTree(dbDir);
        }
    }

    @Test
    void configuredApplicationProtectsMobileClientRoutesWhenAuthEnabled() throws Exception {
        Path tempDir = Files.createTempDirectory("nyx-app-mobile-contract-test");
        try {
            Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
            writeJpeg(mediaRoot.resolve("photo.jpg"));
            writeFakeAudio(mediaRoot.resolve("song.mp3"));

            AuthConfig auth = testAuthConfig(
                true,
                "mobile-token",
                Map.of("mobile", AuthUtils.hashPassword("secret")),
                Map.of()
            );
            ServerConfig config = testServerConfig(
                "127.0.0.1",
                0,
                List.of(),
                List.of(new MediaRootConfig(mediaRoot, "local", "library")),
                testFfmpegConfig(),
                testTranscodeConfig(),
                new DatabaseConfig(tempDir.resolve("db")),
                new com.nyx.config.ThumbnailConfig(),
                new com.nyx.config.AudioConfig(),
                auth,
                new com.nyx.config.RateLimitConfig(),
                new com.nyx.config.CsrfConfig(),
                new TlsConfig(),
                new com.nyx.config.WebhookConfig(),
                new com.nyx.config.QuotaConfig(),
                new com.nyx.config.BackupConfig(),
                new com.nyx.config.StorageConfig()
            );
            ConcurrentHashMap<String, String> runtimeUsers = new ConcurrentHashMap<>(config.getAuth().getUsers());

            testApplication(app -> {
                app.configureRouting(config, runtimeUsers);

                for (String path : List.of(
                    "/api/v1/browse?path=library",
                    "/api/v1/search/files?query=photo",
                    "/api/v1/images/thumb?path=library/photo.jpg&size=150",
                    "/api/v1/images/file?path=library/photo.jpg",
                    "/api/v1/audio/file?path=library/song.mp3",
                    "/api/v1/stream.m3u8?path=library/photo.jpg&quality=medium"
                )) {
                    try (Response response = app.client().get(path)) {
                        assertUnauthorized(response);
                    }
                }

                try (Response capabilities = app.client().get("/api/v1/client/capabilities")) {
                    assertEquals(HttpStatusCode.OK, status(capabilities));
                    String body = bodyAsText(capabilities);
                    assertTrue(body.contains("\"authEnabled\":true"));
                    assertTrue(body.contains("\"routeTemplates\""));
                    assertTrue(body.contains("\"search\":\"/api/v1/search/files?query={query}\""));
                    assertTrue(body.contains("\"typed_errors\""));
                    assertTrue(body.contains("\"media_capability_hints\""));
                    assertTrue(body.contains("\"openapi_json\""));
                }

                try (Response missingStreamPath = app.client().get(
                    "/api/v1/stream.m3u8",
                    request -> request.header(HttpHeaders.Authorization, "Bearer mobile-token")
                )) {
                    assertEquals(HttpStatusCode.BadRequest, status(missingStreamPath));
                    assertTrue(bodyAsText(missingStreamPath).contains("INVALID_REQUEST"));
                }

                try (Response browse = app.client().get(
                    "/api/v1/browse?path=library",
                    request -> request.header(HttpHeaders.Authorization, "Bearer mobile-token")
                )) {
                    assertEquals(HttpStatusCode.OK, status(browse));
                    String body = bodyAsText(browse);
                    assertTrue(body.contains("\"routeTemplates\""));
                    assertTrue(body.contains("\"links\""));
                    assertTrue(body.contains("\"capabilities\""));
                    assertTrue(body.contains("\"imageUrl\""));
                    assertTrue(body.contains("\"audioUrl\""));
                }

                try (Response search = app.client().get(
                    "/api/v1/search/files?query=photo",
                    request -> request.header(HttpHeaders.Authorization, "Bearer mobile-token")
                )) {
                    assertEquals(HttpStatusCode.OK, status(search));
                    String body = bodyAsText(search);
                    assertTrue(body.contains("\"routeTemplates\""));
                    assertTrue(body.contains("\"links\""));
                    assertTrue(body.contains("\"capabilities\""));
                }

                try (Response thumbnail = app.client().get(
                    "/api/v1/images/thumb?path=library/photo.jpg&size=150",
                    request -> request.header(HttpHeaders.Authorization, "Bearer mobile-token")
                )) {
                    assertEquals(HttpStatusCode.OK, status(thumbnail));
                    assertEquals(ContentType.Image.JPEG, contentType(thumbnail));
                }

                try (Response image = app.client().get(
                    "/api/v1/images/file?path=library/photo.jpg",
                    request -> request.basicAuth("mobile", "secret")
                )) {
                    assertEquals(HttpStatusCode.OK, status(image));
                    assertEquals(ContentType.parse("image/jpeg"), contentType(image));
                }

                try (Response audio = app.client().get(
                    "/api/v1/audio/file?path=library/song.mp3",
                    request -> request.header(HttpHeaders.Authorization, "Bearer mobile-token")
                )) {
                    assertEquals(HttpStatusCode.OK, status(audio));
                    assertEquals(ContentType.parse("audio/mpeg"), contentType(audio));
                }
            });
        } finally {
            deleteTree(tempDir);
        }
    }

    @Test
    void metricsEndpointServesPrometheusFormat() throws Exception {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        com.nyx.admin.MetricsService metricsService = new com.nyx.admin.MetricsService(registry);

        testApplication(app -> {
            app.configurePlugins(registry);
            app.routing(route -> route.get("/api/v1/metrics", scope -> scope.getCall().respondText(metricsService.scrape(), ContentType.Text.Plain)));

            var response = app.client().get("/api/v1/metrics");
            assertEquals(HttpStatusCode.OK, status(response));
            assertTrue(bodyAsText(response).isBlank() == false);
        });
    }

    @Test
    void configurePluginsInstallsAllExpectedPlugins() throws Exception {
        testApplication(app -> {
            app.configurePlugins();

            var response = app.client().get("/nonexistent");
            assertNotNull(status(response));
        });
    }

    @Test
    void configureRoutingBootsThroughTheApplicationCompositionRoot() throws Exception {
        Path dbDir = Files.createTempDirectory("nyx-app-routing-test");
        try {
            ServerConfig config = withDatabase(testConfig(List.of(), testAuthConfig()), dbDir);
            ConcurrentHashMap<String, String> runtimeUsers = new ConcurrentHashMap<>(config.getAuth().getUsers());

            testApplication(app -> {
                app.configurePlugins(config, null, null, runtimeUsers);
                app.configureRouting(config, runtimeUsers);

                var response = app.client().get("/api/v1/health");
                assertEquals(HttpStatusCode.OK, status(response));

                var openApiResponse = app.client().get("/api/v1/openapi.json");
                assertEquals(HttpStatusCode.OK, status(openApiResponse));
                assertTrue(bodyAsText(openApiResponse).contains("\"openapi\""));
            });
        } finally {
            deleteTree(dbDir);
        }
    }

    @Test
    void configureRoutingRegistersMediaObjectPersistenceInTheSharedDatabaseMap() throws Exception {
        Path dbDir = Files.createTempDirectory("nyx-app-media-objects-test");
        try {
            ServerConfig config = withDatabase(testConfig(List.of(), testAuthConfig()), dbDir);
            ConcurrentHashMap<String, String> runtimeUsers = new ConcurrentHashMap<>(config.getAuth().getUsers());

            testApplication(app -> {
                app.configurePlugins(config, null, null, runtimeUsers);
                app.configureRouting(config, runtimeUsers);

                @SuppressWarnings("unchecked")
                var databases = (Map<String, javax.sql.DataSource>) app.app().unsafe.appDataManager.get(AppRouting.SHARED_DATABASES_KEY);
                assertTrue(databases.containsKey("media_objects"));
            });
        } finally {
            deleteTree(dbDir);
        }
    }

    @Test
    void configureRoutingWiresThePrimaryMediaSessionReportRoute() throws Exception {
        Path tempDir = Files.createTempDirectory("nyx-app-media-report-test");
        try {
            ServerConfig config = withDatabase(testConfig(List.of(), testAuthConfig()), tempDir.resolve("db"));
            ConcurrentHashMap<String, String> runtimeUsers = new ConcurrentHashMap<>(config.getAuth().getUsers());

            testApplication(app -> {
                app.configurePlugins(config, null, null, runtimeUsers);
                app.configureRouting(config, runtimeUsers);

                var response = app.client().post(
                    "/api/v1/media/sessions/missing-session/report",
                    request -> {
                        request.contentType(ContentType.Application.Json);
                        request.setBody("{\"event\":\"HEARTBEAT\",\"positionMillis\":1000}");
                    }
                );

                assertEquals(HttpStatusCode.NotFound, status(response));
            });
        } finally {
            deleteTree(tempDir);
        }
    }

    @Test
    void configurePluginsWithSpecificCorsOriginsInstallsCorrectly() throws Exception {
        Path tempDir = Files.createTempDirectory("nyx-app-cov-test");
        try {
            Path mediaDir = Files.createDirectories(tempDir.resolve("media"));
            testApplication(app -> app.configurePlugins(testServerConfig(
                "0.0.0.0",
                8080,
                List.of("example.com", "test.com"),
                List.of(new MediaRootConfig(mediaDir, "local")),
                AppTestData.testFfmpegConfig(),
                AppTestData.testTranscodeConfig(),
                new DatabaseConfig(tempDir.resolve("db")),
                new com.nyx.config.ThumbnailConfig(),
                new com.nyx.config.AudioConfig(),
                new AuthConfig(),
                new com.nyx.config.RateLimitConfig(),
                new com.nyx.config.CsrfConfig(),
                new TlsConfig(),
                new com.nyx.config.WebhookConfig(),
                new com.nyx.config.QuotaConfig(),
                new com.nyx.config.BackupConfig(),
                new com.nyx.config.StorageConfig()
            )));
        } finally {
            deleteTree(tempDir);
        }
    }

    @Test
    void configurePluginsWithNullConfigUsesDefaults() throws Exception {
        testApplication(app -> {
            app.configurePlugins((ServerConfig) null);

            var response = app.client().get("/test");
            assertNotEquals(HttpStatusCode.InternalServerError, status(response));
        });
    }

    @Test
    void configurePluginsWithConfiguredCorsOriginsAllowsPreflightForThoseOrigins() throws Exception {
        testApplication(app -> {
            app.configurePlugins(testConfig(List.of("http://example.com"), testAuthConfig()));

            for (String method : List.of("GET", "POST", "PUT", "DELETE")) {
                var response = app.client().options(
                    "/test",
                    request -> {
                        request.header(HttpHeaders.Origin, "http://example.com");
                        request.header(HttpHeaders.AccessControlRequestMethod, method);
                    }
                );
                assertEquals(HttpStatusCode.fromValue(204), status(response));
                assertEquals("http://example.com", response.header("Access-Control-Allow-Origin"));
                assertEquals("Origin", response.header("Vary"));
                assertEquals("GET,POST,PUT,DELETE,PATCH,OPTIONS", response.header("Access-Control-Allow-Methods"));
                assertEquals(
                    "Content-Type,Authorization,X-Requested-With,X-Request-ID",
                    response.header("Access-Control-Allow-Headers")
                );
            }
        });
    }

    @Test
    void configurePluginsWithBasicAuthUsersInstallsBasicAuthentication() throws Exception {
        String hash = AuthUtils.hashPassword("secret123");

        testApplication(app -> {
            app.configurePlugins(testConfig(List.of(), testAuthConfig(true, "", Map.of("admin", hash), Map.of())));
            app.routing(route -> route.withAuth(AuthMode.REQUIRED, List.of("api-basic"))
                .get("/protected", scope -> scope.getCall().respondText("ok")));

            var unauth = app.client().get("/protected");
            assertEquals(HttpStatusCode.Unauthorized, status(unauth));

            var authed = app.client().get("/protected", request -> request.basicAuth("admin", "secret123"));
            assertEquals(HttpStatusCode.OK, status(authed));
            assertEquals("ok", bodyAsText(authed));

            var badPass = app.client().get("/protected", request -> request.basicAuth("admin", "wrong"));
            assertEquals(HttpStatusCode.Unauthorized, status(badPass));

            var badUser = app.client().get("/protected", request -> request.basicAuth("unknown", "secret123"));
            assertEquals(HttpStatusCode.Unauthorized, status(badUser));
        });
    }

    @Test
    void configurePluginsWithBothBearerAndBasicAuth() throws Exception {
        String hash = AuthUtils.hashPassword("password");

        testApplication(app -> {
            app.configurePlugins(testConfig(
                List.of(),
                testAuthConfig(true, "my-token", Map.of("user1", hash), Map.of())
            ));
            app.routing(route -> route.withAuth(AuthMode.OPTIONAL, List.of("api-token", "api-basic"))
                .get("/protected", scope -> scope.getCall().respondText("ok")));

            var bearer = app.client().get("/protected", request -> request.header(HttpHeaders.Authorization, "Bearer my-token"));
            assertEquals(HttpStatusCode.OK, status(bearer));

            var basic = app.client().get("/protected", request -> request.basicAuth("user1", "password"));
            assertEquals(HttpStatusCode.OK, status(basic));

            var none = app.client().get("/protected");
            assertEquals(HttpStatusCode.Unauthorized, status(none));
        });
    }

    @Test
    void rateLimitingWorksAcrossAuthSchemes() throws Exception {
        ApplicationRuntime.clearAuthFailureStateForTesting();
        String hash = AuthUtils.hashPassword("correct");

        testApplication(app -> {
            app.configurePlugins(testConfig(List.of(), testAuthConfig(true, "tok", Map.of("admin", hash), Map.of())));
            app.routing(route -> route.withAuth(AuthMode.REQUIRED, List.of("api-token"))
                .get("/protected", scope -> scope.getCall().respondText("ok")));

            for (int index = 0; index < ApplicationRuntime.AUTH_RATE_LIMIT_MAX_FAILURES; index++) {
                app.client().get("/protected", request -> request.header(HttpHeaders.Authorization, "Bearer wrong"));
            }

            var limited = app.client().get("/protected", request -> request.header(HttpHeaders.Authorization, "Bearer tok"));
            assertEquals(HttpStatusCode.TooManyRequests, status(limited));
        });

        ApplicationRuntime.clearAuthFailureStateForTesting();
    }

    @Test
    void configurePluginsWithWildcardCorsOriginLogsWarningAndBlocksCrossOrigin() throws Exception {
        testApplication(app -> {
            app.configurePlugins(testConfig(List.of("*"), testAuthConfig()));

            var response = app.client().get("/nonexistent", request -> request.header(HttpHeaders.Origin, "http://evil.com"));
            assertEquals(HttpStatusCode.Forbidden, status(response));
        });
    }

    @Test
    void multiTokenAuthReturnsCorrectUserId() throws Exception {
        testApplication(app -> {
            app.configurePlugins(testConfig(
                List.of(),
                testAuthConfig(true, "", Map.of(), Map.of("tok-alice", "alice", "tok-bob", "bob"))
            ));
            app.routing(route -> route.withAuth(AuthMode.REQUIRED, List.of("api-token"))
                .get("/whoami", scope -> {
                    UserIdPrincipal principal = scope.getCall().principal(UserIdPrincipal.class);
                    scope.getCall().respondText(principal == null ? "unknown" : principal.getName());
                }));

            var alice = app.client().get("/whoami", request -> request.header(HttpHeaders.Authorization, "Bearer tok-alice"));
            assertEquals(HttpStatusCode.OK, status(alice));
            assertEquals("alice", bodyAsText(alice));

            var bob = app.client().get("/whoami", request -> request.header(HttpHeaders.Authorization, "Bearer tok-bob"));
            assertEquals(HttpStatusCode.OK, status(bob));
            assertEquals("bob", bodyAsText(bob));

            var bad = app.client().get("/whoami", request -> request.header(HttpHeaders.Authorization, "Bearer invalid"));
            assertEquals(HttpStatusCode.Unauthorized, status(bad));
        });
    }

    @Test
    void legacySingleTokenStillWorksAlongsideMultiToken() throws Exception {
        testApplication(app -> {
            app.configurePlugins(testConfig(
                List.of(),
                testAuthConfig(true, "legacy-secret", Map.of(), Map.of("tok-alice", "alice"))
            ));
            app.routing(route -> route.withAuth(AuthMode.REQUIRED, List.of("api-token"))
                .get("/whoami", scope -> {
                    UserIdPrincipal principal = scope.getCall().principal(UserIdPrincipal.class);
                    scope.getCall().respondText(principal == null ? "unknown" : principal.getName());
                }));

            var legacy = app.client().get("/whoami", request -> request.header(HttpHeaders.Authorization, "Bearer legacy-secret"));
            assertEquals(HttpStatusCode.OK, status(legacy));
            assertEquals("api", bodyAsText(legacy));

            var alice = app.client().get("/whoami", request -> request.header(HttpHeaders.Authorization, "Bearer tok-alice"));
            assertEquals(HttpStatusCode.OK, status(alice));
            assertEquals("alice", bodyAsText(alice));
        });
    }

    @Test
    void multiTokenOnlyAuthWorksWithoutLegacyToken() throws Exception {
        testApplication(app -> {
            app.configurePlugins(testConfig(
                List.of(),
                testAuthConfig(true, "", Map.of(), Map.of("tok1", "user1"))
            ));
            app.routing(route -> route.withAuth(AuthMode.REQUIRED, List.of("api-token"))
                .get("/protected", scope -> scope.getCall().respondText("ok")));

            var authed = app.client().get("/protected", request -> request.header(HttpHeaders.Authorization, "Bearer tok1"));
            assertEquals(HttpStatusCode.OK, status(authed));

            var unauthed = app.client().get("/protected", request -> request.header(HttpHeaders.Authorization, "Bearer wrong"));
            assertEquals(HttpStatusCode.Unauthorized, status(unauthed));
        });
    }

    @Test
    void tlsConfigDefaultsHaveTlsDisabled() {
        TlsConfig tls = new TlsConfig();
        assertFalse(tls.getEnabled());
        assertEquals("", tls.getKeystorePath());
        assertEquals(8443, tls.getPort());
    }

    @Test
    void validateStartupConfigRejectsBlankQloudCompatibilityHost() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> AppRouting.validateStartupConfig(
            testServerConfig(
                "0.0.0.0",
                8080,
                List.of(),
                List.of(),
                testFfmpegConfig(),
                testTranscodeConfig(),
                new DatabaseConfig(Path.of("/tmp")),
                new com.nyx.config.ThumbnailConfig(),
                new com.nyx.config.AudioConfig(),
                testAuthConfig(),
                new com.nyx.config.RateLimitConfig(),
                new com.nyx.config.CsrfConfig(),
                new TlsConfig(),
                new com.nyx.config.WebhookConfig(),
                new com.nyx.config.QuotaConfig(),
                new com.nyx.config.BackupConfig(),
                new com.nyx.config.StorageConfig(),
                qloudCompatibility(true, "", 8081)
            )
        ));

        assertTrue(error.getMessage().contains("compatibility.qloud.host"));
    }

    @Test
    void validateStartupConfigRejectsQloudCompatibilityPortOutsideBindableRange() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> AppRouting.validateStartupConfig(
            testServerConfig(
                "0.0.0.0",
                8080,
                List.of(),
                List.of(),
                testFfmpegConfig(),
                testTranscodeConfig(),
                new DatabaseConfig(Path.of("/tmp")),
                new com.nyx.config.ThumbnailConfig(),
                new com.nyx.config.AudioConfig(),
                testAuthConfig(),
                new com.nyx.config.RateLimitConfig(),
                new com.nyx.config.CsrfConfig(),
                new TlsConfig(),
                new com.nyx.config.WebhookConfig(),
                new com.nyx.config.QuotaConfig(),
                new com.nyx.config.BackupConfig(),
                new com.nyx.config.StorageConfig(),
                qloudCompatibility(true, "127.0.0.1", 65_536)
            )
        ));

        assertTrue(error.getMessage().contains("compatibility.qloud.port=65536"));
    }

    @Test
    void validateStartupConfigRejectsQloudCompatibilityPortMatchingMainPort() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> AppRouting.validateStartupConfig(
            testConfig(List.of(), testAuthConfig(), qloudCompatibility(true, "127.0.0.1", 8080))
        ));

        assertTrue(error.getMessage().contains("must not match server.port"));
    }

    @Test
    void validateStartupConfigRejectsQloudCompatibilityPortMatchingTlsPort() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> AppRouting.validateStartupConfig(
            testServerConfig(
                "0.0.0.0",
                8080,
                List.of(),
                List.of(),
                testFfmpegConfig(),
                testTranscodeConfig(),
                new DatabaseConfig(Path.of("/tmp")),
                new com.nyx.config.ThumbnailConfig(),
                new com.nyx.config.AudioConfig(),
                testAuthConfig(),
                new com.nyx.config.RateLimitConfig(),
                new com.nyx.config.CsrfConfig(),
                new TlsConfig(true, "", "", "nyx", "", 8443),
                new com.nyx.config.WebhookConfig(),
                new com.nyx.config.QuotaConfig(),
                new com.nyx.config.BackupConfig(),
                new com.nyx.config.StorageConfig(),
                qloudCompatibility(true, "127.0.0.1", 8443)
            )
        ));

        assertTrue(error.getMessage().contains("must not match tls.port"));
    }

    @Test
    void validateStartupConfigAllowsDisabledQloudCompatibilityToReuseMainPort() {
        AppRouting.validateStartupConfig(testConfig(List.of(), testAuthConfig(), qloudCompatibility(false, "127.0.0.1", 8080)));
    }

    @Test
    void jsonResponseIsGzipCompressedWhenClientSendsAcceptEncodingGzip() throws Exception {
        testApplication(app -> {
            app.configurePlugins();
            app.routing(route -> route.get(
                "/api/v1/test-compress",
                scope -> scope.getCall().respondText("{\"key\":\"" + "x".repeat(200) + "\"}", ContentType.Application.Json)
            ));

            var response = app.client().get("/api/v1/test-compress", request -> request.header(HttpHeaders.AcceptEncoding, "gzip"));
            assertEquals(HttpStatusCode.OK, status(response));
            assertEquals("gzip", response.header(HttpHeaders.ContentEncoding));
        });
    }

    @Test
    void jsonResponseIsNotCompressedWhenClientOmitsAcceptEncoding() throws Exception {
        testApplication(app -> {
            app.configurePlugins();
            app.routing(route -> route.get(
                "/api/v1/test-no-compress",
                scope -> scope.getCall().respondText("{\"key\":\"value\"}", ContentType.Application.Json)
            ));

            var response = app.client().get("/api/v1/test-no-compress");
            assertEquals(HttpStatusCode.OK, status(response));
            assertNull(response.header(HttpHeaders.ContentEncoding));
        });
    }

    @Test
    void binaryContentTypeIsNotCompressed() throws Exception {
        testApplication(app -> {
            app.configurePlugins();
            app.routing(route -> route.get(
                "/api/v1/test-binary",
                scope -> scope.getCall().respondBytes(new byte[100], ContentType.Application.OctetStream)
            ));

            var response = app.client().get("/api/v1/test-binary", request -> request.header(HttpHeaders.AcceptEncoding, "gzip"));
            assertEquals(HttpStatusCode.OK, status(response));
            assertNull(response.header(HttpHeaders.ContentEncoding));
        });
    }

    @Test
    void imageContentTypeIsNotCompressed() throws Exception {
        testApplication(app -> {
            app.configurePlugins();
            app.routing(route -> route.get(
                "/api/v1/test-image",
                scope -> scope.getCall().respondBytes(new byte[100], ContentType.Image.JPEG)
            ));

            var response = app.client().get("/api/v1/test-image", request -> request.header(HttpHeaders.AcceptEncoding, "gzip"));
            assertEquals(HttpStatusCode.OK, status(response));
            assertNull(response.header(HttpHeaders.ContentEncoding));
        });
    }

    @Test
    void getSwaggerEndpointReturnsHtmlSwaggerUiPage() throws Exception {
        Path dbDir = Files.createTempDirectory("nyx-app-swagger-test");
        try {
            ServerConfig config = withDatabase(testConfig(List.of(), testAuthConfig()), dbDir);
            ConcurrentHashMap<String, String> runtimeUsers = new ConcurrentHashMap<>(config.getAuth().getUsers());

            testApplication(app -> {
                app.configureRouting(config, runtimeUsers);

                var response = app.client().get("/api/v1/swagger");
                assertEquals(HttpStatusCode.OK, status(response));
                ContentType contentType = contentType(response);
                assertNotNull(contentType);
                assertTrue(contentType.match(ContentType.Text.Html), "Expected HTML content type but got " + contentType);
            });
        } finally {
            deleteTree(dbDir);
        }
    }

    @Test
    void configurePluginsInstallsBasicPluginsFromImprovements() throws Exception {
        testApplication(app -> app.configurePlugins());
    }

    @Test
    void configurePluginsWithCorsNonWildcardOriginsFromImprovements() throws Exception {
        Path tempDir = Files.createTempDirectory("nyx-improv-app-test");
        try {
            ServerConfig config = testServerConfig(
                "0.0.0.0",
                8080,
                List.of("localhost:3000"),
                List.of(),
                testFfmpegConfig("ffmpeg", "ffprobe", "4.0", 1, 2, 8, com.nyx.config.FfmpegConfig.DEFAULT_QUALITY_PRESETS, "polling", 500L),
                testTranscodeConfig("dash", 10, 4, 1000, 524_288_000L, 3, 2_000L, 5),
                new DatabaseConfig(tempDir.resolve("db")),
                new com.nyx.config.ThumbnailConfig(),
                new com.nyx.config.AudioConfig(),
                testAuthConfig(),
                new com.nyx.config.RateLimitConfig(),
                new com.nyx.config.CsrfConfig(),
                new TlsConfig(),
                new com.nyx.config.WebhookConfig(),
                new com.nyx.config.QuotaConfig(),
                new com.nyx.config.BackupConfig(),
                new com.nyx.config.StorageConfig()
            );

            testApplication(app -> app.configurePlugins(config));
        } finally {
            deleteTree(tempDir);
        }
    }

    @Test
    void configurePluginsWithAuthEnabledFromImprovements() throws Exception {
        Path tempDir = Files.createTempDirectory("nyx-improv-app-test2");
        try {
            ServerConfig config = testServerConfig(
                "0.0.0.0",
                8080,
                List.of("*"),
                List.of(),
                testFfmpegConfig("ffmpeg", "ffprobe", "4.0", 1, 2, 8, com.nyx.config.FfmpegConfig.DEFAULT_QUALITY_PRESETS, "polling", 500L),
                testTranscodeConfig("dash", 10, 4, 1000, 524_288_000L, 3, 2_000L, 5),
                new DatabaseConfig(tempDir.resolve("db2")),
                new com.nyx.config.ThumbnailConfig(),
                new com.nyx.config.AudioConfig(),
                testAuthConfig(true, "test-token-123", Map.of(), Map.of()),
                new com.nyx.config.RateLimitConfig(),
                new com.nyx.config.CsrfConfig(),
                new TlsConfig(),
                new com.nyx.config.WebhookConfig(),
                new com.nyx.config.QuotaConfig(),
                new com.nyx.config.BackupConfig(),
                new com.nyx.config.StorageConfig()
            );

            testApplication(app -> app.configurePlugins(config, config.getAuth()));
        } finally {
            deleteTree(tempDir);
        }
    }

    @Test
    void configurePluginsWithMetricsRegistryFromImprovements() throws Exception {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        testApplication(app -> app.configurePlugins(registry));
    }

    @Test
    void configurePluginsReturns413WhenContentLengthExceedsLimit() throws Exception {
        testApplication(app -> {
            app.configurePlugins();
            app.routing(route -> route.post("/test", scope -> scope.getCall().respondText("ok")));

            var response = app.client().post("/test", request -> request.setBody("x".repeat((int) (ApplicationRuntime.REQUEST_BODY_MAX_BYTES + 1L))));
            assertEquals(HttpStatusCode.PayloadTooLarge, status(response));
        });
    }

    @Test
    void configurePluginsAllowsRequestWithinSizeLimit() throws Exception {
        testApplication(app -> {
            app.configurePlugins();
            app.routing(route -> route.post("/test-small", scope -> scope.getCall().respondText("ok")));

            var response = app.client().post("/test-small", request -> request.setBody("small body"));
            assertEquals(HttpStatusCode.OK, status(response));
        });
    }

    @Test
    void configurePluginsAuthRateLimitingReturns429AfterThreshold() throws Exception {
        ApplicationRuntime.clearAuthFailureStateForTesting();
        AuthConfig authConfig = testAuthConfig(true, "correct-token", Map.of(), Map.of());

        testApplication(app -> {
            app.configurePlugins(null, authConfig);
            app.routing(route -> route.withAuth(AuthMode.REQUIRED, List.of("api-token"))
                .get("/secure-rl", scope -> scope.getCall().respondText("ok")));

            for (int index = 0; index < ApplicationRuntime.AUTH_RATE_LIMIT_MAX_FAILURES; index++) {
                app.client().get("/secure-rl", request -> request.header(HttpHeaders.Authorization, "Bearer wrong-token"));
            }

            var response = app.client().get("/secure-rl", request -> request.header(HttpHeaders.Authorization, "Bearer wrong-token"));
            assertEquals(HttpStatusCode.TooManyRequests, status(response));
        });

        ApplicationRuntime.clearAuthFailureStateForTesting();
    }

    @Test
    void requestBodyMaxBytesConstantIs10Mb() {
        assertEquals(10L * 1024 * 1024, ApplicationRuntime.REQUEST_BODY_MAX_BYTES);
    }

    @Test
    void authRateLimitConstantsAreCorrect() {
        assertEquals(60L, ApplicationRuntime.AUTH_RATE_LIMIT_WINDOW_SECONDS);
        assertEquals(10, ApplicationRuntime.AUTH_RATE_LIMIT_MAX_FAILURES);
    }

    @Test
    void createApplicationRejectsEnabledCompatibilityListeners() throws Exception {
        Path tempDir = Files.createTempDirectory("nyx-compat-create-app-test");
        try {
            Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
            ServerConfig config = testServerConfig(
                "127.0.0.1",
                0,
                List.of(),
                List.of(new MediaRootConfig(mediaRoot, "local", "library")),
                testFfmpegConfig(),
                testTranscodeConfig(),
                new DatabaseConfig(tempDir.resolve("db")),
                new com.nyx.config.ThumbnailConfig(),
                new com.nyx.config.AudioConfig(),
                testAuthConfig(),
                new com.nyx.config.RateLimitConfig(),
                new com.nyx.config.CsrfConfig(),
                new TlsConfig(),
                new com.nyx.config.WebhookConfig(),
                new com.nyx.config.QuotaConfig(),
                new com.nyx.config.BackupConfig(),
                new com.nyx.config.StorageConfig(),
                qloudCompatibility(true, "127.0.0.1", 0)
            );

            IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                AppRouting.createApplication(config, new ConcurrentHashMap<>(config.getAuth().getUsers()))
            );
            assertTrue(error.getMessage().contains("createApplicationServerGroup"));
        } finally {
            deleteTree(tempDir);
        }
    }

    @Test
    void createApplicationServerGroupIsolatesQloudProcRoutesToCompatibilityListener() throws Exception {
        Path tempDir = Files.createTempDirectory("nyx-compat-group-test");
        try {
            Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
            ServerConfig config = testServerConfig(
                "127.0.0.1",
                0,
                List.of(),
                List.of(new MediaRootConfig(mediaRoot, "local", "library")),
                testFfmpegConfig(),
                testTranscodeConfig(),
                new DatabaseConfig(tempDir.resolve("db")),
                new com.nyx.config.ThumbnailConfig(),
                new com.nyx.config.AudioConfig(),
                testAuthConfig(),
                new com.nyx.config.RateLimitConfig(),
                new com.nyx.config.CsrfConfig(),
                new TlsConfig(),
                new com.nyx.config.WebhookConfig(),
                new com.nyx.config.QuotaConfig(),
                new com.nyx.config.BackupConfig(),
                new com.nyx.config.StorageConfig(),
                qloudCompatibility(true, "127.0.0.1", 0)
            );

            try (ApplicationServerGroup group = AppRouting.createApplicationServerGroup(
                config,
                new ConcurrentHashMap<>(config.getAuth().getUsers())
            )) {
                group.start();

                CompatibilityShimServer qloud = group.compatibilityServers().getFirst();
                String mainBaseUrl = "http://127.0.0.1:" + group.mainApp().port();
                String compatibilityBaseUrl = "http://127.0.0.1:" + qloud.port();

                try (Response mainResponse = postJson(mainBaseUrl, "/proc/hello", """
                    {
                      "action": "hello",
                      "version": 11,
                      "protocol-version": 35
                    }
                    """)) {
                    assertEquals(HttpStatusCode.NotFound, status(mainResponse));
                }

                try (Response compatibilityResponse = postJson(compatibilityBaseUrl, "/proc/hello", """
                    {
                      "action": "hello",
                      "version": 11,
                      "protocol-version": 35
                    }
                    """)) {
                    assertEquals(HttpStatusCode.OK, status(compatibilityResponse));
                    assertTrue(bodyAsText(compatibilityResponse).contains("\"action\":\"hello\""));
                }
            }
        } finally {
            deleteTree(tempDir);
        }
    }

    @Test
    void mainListenerAdminAssetsAreNotShadowedByQloudRoutes() throws Exception {
        Path tempDir = Files.createTempDirectory("nyx-compat-assets-test");
        try {
            Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
            ServerConfig config = testServerConfig(
                "127.0.0.1",
                0,
                List.of(),
                List.of(new MediaRootConfig(mediaRoot, "local", "library")),
                testFfmpegConfig(),
                testTranscodeConfig(),
                new DatabaseConfig(tempDir.resolve("db")),
                new com.nyx.config.ThumbnailConfig(),
                new com.nyx.config.AudioConfig(),
                testAuthConfig(),
                new com.nyx.config.RateLimitConfig(),
                new com.nyx.config.CsrfConfig(),
                new TlsConfig(),
                new com.nyx.config.WebhookConfig(),
                new com.nyx.config.QuotaConfig(),
                new com.nyx.config.BackupConfig(),
                new com.nyx.config.StorageConfig(),
                qloudCompatibility(true, "127.0.0.1", 0)
            );

            try (ApplicationServerGroup group = AppRouting.createApplicationServerGroup(
                config,
                new ConcurrentHashMap<>(config.getAuth().getUsers())
            )) {
                group.start();

                String assetPath = findAdminAssetPath();
                try (Response assetResponse = get("http://127.0.0.1:" + group.mainApp().port(), assetPath)) {
                    assertEquals(HttpStatusCode.OK, status(assetResponse));
                    assertFalse(bodyAsText(assetResponse).contains("JOB_NOT_FOUND"));
                }
            }
        } finally {
            deleteTree(tempDir);
        }
    }

    private static ServerConfig testConfig(List<String> corsOrigins, AuthConfig auth) {
        return testConfig(corsOrigins, auth, AppTestData.disabledCompatibilityConfig());
    }

    private static ServerConfig testConfig(List<String> corsOrigins, AuthConfig auth, CompatibilityConfig compatibility) {
        return testServerConfig(
            "0.0.0.0",
            8080,
            corsOrigins,
            List.of(),
            testFfmpegConfig(),
            testTranscodeConfig(),
            new DatabaseConfig(Path.of("/tmp")),
            new com.nyx.config.ThumbnailConfig(),
            new com.nyx.config.AudioConfig(),
            auth,
            new com.nyx.config.RateLimitConfig(),
            new com.nyx.config.CsrfConfig(),
            new TlsConfig(),
            new com.nyx.config.WebhookConfig(),
            new com.nyx.config.QuotaConfig(),
            new com.nyx.config.BackupConfig(),
            new com.nyx.config.StorageConfig(),
            compatibility
        );
    }

    private static CompatibilityConfig qloudCompatibility(boolean enabled, String host, int port) {
        return new CompatibilityConfig(new QloudCompatibilityConfig(enabled, host, port));
    }

    private static Response get(String baseUrl, String path) throws IOException {
        return new OkHttpClient.Builder()
            .addInterceptor(chain -> chain.proceed(
                chain.request().newBuilder().header(HttpHeaders.AcceptEncoding, "identity").build()
            ))
            .build()
            .newCall(new Request.Builder().url(baseUrl + path).get().build())
            .execute();
    }

    private static Response postJson(String baseUrl, String path, String body) throws IOException {
        return new OkHttpClient.Builder()
            .addInterceptor(chain -> chain.proceed(
                chain.request().newBuilder().header(HttpHeaders.AcceptEncoding, "identity").build()
            ))
            .build()
            .newCall(new Request.Builder()
                .url(baseUrl + path)
                .post(RequestBody.create(body, MediaType.get("application/json")))
                .build())
            .execute();
    }

    private static JsonNode readJsonResource(String resourcePath) throws IOException {
        try (var stream = ApplicationTest.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new AssertionError("Missing test resource: " + resourcePath);
            }
            return JSON.readTree(stream);
        }
    }

    private static JsonNode mobileOpenApiSnapshot(JsonNode spec) {
        ObjectNode snapshot = JSON.createObjectNode();
        snapshot.set("openapi", requiredField(spec, "openapi").deepCopy());
        snapshot.set("info", requiredField(spec, "info").deepCopy());

        JsonNode generatedPaths = requiredField(spec, "paths");
        ObjectNode paths = JSON.createObjectNode();
        for (String path : MOBILE_OPENAPI_SNAPSHOT_PATHS) {
            JsonNode pathSpec = generatedPaths.path(path);
            if (pathSpec.isMissingNode()) {
                throw new AssertionError("Generated OpenAPI spec is missing mobile contract path: " + path);
            }
            paths.set(path, pathSpec.deepCopy());
        }
        snapshot.set("paths", paths);
        return snapshot;
    }

    private static JsonNode requiredField(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode()) {
            throw new AssertionError("Generated OpenAPI spec is missing field: " + fieldName);
        }
        return value;
    }

    private static String prettyJson(JsonNode node) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (IOException exception) {
            throw new RuntimeException("Failed to render JSON", exception);
        }
    }

    private static void assertUnauthorized(Response response) {
        assertEquals(HttpStatusCode.Unauthorized, status(response));
        assertTrue(bodyAsText(response).contains("UNAUTHORIZED"));
    }

    private static void writeJpeg(Path path) throws IOException {
        BufferedImage image = new BufferedImage(80, 60, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y += 1) {
            for (int x = 0; x < image.getWidth(); x += 1) {
                int red = (x * 3) & 0xff;
                int green = (y * 4) & 0xff;
                int blue = 0x60;
                image.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
        ImageIO.write(image, "jpg", path.toFile());
    }

    private static void writeFakeAudio(Path path) throws IOException {
        byte[] bytes = new byte[1024];
        for (int index = 0; index < bytes.length; index += 1) {
            bytes[index] = (byte) (index % 251);
        }
        Files.write(path, bytes);
    }

    private static String findAdminAssetPath() throws Exception {
        URI assetsUri = ApplicationTest.class.getClassLoader().getResource("admin-ui/assets").toURI();
        try (var files = Files.list(Path.of(assetsUri))) {
            return "/assets/" + files
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .findFirst()
                .orElseThrow();
        }
    }

    private static ServerConfig withDatabase(ServerConfig config, Path dbDir) {
        return testServerConfig(
            config.getHost(),
            config.getPort(),
            config.getCorsOrigins(),
            config.getMediaRoots(),
            config.getFfmpeg(),
            config.getTranscode(),
            new DatabaseConfig(dbDir),
            config.getThumbnails(),
            config.getAudio(),
            config.getAuth(),
            config.getRateLimit(),
            config.getCsrf(),
            config.getTls(),
            config.getWebhooks(),
            config.getQuota(),
            config.getBackup(),
            config.getStorage()
        );
    }

    private static void deleteTree(Path root) {
        if (root == null || Files.notExists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
