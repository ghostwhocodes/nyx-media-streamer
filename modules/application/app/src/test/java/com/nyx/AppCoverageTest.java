package com.nyx;

import com.nyx.config.AudioConfig;
import com.nyx.config.BackupConfig;
import com.nyx.config.CompatibilityConfig;
import com.nyx.config.CsrfConfig;
import com.nyx.config.DatabaseConfig;
import com.nyx.config.FfmpegConfig;
import com.nyx.config.MediaRootConfig;
import com.nyx.config.QuotaConfig;
import com.nyx.config.QloudCompatibilityConfig;
import com.nyx.config.RateLimitConfig;
import com.nyx.config.ServerConfig;
import com.nyx.config.StorageConfig;
import com.nyx.config.ThumbnailConfig;
import com.nyx.config.TlsConfig;
import com.nyx.config.WebhookConfig;
import com.nyx.http.ContentType;
import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppCoverageTest {
    @Test
    void createConfiguredAppConvenienceOverloadsReturnUsableRuntime() {
        ServerConfig config = testServerConfig();
        ConcurrentHashMap<String, String> runtimeUsers = new ConcurrentHashMap<>(config.getAuth().getUsers());
        PrometheusMeterRegistry metricsRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        var withAuthAndMetrics = ApplicationRuntime.createConfiguredApp(config, config.getAuth(), metricsRegistry);
        var withServerConfigOnly = ApplicationRuntime.createConfiguredApp(config);
        var withRuntimeUsers = ApplicationRuntime.createConfiguredApp(config, runtimeUsers);

        assertNotNull(withAuthAndMetrics.app());
        assertNotNull(withAuthAndMetrics.authEvaluator());
        assertNotNull(withServerConfigOnly.app());
        assertNotNull(withServerConfigOnly.authEvaluator());
        assertNotNull(withRuntimeUsers.app());
        assertNotNull(withRuntimeUsers.authEvaluator());
    }

    @Test
    void backgroundExecutorUsesDaemonServiceWorkerThreads() throws Exception {
        ExecutorService executor = AppCompositionModule.provideBackgroundExecutor();
        try {
            Future<Thread> workerFuture = executor.submit(Thread::currentThread);
            Thread worker = workerFuture.get(5, TimeUnit.SECONDS);

            assertEquals("nyx-service-worker", worker.getName());
            assertTrue(worker.isDaemon());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void configureRoutingServesOperationalEndpointsIncludingBackupAwareHealthAndSwaggerSlash() throws Exception {
        Path tempDir = Files.createTempDirectory("nyx-app-coverage");
        try {
            Path mediaDir = Files.createDirectories(tempDir.resolve("media"));
            Path backupDir = Files.createDirectories(tempDir.resolve("backups"));
            Path dbDir = Files.createDirectories(tempDir.resolve("db"));
            ServerConfig config = testServerConfig(
                "127.0.0.1",
                8080,
                List.of(),
                List.of(new MediaRootConfig(mediaDir, "local")),
                testFfmpegConfig(),
                testTranscodeConfig(),
                new DatabaseConfig(dbDir),
                new ThumbnailConfig(),
                new AudioConfig(),
                testAuthConfig(),
                new RateLimitConfig(),
                new CsrfConfig(),
                new TlsConfig(),
                new WebhookConfig(),
                new QuotaConfig(),
                new BackupConfig(true, backupDir.toString(), 0, 5),
                new StorageConfig()
            );
            ConcurrentHashMap<String, String> runtimeUsers = new ConcurrentHashMap<>(config.getAuth().getUsers());

            testApplication(app -> {
                app.configureRouting(config, runtimeUsers);

                var health = app.client().get("/api/v1/health");
                assertEquals(HttpStatusCode.OK, status(health));
                String healthBody = bodyAsText(health);
                assertTrue(healthBody.contains("lastBackupTimestamp"));
                assertTrue(healthBody.contains("lastBackupBytes"));
                assertTrue(healthBody.contains("serverVersion"));
                assertTrue(healthBody.contains("build"));

                var capabilities = app.client().get("/api/v1/client/capabilities");
                assertEquals(HttpStatusCode.OK, status(capabilities));
                String capabilitiesBody = bodyAsText(capabilities);
                assertTrue(capabilitiesBody.contains("authEnabled"));
                assertTrue(capabilitiesBody.contains("supportedPlaybackQualities"));
                assertTrue(capabilitiesBody.contains("supportedThumbnailSizes"));
                assertTrue(capabilitiesBody.contains("/api/v1/stream.m3u8?path={path}&quality={quality}"));

                var live = app.client().get("/api/v1/health/live");
                assertEquals(HttpStatusCode.OK, status(live));

                var ready = app.client().get("/api/v1/health/ready");
                HttpStatusCode readinessStatus = status(ready);
                assertTrue(
                    readinessStatus == HttpStatusCode.OK || readinessStatus == HttpStatusCode.ServiceUnavailable
                );

                var metrics = app.client().get("/api/v1/metrics");
                assertEquals(HttpStatusCode.OK, status(metrics));
                ContentType metricsType = contentType(metrics);
                assertNotNull(metricsType);
                assertTrue(metricsType.match(ContentType.Text.Plain));
                assertFalse(bodyAsText(metrics).isBlank());

                var swagger = app.client().get("/api/v1/swagger/");
                assertEquals(HttpStatusCode.OK, status(swagger));
                ContentType swaggerType = contentType(swagger);
                assertNotNull(swaggerType);
                assertTrue(swaggerType.match(ContentType.Text.Html));
                assertTrue(bodyAsText(swagger).contains("SwaggerUIBundle"));
            });
        } finally {
            deleteTree(tempDir);
        }
    }

    @Test
    void clientCapabilitiesReportsDefaultQualitiesAndQloudFeatureWhenCompatibilityIsEnabled() throws Exception {
        Path tempDir = Files.createTempDirectory("nyx-app-capabilities-coverage");
        try {
            Path mediaDir = Files.createDirectories(tempDir.resolve("media"));
            Path dbDir = Files.createDirectories(tempDir.resolve("db"));
            ServerConfig config = testServerConfig(
                "127.0.0.1",
                0,
                List.of(),
                List.of(new MediaRootConfig(mediaDir, "local")),
                testFfmpegConfig(
                    "ffmpeg",
                    "ffprobe",
                    "6.0",
                    2,
                    4,
                    8,
                    Map.of(),
                    "polling",
                    500L
                ),
                testTranscodeConfig(),
                new DatabaseConfig(dbDir),
                new ThumbnailConfig(),
                new AudioConfig(),
                testAuthConfig(),
                new RateLimitConfig(),
                new CsrfConfig(),
                new TlsConfig(),
                new WebhookConfig(),
                new QuotaConfig(),
                new BackupConfig(),
                new StorageConfig(),
                new CompatibilityConfig(new QloudCompatibilityConfig(true, "127.0.0.1", 0))
            );

            try (ApplicationServerGroup group = AppRouting.createApplicationServerGroup(
                config,
                new ConcurrentHashMap<>(config.getAuth().getUsers())
            )) {
                group.start();
                try (Response capabilities = get(
                    "http://127.0.0.1:" + group.mainApp().port(),
                    "/api/v1/client/capabilities"
                )) {
                    assertEquals(HttpStatusCode.OK, status(capabilities));
                    String body = bodyAsText(capabilities);
                    assertTrue(body.contains("\"qloudCompatibilityPort\":0"));
                    assertTrue(body.contains("qloud_compatibility"));
                    assertTrue(body.contains("low"));
                    assertTrue(body.contains("medium"));
                    assertTrue(body.contains("high"));
                }
            }
        } finally {
            deleteTree(tempDir);
        }
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

    private static Response get(String baseUrl, String path) throws IOException {
        return new OkHttpClient.Builder()
            .addInterceptor(chain -> chain.proceed(
                chain.request().newBuilder().header(HttpHeaders.AcceptEncoding, "identity").build()
            ))
            .build()
            .newCall(new Request.Builder().url(baseUrl + path).get().build())
            .execute();
    }
}
