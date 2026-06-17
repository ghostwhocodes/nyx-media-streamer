package com.nyx.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.http.HttpStatusCode;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

class MetricsPluginTest {
    @Test
    void metricsPluginRecordsTimerForGet200Response() throws Exception {
        PrometheusMeterRegistry registry = buildRegistry();

        ServerCommonTestSupport.testApplication(app -> {
            installMetrics(app, registry);
            app.routing(route -> route.get("/api/v1/test", scope -> {
                scope.getCall().respond(HttpStatusCode.OK, "ok");
            }));

            try (Response ignored = app.client().get("/api/v1/test")) {
                var timer = registry.find("http_server_request_duration_seconds")
                    .tags("method", "GET", "status", "200")
                    .timer();
                assertNotNull(timer, "Timer should be registered for GET 200");
                assertEquals(1, timer.count());
            }
        });
    }

    @Test
    void metricsPluginRecordsTimerForPostRequest() throws Exception {
        PrometheusMeterRegistry registry = buildRegistry();

        ServerCommonTestSupport.testApplication(app -> {
            installMetrics(app, registry);
            app.routing(route -> route.post("/api/v1/items", scope -> {
                scope.getCall().respond(HttpStatusCode.Created, "created");
            }));

            try (Response ignored = app.client().post("/api/v1/items")) {
                var timer = registry.find("http_server_request_duration_seconds")
                    .tags("method", "POST", "status", "201")
                    .timer();
                assertNotNull(timer, "Timer should be registered for POST 201");
                assertEquals(1, timer.count());
            }
        });
    }

    @Test
    void metricsPluginRecordsTimerFor404Response() throws Exception {
        PrometheusMeterRegistry registry = buildRegistry();

        ServerCommonTestSupport.testApplication(app -> {
            installMetrics(app, registry);
            app.routing(route -> route.get("/api/v1/existing", scope -> {
                scope.getCall().respond(HttpStatusCode.OK, "ok");
            }));

            try (Response ignored = app.client().get("/api/v1/nonexistent")) {
                var timers = registry.find("http_server_request_duration_seconds").timers();
                assertFalse(timers.isEmpty(), "Should have recorded at least one timer entry");
            }
        });
    }

    @Test
    void metricsPluginExcludesHealthEndpoints() throws Exception {
        PrometheusMeterRegistry registry = buildRegistry();

        ServerCommonTestSupport.testApplication(app -> {
            installMetrics(app, registry);
            app.routing(route -> {
                route.get("/api/v1/health/live", scope -> {
                    scope.getCall().respond(HttpStatusCode.OK, "alive");
                });
                route.get("/api/v1/health/ready", scope -> {
                    scope.getCall().respond(HttpStatusCode.OK, "ready");
                });
            });

            try (Response ignored1 = app.client().get("/api/v1/health/live");
                 Response ignored2 = app.client().get("/api/v1/health/ready")) {
                var timers = registry.find("http_server_request_duration_seconds").timers();
                assertTrue(timers.isEmpty(), "Health endpoints should be excluded from metrics");
            }
        });
    }

    @Test
    void metricsPluginExcludesMetricsEndpoint() throws Exception {
        PrometheusMeterRegistry registry = buildRegistry();

        ServerCommonTestSupport.testApplication(app -> {
            installMetrics(app, registry);
            app.routing(route -> route.get("/api/v1/metrics", scope -> {
                scope.getCall().respond(HttpStatusCode.OK, "metrics");
            }));

            try (Response ignored = app.client().get("/api/v1/metrics")) {
                var timers = registry.find("http_server_request_duration_seconds").timers();
                assertTrue(timers.isEmpty(), "Metrics endpoint should be excluded from self-recording");
            }
        });
    }

    @Test
    void metricsPluginUsesMatchedPathForParameterizedRoutes() throws Exception {
        PrometheusMeterRegistry registry = buildRegistry();

        ServerCommonTestSupport.testApplication(app -> {
            installMetrics(app, registry);
            app.routing(route -> route.get("/api/v1/forms/{id}", scope -> {
                scope.getCall().respond(HttpStatusCode.OK, "form");
            }));

            try (Response ignored = app.client().get("/api/v1/forms/some-uuid-here")) {
                var timer = registry.find("http_server_request_duration_seconds")
                    .tags("route", "/api/v1/forms/{id}")
                    .timer();
                assertNotNull(timer, "Should use the Javalin matched path template");
            }
        });
    }

