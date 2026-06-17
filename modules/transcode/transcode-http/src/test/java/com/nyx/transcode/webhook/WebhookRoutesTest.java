package com.nyx.transcode.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.common.DatabaseResources;
import com.nyx.http.ContentType;
import com.nyx.http.HttpStatusCode;
import com.nyx.transcode.TranscodeHttpTestSupport;
import com.nyx.transcode.contracts.webhook.WebhookSubscription;
import com.nyx.transcode.contracts.webhook.WebhookUrlValidator;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebhookRoutesTest {
    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    private Path tempDir;
    private WebhookRepository repository;
    private HikariDataSource dataSource;
    private final WebhookUrlValidator urlValidator = new WebhookUrlValidator(Set.of("example.com", "other.com"));

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("nyx-webhook-routes-test");
        DatabaseResources resources = WebhookRepository.createDatabase(tempDir);
        dataSource = resources.getDataSource();
        repository = new WebhookRepository(resources.getJdbi());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (dataSource != null) {
            dataSource.close();
        }
        deleteRecursively(tempDir);
    }

    @Test
    void postWebhooksCreatesSubscription() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            installWebhookRoutes(app);

            try (Response response = app.client().post(
                "/api/v1/transcode/webhooks",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("""
                        {"url":"https://example.com/hook","events":["job.completed"],"secret":"s3cret"}
                        """);
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getCreated(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("example.com"));
                assertTrue(body.contains("job.completed"));
                assertFalse(body.contains("s3cret"));
            }
        });
    }

    @Test
    void postWebhooksRejectsNonHttpUrl() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            installWebhookRoutes(app);

            try (Response response = app.client().post(
                "/api/v1/transcode/webhooks",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("""
                        {"url":"ftp://example.com/hook","events":["job.completed"]}
                        """);
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getForbidden(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void postWebhooksRejectsInvalidEventTypes() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            installWebhookRoutes(app);

            try (Response response = app.client().post(
                "/api/v1/transcode/webhooks",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("""
                        {"url":"https://example.com/hook","events":["invalid.event"]}
                        """);
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), TranscodeHttpTestSupport.status(response));
                assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("invalid.event"));
            }
        });
    }

    @Test
    void postWebhooksRejectsEmptyEvents() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            installWebhookRoutes(app);

            try (Response response = app.client().post(
                "/api/v1/transcode/webhooks",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("""
                        {"url":"https://example.com/hook","events":[]}
                        """);
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getWebhooksReturnsList() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            installWebhookRoutes(app);

            try (Response ignored = app.client().post(
                "/api/v1/transcode/webhooks",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("""
                        {"url":"https://example.com/hook","events":["job.completed"]}
                        """);
                }
            )) {
                assertNotNull(ignored);
            }

            try (Response response = app.client().get("/api/v1/transcode/webhooks")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("example.com"));
            }
        });
    }

    @Test
    void getWebhooksByIdReturnsSubscriptionWithDeliveries() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            installWebhookRoutes(app);

            repository.createSubscription(new WebhookSubscription(
                "test-id",
                "https://example.com/hook",
                null,
                List.of("job.completed"),
                true,
                "2026-01-01T00:00:00Z",
                "2026-01-01T00:00:00Z"
            ));

            try (Response response = app.client().get("/api/v1/transcode/webhooks/test-id")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("test-id"));
                assertTrue(body.contains("recentDeliveries"));
            }
        });
    }

    @Test
    void getWebhooksByUnknownIdReturns404() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            installWebhookRoutes(app);

            try (Response response = app.client().get("/api/v1/transcode/webhooks/nonexistent")) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void deleteWebhooksRemovesSubscription() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            installWebhookRoutes(app);

            repository.createSubscription(new WebhookSubscription(
                "del-test",
                "https://example.com/hook",
                null,
                List.of("job.completed"),
                true,
                "2026-01-01T00:00:00Z",
                "2026-01-01T00:00:00Z"
            ));

            try (Response response = app.client().delete("/api/v1/transcode/webhooks/del-test")) {
                assertEquals(HttpStatusCode.Companion.getNoContent(), TranscodeHttpTestSupport.status(response));
            }

            try (Response response = app.client().get("/api/v1/transcode/webhooks/del-test")) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void deleteWebhooksReturns404ForUnknownId() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            installWebhookRoutes(app);

            try (Response response = app.client().delete("/api/v1/transcode/webhooks/nonexistent")) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void postWebhooksWithMultipleValidEventsSucceeds() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            installWebhookRoutes(app);

            try (Response response = app.client().post(
                "/api/v1/transcode/webhooks",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("""
                        {"url":"https://example.com/hook","events":["job.completed","job.failed","job.progress","job.retrying"]}
                        """);
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getCreated(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void postWebhooksGenerates12CharacterId() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            installWebhookRoutes(app);

            try (Response response = app.client().post(
                "/api/v1/transcode/webhooks",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("""
                        {"url":"https://example.com/hook","events":["job.completed"]}
                        """);
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getCreated(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                var matcher = ID_PATTERN.matcher(body);
                assertTrue(matcher.find());
                String id = matcher.group(1);
                assertEquals(12, id.length(), "Subscription ID should be 12 hex characters");
                assertTrue(id.chars().allMatch(ch -> Character.digit(ch, 16) >= 0), "ID should be hex characters");
            }
        });
    }

    @Test
    void postWebhooksRejectsSsrfLoopbackUrl() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            installWebhookRoutes(app);

            try (Response response = app.client().post(
                "/api/v1/transcode/webhooks",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("""
                        {"url":"http://127.0.0.1/hook","events":["job.completed"]}
                        """);
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getForbidden(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void patchUpdatesUrl() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            installWebhookRoutes(app);

            repository.createSubscription(new WebhookSubscription(
                "patch-1",
                "https://example.com/hook",
                null,
                List.of("job.completed"),
                true,
                "2026-01-01T00:00:00Z",
                "2026-01-01T00:00:00Z"
            ));

            try (Response response = app.client().patch(
                "/api/v1/transcode/webhooks/patch-1",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("""
                        {"url":"https://other.com/new-hook"}
                        """);
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("other.com/new-hook"));
            }
        });
    }

    @Test
    void patchTogglesIsActive() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            installWebhookRoutes(app);

            repository.createSubscription(new WebhookSubscription(
                "patch-2",
                "https://example.com/hook",
                null,
                List.of("job.completed"),
                true,
                "2026-01-01T00:00:00Z",
                "2026-01-01T00:00:00Z"
            ));

            try (Response response = app.client().patch(
                "/api/v1/transcode/webhooks/patch-2",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("""
                        {"isActive":false}
                        """);
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("\"isActive\":false"));
            }
        });
    }

    @Test
    void patchRejectsInvalidEvents() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            installWebhookRoutes(app);

            repository.createSubscription(new WebhookSubscription(
                "patch-3",
                "https://example.com/hook",
                null,
                List.of("job.completed"),
                true,
                "2026-01-01T00:00:00Z",
                "2026-01-01T00:00:00Z"
            ));

            try (Response response = app.client().patch(
                "/api/v1/transcode/webhooks/patch-3",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("""
                        {"events":["bad.event"]}
                        """);
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void patchRejectsEmptyEvents() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            installWebhookRoutes(app);

            repository.createSubscription(new WebhookSubscription(
                "patch-4",
                "https://example.com/hook",
                null,
                List.of("job.completed"),
                true,
                "2026-01-01T00:00:00Z",
                "2026-01-01T00:00:00Z"
            ));

            try (Response response = app.client().patch(
                "/api/v1/transcode/webhooks/patch-4",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("""
                        {"events":[]}
                        """);
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void patchReturns404ForUnknownId() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            installWebhookRoutes(app);

            try (Response response = app.client().patch(
                "/api/v1/transcode/webhooks/nonexistent",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("""
                        {"isActive":false}
                        """);
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void patchRejectsSsrfUrl() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            installWebhookRoutes(app);

            repository.createSubscription(new WebhookSubscription(
                "patch-5",
                "https://example.com/hook",
                null,
                List.of("job.completed"),
                true,
                "2026-01-01T00:00:00Z",
                "2026-01-01T00:00:00Z"
            ));

            try (Response response = app.client().patch(
                "/api/v1/transcode/webhooks/patch-5",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("""
                        {"url":"http://169.254.169.254/latest/meta-data"}
                        """);
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getForbidden(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    private void installWebhookRoutes(TranscodeHttpTestSupport.ApplicationHarness app) {
        app.routing(route -> WebhookRoutes.webhookRoutes(route, repository, urlValidator));
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (path == null || Files.notExists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(entry -> {
                try {
                    Files.deleteIfExists(entry);
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            });
        }
    }
}
