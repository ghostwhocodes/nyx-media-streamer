package com.nyx.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import okhttp3.Response;
import org.junit.jupiter.api.Test;

class RateLimitPluginTest {
    @Test
    void allowRequestPermitsRequestsUpToBurstLimit() {
        RateLimitPlugin plugin = new RateLimitPlugin(rateLimitConfig(10, 5));

        for (int i = 0; i < 5; i++) {
            assertTrue(plugin.allowRequest("127.0.0.1"), "Request " + (i + 1) + " should be allowed");
        }
    }

    @Test
    void allowRequestRejectsRequestBeyondBurstLimit() {
        RateLimitPlugin plugin = new RateLimitPlugin(rateLimitConfig(10, 3));

        for (int i = 0; i < 3; i++) {
            plugin.allowRequest("127.0.0.1");
        }
        assertFalse(plugin.allowRequest("127.0.0.1"), "4th request should be rate-limited");
    }

    @Test
    void allowRequestTracksIpsIndependently() {
        RateLimitPlugin plugin = new RateLimitPlugin(rateLimitConfig(10, 2));

        assertTrue(plugin.allowRequest("1.1.1.1"));
        assertTrue(plugin.allowRequest("1.1.1.1"));
        assertFalse(plugin.allowRequest("1.1.1.1"));

        assertTrue(plugin.allowRequest("2.2.2.2"));
        assertTrue(plugin.allowRequest("2.2.2.2"));
        assertFalse(plugin.allowRequest("2.2.2.2"));
    }

    @Test
    void allowRequestIsDisabledWhenConfigEnabledFalse() {
        RateLimitPlugin plugin = new RateLimitPlugin(new RateLimitConfig(false, 1, 1L, 1));

        assertTrue(plugin.allowRequest("127.0.0.1"));
        assertFalse(plugin.allowRequest("127.0.0.1"));
    }

    @Test
    void rateLimiterReturns429AfterBurstIsExhausted() throws Exception {
        ServerConfig config = new ServerConfig(
            "0.0.0.0",
            8080,
            List.of("*"),
            List.of(),
            new FfmpegConfig("", "", "", 4),
            new TranscodeConfig("", 1, 1),
            new DatabaseConfig(Path.of("/tmp")),
            new ThumbnailConfig(),
            new AudioConfig(),
            new AuthConfig(),
            new RateLimitConfig(true, 1, 1_000L, 1),
            new CsrfConfig(),
            new TlsConfig(),
            new WebhookConfig(),
            new QuotaConfig(),
            new BackupConfig(),
            new StorageConfig()
        );

        ServerCommonTestSupport.testApplication(app -> {
            installPlugins(app, config);
            app.routing(route -> route.get("/api/v1/test", scope -> {
                scope.getCall().respondText("ok");
            }));

            try (Response response = app.client().get("/api/v1/test")) {
                assertEquals(HttpStatusCode.OK, ServerCommonTestSupport.status(response), "Request 1 should succeed");
            }

            try (Response limited = app.client().get("/api/v1/test")) {
                assertEquals(HttpStatusCode.TooManyRequests, ServerCommonTestSupport.status(limited));
                assertTrue(ServerCommonTestSupport.bodyAsText(limited).contains("RATE_LIMITED"));
            }
        });
    }

    @Test
    void healthEndpointIsExemptFromRateLimiting() throws Exception {
        ServerConfig config = serverConfig(1, 1, rateLimitConfig(1, 1));

        ServerCommonTestSupport.testApplication(app -> {
            installPlugins(app, config);
            app.routing(route -> route.get("/api/v1/health", scope -> {
                scope.getCall().respondText("ok");
            }));

            for (int i = 0; i < 5; i++) {
                try (Response response = app.client().get("/api/v1/health")) {
                    assertEquals(HttpStatusCode.OK, ServerCommonTestSupport.status(response), "Health check " + (i + 1) + " should succeed");
                }
            }
        });
    }

    @Test
    void metricsEndpointIsExemptFromRateLimiting() throws Exception {
        ServerConfig config = serverConfig(1, 1, rateLimitConfig(1, 1));

        ServerCommonTestSupport.testApplication(app -> {
            installPlugins(app, config);
            app.routing(route -> route.get("/api/v1/metrics", scope -> {
                scope.getCall().respondText("# metrics");
            }));

            for (int i = 0; i < 5; i++) {
                try (Response response = app.client().get("/api/v1/metrics")) {
                    assertEquals(HttpStatusCode.OK, ServerCommonTestSupport.status(response), "Metrics request " + (i + 1) + " should succeed");
                }
            }
        });
    }

    @Test
    void rateLimiterNotAppliedWhenDisabled() throws Exception {
        ServerConfig config = serverConfig(5, 5, new RateLimitConfig());

        ServerCommonTestSupport.testApplication(app -> {
            installPlugins(app, config);
            app.routing(route -> route.get("/api/v1/test", scope -> {
                scope.getCall().respondText("ok");
            }));

            for (int i = 0; i < 20; i++) {
                try (Response response = app.client().get("/api/v1/test")) {
                    assertNotEquals(HttpStatusCode.TooManyRequests, ServerCommonTestSupport.status(response));
                }
            }
        });
    }

    @Test
    void rateLimitPluginDefaultExemptPathsAreHealthAndMetrics() {
        var paths = RateLimitPlugin.DEFAULT_EXEMPT_PATHS;

        assertTrue(paths.contains("/api/v1/health"));
        assertTrue(paths.contains("/api/v1/metrics"));
    }

    @Test
    void allowRequestPrunesExpiredTimestampsAndAllowsNewRequests() throws Exception {
        RateLimitPlugin plugin = new RateLimitPlugin(new RateLimitConfig(true, 2, 1L, 10));

        assertTrue(plugin.allowRequest("192.168.1.1"));
        assertTrue(plugin.allowRequest("192.168.1.1"));
        Thread.sleep(1_100L);
        assertTrue(plugin.allowRequest("192.168.1.1"), "Request after window expiry should be allowed");
    }

    private static RateLimitConfig rateLimitConfig(int rps, int burst) {
        return new RateLimitConfig(true, rps, 1L, burst);
    }

    private static ServerConfig serverConfig(int rps, int burst, RateLimitConfig rateLimit) {
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
            new AuthConfig(),
            rateLimit,
            new CsrfConfig(),
            new TlsConfig(),
            new WebhookConfig(),
            new QuotaConfig(),
            new BackupConfig(),
            new StorageConfig()
        );
    }

    private static void installPlugins(ServerCommonTestSupport.ApplicationHarness app, ServerConfig serverConfig) {
        if (serverConfig.rateLimit().enabled()) {
            new RateLimitPlugin(serverConfig.rateLimit()).install(app.app());
        }
    }
}