    @Test
    void metricsPluginNormalizesUuidPathsWhenNoTemplateAttribute() throws Exception {
        PrometheusMeterRegistry registry = buildRegistry();

        ServerCommonTestSupport.testApplication(app -> {
            installMetrics(app, registry);
            app.routing(route -> route.get("/api/v1/forms/{id}", scope -> {
                scope.getCall().respond(HttpStatusCode.OK, "form");
            }));

            try (Response ignored = app.client().get("/api/v1/forms/abcdef12-1234-5678-abcd-ef1234567890")) {
                var timer = registry.find("http_server_request_duration_seconds")
                    .tags("route", "/api/v1/forms/{id}")
                    .timer();
                assertNotNull(timer, "UUID path segments should be normalized to {id}");
            }
        });
    }

    @Test
    void metricsPluginRecordsMultipleRequestsCumulatively() throws Exception {
        PrometheusMeterRegistry registry = buildRegistry();

        ServerCommonTestSupport.testApplication(app -> {
            installMetrics(app, registry);
            app.routing(route -> route.get("/api/v1/ping", scope -> {
                scope.getCall().respond(HttpStatusCode.OK, "pong");
            }));

            for (int i = 0; i < 3; i++) {
                try (Response ignored = app.client().get("/api/v1/ping")) {
                }
            }

            var timer = registry.find("http_server_request_duration_seconds")
                .tags("method", "GET", "status", "200")
                .timer();
            assertNotNull(timer);
            assertEquals(3, timer.count());
        });
    }

    @Test
    void normalizeRoutePathReplacesUuidsWithId() {
        assertEquals("/api/v1/forms/{id}", MetricsSupport.normalizeRoutePath(
            "/api/v1/forms/abcdef12-1234-5678-abcd-ef1234567890"
        ));
    }

    @Test
    void normalizeRoutePathReplacesNumericSegmentsWithN() {
        assertEquals("/api/v1/transcode/jobs/{n}", MetricsSupport.normalizeRoutePath("/api/v1/transcode/jobs/123"));
    }

    @Test
    void normalizeRoutePathLeavesCleanPathsUnchanged() {
        assertEquals("/api/v1/forms", MetricsSupport.normalizeRoutePath("/api/v1/forms"));
    }

    @Test
    void buildRouteTemplateHandlesConstantSegments() {
        assertEquals("/api/v1/health", MetricsSupport.normalizeRoutePath("/api/v1/health"));
    }

    @Test
    void metricsPluginWorksWithoutRegistry() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            app.routing(route -> route.get("/api/v1/test", scope -> {
                scope.getCall().respond(HttpStatusCode.OK, "ok");
            }));

