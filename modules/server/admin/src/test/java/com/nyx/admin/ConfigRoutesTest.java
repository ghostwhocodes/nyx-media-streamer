package com.nyx.admin;

import static com.nyx.admin.AdminApiTestSupport.bodyAsText;
import static com.nyx.admin.AdminApiTestSupport.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.config.AuthUtils;
import com.nyx.config.ConfigService;
import com.nyx.config.DatabaseConfig;
import com.nyx.config.MediaRootConfig;
import com.nyx.config.ServerConfig;
import com.nyx.http.ContentType;
import com.nyx.http.HttpStatusCode;
import com.nyx.http.UserIdPrincipal;
import com.nyx.json.NyxJson;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigRoutesTest {
    private final ObjectMapper json = NyxJson.newMapper();
    private static final ContentType JSON_CONTENT_TYPE = ContentType.Application.getJson();

    private ServerConfig testConfig() {
        return testConfig(Map.of(), "", false);
    }

    private ServerConfig testConfig(Map<String, String> users, String token, boolean authEnabled) {
        return AdminFixtures.testServerConfig(
            List.of(new MediaRootConfig(Path.of("/media"))),
            new DatabaseConfig(Path.of("/tmp")),
            AdminFixtures.testAuthConfig(authEnabled, token, users, Map.of())
        );
    }

    private AuthConfigWithPassword authConfigWithAdmin() {
        return authConfigWithAdmin("secret-password");
    }

    private AuthConfigWithPassword authConfigWithAdmin(String password) {
        String hash = AuthUtils.hashPassword(password);
        return new AuthConfigWithPassword(
            testConfig(Map.of("admin", hash), "", true),
            password
        );
    }

    private void installWithRoutes(
        AdminApiTestSupport.ApplicationHarness app,
        ServerConfig config,
        List<String> authProviders
    ) {
        app.installContentNegotiation(json);
        app.installStatusPages();
        if (!authProviders.isEmpty()) {
            app.installAuthentication(authentication -> {
                if (authProviders.contains("api-token")) {
                    authentication.bearer("api-token", bearer ->
                        bearer.authenticate(credential ->
                            credential.token().equals(config.getAuth().getToken()) ? new UserIdPrincipal("api") : null
                        )
                    );
                }
                if (authProviders.contains("api-basic")) {
                    authentication.basic("api-basic", basic -> {
                        basic.setRealm("nyx");
                        basic.validate(credentials -> {
                            String hash = config.getAuth().getUsers().get(credentials.name());
                            if (hash != null && AuthUtils.verifyPassword(credentials.password(), hash)) {
                                return new UserIdPrincipal(credentials.name());
                            }
                            return null;
                        });
                    });
                }
            });
        }
        app.routing(route -> AdminFixtures.configRoutes(route, new ConfigService(config), authProviders));
    }

    @Test
    void getConfigReturnsSanitizedConfigWithoutAuth() throws Exception {
        AdminApiTestSupport.testApplication(app -> {
            installWithRoutes(app, testConfig(), List.of());
            try (var response = app.client().get("/api/v1/config")) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(response));
                JsonNode body = json.readTree(bodyAsText(response));
                assertEquals("0.0.0.0", body.get("host").asText());
                assertEquals(8080, body.get("port").asInt());
                assertNotNull(body.get("auth"));
                assertNull(body.get("auth").get("token"));
            }
        });
    }

    @Test
    void getConfigWithAuthReturnsUnauthorizedWithoutCredentials() throws Exception {
        String hash = AuthUtils.hashPassword("secret");
        ServerConfig config = testConfig(Map.of("admin", hash), "", true);
        AdminApiTestSupport.testApplication(app -> {
            installWithRoutes(app, config, List.of("api-basic"));
            try (var response = app.client().get("/api/v1/config")) {
                assertEquals(HttpStatusCode.Companion.getUnauthorized(), status(response));
            }
        });
    }

    @Test
    void getConfigWithBasicAuthSucceeds() throws Exception {
        String hash = AuthUtils.hashPassword("secret");
        ServerConfig config = testConfig(Map.of("admin", hash), "", true);
        AdminApiTestSupport.testApplication(app -> {
            installWithRoutes(app, config, List.of("api-basic"));
            try (var response = app.client().get("/api/v1/config", builder -> builder.basicAuth("admin", "secret"))) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(response));
            }
        });
    }

    @Test
    void putConfigPersistsCorsOriginsAndReturnsUpdatedConfig() throws Exception {
        AuthConfigWithPassword auth = authConfigWithAdmin();
        AdminApiTestSupport.testApplication(app -> {
            installWithRoutes(app, auth.config(), List.of("api-basic"));
            try (var response = app.client().put("/api/v1/config", builder -> {
                builder.contentType(JSON_CONTENT_TYPE);
                builder.basicAuth("admin", auth.password());
                builder.setBody("{\"corsOrigins\": [\"http://new.com\"]}");
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(response));
                JsonNode body = json.readTree(bodyAsText(response));
                assertTrue(body.get("restartRequired").asBoolean());
                assertEquals(1, body.get("config").get("corsOrigins").size());
                assertEquals("http://new.com", body.get("config").get("corsOrigins").get(0).asText());
            }
        });
    }

    @Test
    void postUserWithShortPasswordReturnsBadRequest() throws Exception {
        AuthConfigWithPassword auth = authConfigWithAdmin();
        AdminApiTestSupport.testApplication(app -> {
            installWithRoutes(app, auth.config(), List.of("api-basic"));
            try (var response = app.client().post("/api/v1/auth/users", builder -> {
                builder.contentType(JSON_CONTENT_TYPE);
                builder.basicAuth("admin", auth.password());
                builder.setBody("{\"username\":\"newuser\",\"password\":\"short\"}");
            })) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), status(response));
            }
        });
    }

    @Test
    void postUserWithValidPasswordReturnsCreated() throws Exception {
        AuthConfigWithPassword auth = authConfigWithAdmin();
        AdminApiTestSupport.testApplication(app -> {
            installWithRoutes(app, auth.config(), List.of("api-basic"));
            try (var response = app.client().post("/api/v1/auth/users", builder -> {
                builder.contentType(JSON_CONTENT_TYPE);
                builder.basicAuth("admin", auth.password());
                builder.setBody("{\"username\":\"newuser\",\"password\":\"longpassword123\"}");
            })) {
                assertEquals(HttpStatusCode.Companion.getCreated(), status(response));
                JsonNode body = json.readTree(bodyAsText(response));
                assertEquals("newuser", body.get("username").asText());
            }
        });
    }

    @Test
    void postDuplicateUserReturnsConflict() throws Exception {
        String hash = AuthUtils.hashPassword("existing");
        String adminHash = AuthUtils.hashPassword("secret-password");
        ServerConfig config = testConfig(Map.of("admin", adminHash, "existing", hash), "", true);
        AdminApiTestSupport.testApplication(app -> {
            installWithRoutes(app, config, List.of("api-basic"));
            try (var response = app.client().post("/api/v1/auth/users", builder -> {
                builder.contentType(JSON_CONTENT_TYPE);
                builder.basicAuth("admin", "secret-password");
                builder.setBody("{\"username\":\"admin\",\"password\":\"newpassword1\"}");
            })) {
                assertEquals(HttpStatusCode.Companion.getConflict(), status(response));
            }
        });
    }

    @Test
    void deleteExistingUserReturnsNoContent() throws Exception {
        String adminHash = AuthUtils.hashPassword("secret-password");
        String userHash = AuthUtils.hashPassword("password");
        ServerConfig config = testConfig(Map.of("admin", adminHash, "testuser", userHash), "", true);
        AdminApiTestSupport.testApplication(app -> {
            installWithRoutes(app, config, List.of("api-basic"));
            try (var response = app.client().delete("/api/v1/auth/users/testuser", builder ->
                builder.basicAuth("admin", "secret-password")
            )) {
                assertEquals(HttpStatusCode.Companion.getNoContent(), status(response));
            }
        });
    }

    @Test
    void deleteNonExistentUserReturnsNotFound() throws Exception {
        AuthConfigWithPassword auth = authConfigWithAdmin();
        AdminApiTestSupport.testApplication(app -> {
            installWithRoutes(app, auth.config(), List.of("api-basic"));
            try (var response = app.client().delete("/api/v1/auth/users/ghost", builder ->
                builder.basicAuth("admin", auth.password())
            )) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), status(response));
            }
        });
    }

    @Test
    void postAuthUsersRejectsBlankUsername() throws Exception {
        AuthConfigWithPassword auth = authConfigWithAdmin();
        AdminApiTestSupport.testApplication(app -> {
            installWithRoutes(app, auth.config(), List.of("api-basic"));
            try (var response = app.client().post("/api/v1/auth/users", builder -> {
                builder.contentType(JSON_CONTENT_TYPE);
                builder.basicAuth("admin", auth.password());
                builder.setBody("{\"username\":\"\",\"password\":\"12345678\"}");
            })) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), status(response));
            }
        });
    }

    @Test
    void adminConfigWritesAreForbiddenWhenAuthIsNotConfigured() throws Exception {
        AdminApiTestSupport.testApplication(app -> {
            installWithRoutes(app, testConfig(), List.of());
            try (var response = app.client().put("/api/v1/config", builder -> {
                builder.contentType(JSON_CONTENT_TYPE);
                builder.setBody("{\"corsOrigins\": [\"http://new.com\"]}");
            })) {
                assertEquals(HttpStatusCode.Companion.getForbidden(), status(response));
            }
        });
    }

    @Test
    void getConfigShowsUsersListWithoutHashes() throws Exception {
        String hash = AuthUtils.hashPassword("secret");
        ServerConfig config = testConfig(Map.of("admin", hash, "viewer", hash), "", false);
        AdminApiTestSupport.testApplication(app -> {
            installWithRoutes(app, config, List.of());
            try (var response = app.client().get("/api/v1/config")) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(response));
                JsonNode users = json.readTree(bodyAsText(response)).get("auth").get("users");
                assertEquals(2, users.size());
                assertTrue(users.toString().contains("admin"));
                assertTrue(users.toString().contains("viewer"));
                assertFalse(users.toString().contains(hash));
            }
        });
    }

    private record AuthConfigWithPassword(ServerConfig config, String password) {
    }
}
