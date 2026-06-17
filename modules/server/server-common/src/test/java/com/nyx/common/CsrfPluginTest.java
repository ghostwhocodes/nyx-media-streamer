package com.nyx.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.config.AudioConfig;
import com.nyx.config.AuthConfig;
import com.nyx.config.BackupConfig;
import com.nyx.config.CsrfConfig;
import com.nyx.config.DatabaseConfig;
import com.nyx.config.FfmpegConfig;
import com.nyx.config.QuotaConfig;
import com.nyx.config.RateLimitConfig;
import com.nyx.config.ServerConfig;
import com.nyx.config.StorageConfig;
import com.nyx.config.ThumbnailConfig;
import com.nyx.config.TlsConfig;
import com.nyx.config.TranscodeConfig;
import com.nyx.config.WebhookConfig;
import com.nyx.http.HttpStatusCode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

class CsrfPluginTest {
    @Test
    void requiredHeaderAndRequiredHeaderValueAreCorrect() {
        assertEquals("X-Requested-With", CsrfPlugin.REQUIRED_HEADER);
        assertEquals("XMLHttpRequest", CsrfPlugin.REQUIRED_HEADER_VALUE);
    }

    @Test
    void postWithoutXRequestedWithIsRejectedWhenCsrfEnabled() throws Exception {
        ServerConfig serverConfig = serverConfigWithCsrf(true, false);

        ServerCommonTestSupport.testApplication(app -> {
            installPlugins(app, serverConfig);
            app.routing(route -> route.post("/api/v1/resource", scope -> {
                scope.getCall().respond(HttpStatusCode.Created, "created");
            }));

            try (Response response = app.client().post("/api/v1/resource")) {
                assertEquals(HttpStatusCode.Forbidden, ServerCommonTestSupport.status(response));
                assertTrue(ServerCommonTestSupport.bodyAsText(response).contains("CSRF"));
            }
        });
    }

    @Test
    void postWithXRequestedWithXmlHttpRequestIsAllowedWhenCsrfEnabled() throws Exception {
        ServerConfig serverConfig = serverConfigWithCsrf(true, false);

        ServerCommonTestSupport.testApplication(app -> {
            installPlugins(app, serverConfig);
            app.routing(route -> route.post("/api/v1/resource", scope -> {
                scope.getCall().respond(HttpStatusCode.Created, "created");
            }));

            try (Response response = app.client().post("/api/v1/resource", request ->
                request.header(CsrfPlugin.REQUIRED_HEADER, CsrfPlugin.REQUIRED_HEADER_VALUE)
            )) {
                assertEquals(HttpStatusCode.Created, ServerCommonTestSupport.status(response));
            }
        });
    }

    @Test
    void deleteWithoutXRequestedWithIsRejectedWhenCsrfEnabled() throws Exception {
        ServerConfig serverConfig = serverConfigWithCsrf(true, false);

        ServerCommonTestSupport.testApplication(app -> {
            installPlugins(app, serverConfig);
            app.routing(route -> route.delete("/api/v1/resource/{id}", scope -> {
                scope.getCall().respond(HttpStatusCode.NoContent);
            }));

            try (Response response = app.client().delete("/api/v1/resource/123")) {
                assertEquals(HttpStatusCode.Forbidden, ServerCommonTestSupport.status(response));
            }
        });
    }

    @Test
    void putWithoutXRequestedWithIsRejectedWhenCsrfEnabled() throws Exception {
        ServerConfig serverConfig = serverConfigWithCsrf(true, false);

        ServerCommonTestSupport.testApplication(app -> {
            installPlugins(app, serverConfig);
            app.routing(route -> route.put("/api/v1/resource/{id}", scope -> {
                scope.getCall().respond(HttpStatusCode.OK);
            }));

            try (Response response = app.client().put("/api/v1/resource/123", request -> {
            })) {
                assertEquals(HttpStatusCode.Forbidden, ServerCommonTestSupport.status(response));
            }
        });
    }

    @Test
    void getRequestsAreNotAffectedByCsrf() throws Exception {
        ServerConfig serverConfig = serverConfigWithCsrf(true, false);

        ServerCommonTestSupport.testApplication(app -> {
            installPlugins(app, serverConfig);
            app.routing(route -> route.get("/api/v1/resource", scope -> {
                scope.getCall().respondText("ok");
            }));

            try (Response response = app.client().get("/api/v1/resource")) {
                assertEquals(HttpStatusCode.OK, ServerCommonTestSupport.status(response));
            }
        });
    }

    @Test
    void csrfNotAppliedWhenAuthIsEnabled() throws Exception {
        ServerConfig serverConfig = serverConfigWithCsrf(true, true);

        ServerCommonTestSupport.testApplication(app -> {
            installPlugins(app, serverConfig);
            app.routing(route -> route.post("/api/v1/resource", scope -> {
                scope.getCall().respond(HttpStatusCode.Created, "created");
            }));

            try (Response response = app.client().post("/api/v1/resource")) {
                assertNotEquals(HttpStatusCode.Forbidden, ServerCommonTestSupport.status(response));
            }
        });
    }

    @Test
    void csrfNotAppliedWhenCsrfDisabled() throws Exception {
        ServerConfig serverConfig = serverConfigWithCsrf(false, false);

        ServerCommonTestSupport.testApplication(app -> {
            installPlugins(app, serverConfig);
            app.routing(route -> route.post("/api/v1/resource", scope -> {
                scope.getCall().respond(HttpStatusCode.Created, "created");
            }));

            try (Response response = app.client().post("/api/v1/resource")) {
                assertNotEquals(HttpStatusCode.Forbidden, ServerCommonTestSupport.status(response));
            }
        });
    }

    @Test
    void csrfPluginWithExemptPathAllowsPostWithoutHeader() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            new CsrfPlugin(Set.of("/api/v1/webhook")).install(app.app());
            app.routing(route -> {
                route.post("/api/v1/webhook", scope -> {
                    scope.getCall().respondText("ok");
                });
                route.post("/api/v1/protected", scope -> {
                    scope.getCall().respondText("ok");
                });
            });

            try (Response exempt = app.client().post("/api/v1/webhook")) {
                assertNotEquals(HttpStatusCode.Forbidden, ServerCommonTestSupport.status(exempt));
            }

            try (Response protectedPath = app.client().post("/api/v1/protected")) {
                assertEquals(HttpStatusCode.Forbidden, ServerCommonTestSupport.status(protectedPath));
            }
        });
    }

    private static ServerConfig serverConfigWithCsrf(boolean csrfEnabled, boolean authEnabled) {
        return new ServerConfig(
            "0.0.0.0",
            8080,
            List.of(),
            List.of(),
            new FfmpegConfig("ffmpeg", "ffprobe", "6.0", 2),
            new TranscodeConfig("both", 10, 6),
            new DatabaseConfig(Path.of("/tmp")),
            new ThumbnailConfig(),
            new AudioConfig(),
            new AuthConfig(authEnabled, authEnabled ? "token" : "", Map.of(), Map.of()),
            new RateLimitConfig(),
            new CsrfConfig(csrfEnabled),
            new TlsConfig(),
            new WebhookConfig(),
            new QuotaConfig(),
            new BackupConfig(),
            new StorageConfig()
        );
    }

    private static void installPlugins(ServerCommonTestSupport.ApplicationHarness app, ServerConfig serverConfig) {
        if (serverConfig.csrf().enabled() && !serverConfig.auth().enabled()) {
            new CsrfPlugin(Set.of("/api/v1/webhook")).install(app.app());
        }
    }
}