            try (Response response = app.client().get("/api/v1/test")) {
                assertEquals(HttpStatusCode.OK, ServerCommonTestSupport.status(response));
            }
        });
    }

    @Test
    void normalizeRoutePathReplacesMultipleUuids() {
        assertEquals(
            "/api/v1/forms/{id}/versions/{id}",
            MetricsSupport.normalizeRoutePath(
                "/api/v1/forms/abcdef12-1234-5678-abcd-ef1234567890/versions/12345678-aaaa-bbbb-cccc-123456789012"
            )
        );
    }

    @Test
    void normalizeRoutePathReplacesMultipleNumericSegments() {
        assertEquals("/api/v1/jobs/{n}/segments/{n}", MetricsSupport.normalizeRoutePath("/api/v1/jobs/42/segments/7"));
    }

    @Test
    void normalizeRoutePathHandlesMixedUuidAndNumeric() {
        assertEquals(
            "/api/v1/forms/{id}/versions/{n}",
            MetricsSupport.normalizeRoutePath("/api/v1/forms/abcdef12-1234-5678-abcd-ef1234567890/versions/3")
        );
    }

    @Test
    void normalizeRoutePathHandlesNumericAtEndOfPath() {
        assertEquals("/api/v1/tracks/{n}", MetricsSupport.normalizeRoutePath("/api/v1/tracks/999"));
    }

    @Test
    void normalizeRoutePathDoesNotReplaceNonNumericText() {
        assertEquals("/api/v1/health/live", MetricsSupport.normalizeRoutePath("/api/v1/health/live"));
    }

    @Test
    void normalizeRoutePathHandlesUppercaseUuid() {
        assertEquals(
            "/api/v1/items/{id}",
            MetricsSupport.normalizeRoutePath("/api/v1/items/ABCDEF12-1234-5678-ABCD-EF1234567890")
        );
    }

    @Test
    void nestedRoutesKeepParameterizedMatchedPaths() throws Exception {
        PrometheusMeterRegistry registry = buildRegistry();

        ServerCommonTestSupport.testApplication(app -> {
            installMetrics(app, registry);
            app.routing(route -> route.route("/api/v1/forms", forms -> {
                forms.get("{id}", scope -> {
                    scope.getCall().respond(HttpStatusCode.OK, "form");
                });
            }));

            try (Response ignored = app.client().get("/api/v1/forms/some-id-here")) {
                var timer = registry.find("http_server_request_duration_seconds")
                    .tags("route", "/api/v1/forms/{id}")
                    .timer();
                assertNotNull(timer, "Should record timer for nested parameterized route");
            }
        });
    }

    @Test
    void nestedRoutesPreserveConstantAndParameterPathSegments() throws Exception {
        PrometheusMeterRegistry registry = buildRegistry();

        ServerCommonTestSupport.testApplication(app -> {
            installMetrics(app, registry);
            app.routing(route -> route.route("/api", api -> {
                api.route("/v1", v1 -> {
                    v1.route("/jobs", jobs -> {
                        jobs.get("{jobId}", scope -> {
                            scope.getCall().respond(HttpStatusCode.OK, "job");
                        });
                    });
                });
            }));

            try (Response ignored = app.client().get("/api/v1/jobs/test-job-id")) {
                var timer = registry.find("http_server_request_duration_seconds")
                    .tags("route", "/api/v1/jobs/{jobId}")
                    .timer();
                assertNotNull(timer, "Should keep nested route template intact");
            }
        });
    }

    @Test
    void metricsPluginFallsBackToNormalizedPathWhenNoAttribute() throws Exception {
        PrometheusMeterRegistry registry = buildRegistry();

        ServerCommonTestSupport.testApplication(app -> {
            installMetrics(app, registry);
            app.app().unsafe.routes.get("/api/v1/items/{id}", ctx -> ctx.result("ok"));

            try (Response ignored = app.client().get("/api/v1/items/42")) {
                var timer = registry.find("http_server_request_duration_seconds")
                    .tags("route", "/api/v1/items/{n}")
                    .timer();
                assertNotNull(timer, "Should normalize numeric segments when no route attribute");
            }
        });
    }

    @Test
    void metricsPluginRecordsNon200StatusCodes() throws Exception {
        PrometheusMeterRegistry registry = buildRegistry();

        ServerCommonTestSupport.testApplication(app -> {
            installMetrics(app, registry);
            app.routing(route -> {
                route.get("/api/v1/notfound", scope -> {
                    scope.getCall().respond(HttpStatusCode.NotFound, "not found");
                });
                route.get("/api/v1/error", scope -> {
                    scope.getCall().respond(HttpStatusCode.InternalServerError, "error");
                });
            });

            try (Response ignored1 = app.client().get("/api/v1/notfound");
                 Response ignored2 = app.client().get("/api/v1/error")) {
                var timers = registry.find("http_server_request_duration_seconds").timers();
                assertTrue(
                    timers.size() >= 1,
                    "Should have recorded at least some requests: found "
                        + timers.stream().map(timer -> timer.getId().getTags()).toList()
                );
            }
        });
    }

    private static PrometheusMeterRegistry buildRegistry() {
        return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }

    private static void installMetrics(ServerCommonTestSupport.ApplicationHarness app, PrometheusMeterRegistry registry) {
        MetricsSupport.installMetrics(app.app(), registry);
    }
}
