package com.nyx.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.config.MediaRootConfig;
import com.nyx.config.QuotaConfig;
import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import com.nyx.http.Route;
import com.nyx.http.UserIdPrincipal;
import java.nio.file.Files;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

class RouteUtilsTest {
    @Test
    void getPageParamReturnsDefault1WhenNotSpecified() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            registerPageRoute(app);

            try (Response response = app.client().get("/test")) {
                assertEquals(HttpStatusCode.OK, ServerCommonTestSupport.status(response));
                assertEquals("1", ServerCommonTestSupport.bodyAsText(response));
            }
        });
    }

    @Test
    void getPageParamReturnsCustomValue() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            registerPageRoute(app);

            try (Response response = app.client().get("/test?page=5")) {
                assertEquals(HttpStatusCode.OK, ServerCommonTestSupport.status(response));
                assertEquals("5", ServerCommonTestSupport.bodyAsText(response));
            }
        });
    }

    @Test
    void getPageParamThrowsForPageLessThan1() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            registerPageRoute(app);

            try (Response response = app.client().get("/test?page=0")) {
                assertEquals(HttpStatusCode.BadRequest, ServerCommonTestSupport.status(response));
                String body = ServerCommonTestSupport.bodyAsText(response);
                assertTrue(body.contains("INVALID_REQUEST"));
                assertTrue(body.contains("Page must be between 1 and"));
            }
        });
    }

    @Test
    void getPageParamTreatsNonNumericAsDefault() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            registerPageRoute(app);

            try (Response response = app.client().get("/test?page=abc")) {
                assertEquals(HttpStatusCode.OK, ServerCommonTestSupport.status(response));
                assertEquals("1", ServerCommonTestSupport.bodyAsText(response));
            }
        });
    }

    @Test
    void getLimitParamReturnsDefault50WhenNotSpecified() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            registerLimitRoute(app);

            try (Response response = app.client().get("/test")) {
                assertEquals(HttpStatusCode.OK, ServerCommonTestSupport.status(response));
                assertEquals("50", ServerCommonTestSupport.bodyAsText(response));
            }
        });
    }

    @Test
    void getLimitParamReturnsCustomValue() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            registerLimitRoute(app);

            try (Response response = app.client().get("/test?limit=25")) {
                assertEquals(HttpStatusCode.OK, ServerCommonTestSupport.status(response));
                assertEquals("25", ServerCommonTestSupport.bodyAsText(response));
            }
        });
    }

    @Test
    void getLimitParamThrowsForLimitLessThan1() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            registerLimitRoute(app);

            try (Response response = app.client().get("/test?limit=0")) {
                assertEquals(HttpStatusCode.BadRequest, ServerCommonTestSupport.status(response));
                String body = ServerCommonTestSupport.bodyAsText(response);
                assertTrue(body.contains("INVALID_REQUEST"));
                assertTrue(body.contains("Limit must be between 1 and 200"));
            }
        });
    }

    @Test
    void getLimitParamThrowsForLimitGreaterThanMax() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            registerLimitRoute(app);

            try (Response response = app.client().get("/test?limit=201")) {
                assertEquals(HttpStatusCode.BadRequest, ServerCommonTestSupport.status(response));
                String body = ServerCommonTestSupport.bodyAsText(response);
                assertTrue(body.contains("INVALID_REQUEST"));
                assertTrue(body.contains("Limit must be between 1 and 200"));
            }
        });
    }

    @Test
    void getLimitParamAllowsMaxValueOf200() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            registerLimitRoute(app);

            try (Response response = app.client().get("/test?limit=200")) {
                assertEquals(HttpStatusCode.OK, ServerCommonTestSupport.status(response));
                assertEquals("200", ServerCommonTestSupport.bodyAsText(response));
            }
        });
    }

    @Test
    void getLimitParamTreatsNonNumericAsDefault() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            registerLimitRoute(app);

            try (Response response = app.client().get("/test?limit=abc")) {
                assertEquals(HttpStatusCode.OK, ServerCommonTestSupport.status(response));
                assertEquals("50", ServerCommonTestSupport.bodyAsText(response));
            }
        });
    }

    @Test
    void getRequiredParamReturnsValueWhenPresent() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            registerRequiredParamRoute(app);

            try (Response response = app.client().get("/test?name=hello")) {
                assertEquals(HttpStatusCode.OK, ServerCommonTestSupport.status(response));
                assertEquals("hello", ServerCommonTestSupport.bodyAsText(response));
            }
        });
    }

    @Test
    void getRequiredParamThrowsWhenMissing() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            registerRequiredParamRoute(app);

            try (Response response = app.client().get("/test")) {
                assertEquals(HttpStatusCode.BadRequest, ServerCommonTestSupport.status(response));
                String body = ServerCommonTestSupport.bodyAsText(response);
                assertTrue(body.contains("INVALID_REQUEST"));
                assertTrue(body.contains("Missing required parameter: name"));
            }
        });
    }

    @Test
    void getRequiredPathParamReturnsValueWhenPresent() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            app.routing(route -> route.get("/test/{id}", scope -> {
                String value = RouteUtilsJava.getRequiredPathParam(scope.getCall(), "id");
                scope.getCall().respondText(value);
            }));

            try (Response response = app.client().get("/test/42")) {
                assertEquals(HttpStatusCode.OK, ServerCommonTestSupport.status(response));
                assertEquals("42", ServerCommonTestSupport.bodyAsText(response));
            }
        });
    }

    @Test
    void getRequiredPathParamThrowsWhenMissing() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            app.routing(route -> route.get("/test", scope -> {
                String value = RouteUtilsJava.getRequiredPathParam(scope.getCall(), "id");
                scope.getCall().respondText(value);
            }));

            try (Response response = app.client().get("/test")) {
                assertEquals(HttpStatusCode.BadRequest, ServerCommonTestSupport.status(response));
                String body = ServerCommonTestSupport.bodyAsText(response);
                assertTrue(body.contains("INVALID_REQUEST"));
                assertTrue(body.contains("Missing required path parameter: id"));
            }
        });
    }

    @Test
    void getLimitParamWithNegativeValueThrows() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            registerLimitRoute(app);

            try (Response response = app.client().get("/test?limit=-5")) {
                assertEquals(HttpStatusCode.BadRequest, ServerCommonTestSupport.status(response));
                assertTrue(ServerCommonTestSupport.bodyAsText(response).contains("INVALID_REQUEST"));
            }
        });
    }

    @Test
    void getPageParamWithNegativeValueThrows() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            registerPageRoute(app);

            try (Response response = app.client().get("/test?page=-1")) {
                assertEquals(HttpStatusCode.BadRequest, ServerCommonTestSupport.status(response));
                assertTrue(ServerCommonTestSupport.bodyAsText(response).contains("INVALID_REQUEST"));
            }
        });
    }

    @Test
    void getPageParamThrowsForPageExceedingMaxPage() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            registerPageRoute(app);

            try (Response response = app.client().get("/test?page=" + (RouteUtilsJava.MAX_PAGE + 1))) {
                assertEquals(HttpStatusCode.BadRequest, ServerCommonTestSupport.status(response));
                String body = ServerCommonTestSupport.bodyAsText(response);
                assertTrue(body.contains("INVALID_REQUEST"));
                assertTrue(body.contains("Page must be between 1 and " + RouteUtilsJava.MAX_PAGE));
            }
        });
    }

    @Test
    void getPageParamAcceptsMaxPage() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            registerPageRoute(app);

            try (Response response = app.client().get("/test?page=" + RouteUtilsJava.MAX_PAGE)) {
                assertEquals(HttpStatusCode.OK, ServerCommonTestSupport.status(response));
                assertEquals(Integer.toString(RouteUtilsJava.MAX_PAGE), ServerCommonTestSupport.bodyAsText(response));
            }
        });
    }

    @Test
    void resolveDirParamReturnsAbsolutePathForValidDirectory() throws Exception {
        var tempDir = Files.createTempDirectory("nyx-route-utils-dir-test");
        try {
            var dataRoot = Files.createDirectories(tempDir.resolve("data"));
            var subDir = Files.createDirectories(dataRoot.resolve("docs"));
            var resolver = new VirtualPathResolver(java.util.List.of(new MediaRootConfig(dataRoot)));
            var pathSecurity = new PathSecurity(java.util.List.of(dataRoot));

            var result = RouteUtilsJava.resolveDirParam("data/docs", pathSecurity, resolver);
            assertEquals(subDir.toRealPath(), result.toRealPath());
        } finally {
            ServerCommonTestSupport.deleteRecursively(tempDir);
        }
    }

    @Test
    void resolvePathParamReturnsAbsoluteFilePathForValidFile() throws Exception {
        var tempDir = Files.createTempDirectory("nyx-route-utils-file-test");
        try {
            var dataRoot = Files.createDirectories(tempDir.resolve("data"));
            var file = dataRoot.resolve("movie.mkv");
            Files.writeString(file, "media");
            var resolver = new VirtualPathResolver(java.util.List.of(new MediaRootConfig(dataRoot)));
            var pathSecurity = new PathSecurity(java.util.List.of(dataRoot));

            var result = RouteUtilsJava.resolvePathParam("data/movie.mkv", pathSecurity, resolver);
            assertEquals(file.toRealPath(), result.toRealPath());
        } finally {
            ServerCommonTestSupport.deleteRecursively(tempDir);
        }
    }

    @Test
    void paginationHelpersClampConsistently() {
        assertEquals(40L, RouteUtilsJava.pageOffset(3, 20));
        assertEquals(90, RouteUtilsJava.pageStartIndex(10, 10, 90));
        assertEquals(90, RouteUtilsJava.pageEndIndex(85, 10, 90));
    }

    @Test
    void enforceUserRateLimitRejectsOverLimitButNoOpsWithoutAuthContext() throws Exception {
        QuotaService quotaService = new QuotaService(
            new QuotaConfig(true, 4, 1, 10_737_418_240L, java.util.Map.of()),
            ignored -> 0
        );

        ServerCommonTestSupport.testApplication(app -> {
            Route route = new Route(app.app(), (call, authMode, authProviders) -> {
                if ("Bearer alice".equals(call.getRequest().getHeaders().get(HttpHeaders.Authorization))) {
                    call.attribute(Route.AUTH_PRINCIPAL_ATTRIBUTE, new UserIdPrincipal("alice"));
                }
            });

            RouteUtilsJava.optionalAuth(route, java.util.List.of("bearer"), optionalRoute -> {
                optionalRoute.get("/optional-rate-limit", scope -> {
                    RouteUtilsJava.enforceUserRateLimit(scope.getCall(), null);
                    scope.getCall().respondText("optional");
                });
            });

            RouteUtilsJava.requireAuth(route, java.util.List.of("bearer"), requiredRoute -> {
                requiredRoute.get("/limited", scope -> {
                    RouteUtilsJava.enforceUserRateLimit(scope.getCall(), quotaService);
                    scope.getCall().respondText("ok");
                });
            });

            try (Response optional = app.client().get("/optional-rate-limit")) {
                assertEquals(HttpStatusCode.OK, ServerCommonTestSupport.status(optional));
                assertEquals("optional", ServerCommonTestSupport.bodyAsText(optional));
            }

            try (Response first = app.client().get("/limited", builder ->
                builder.header(HttpHeaders.Authorization, "Bearer alice"))) {
                assertEquals(HttpStatusCode.OK, ServerCommonTestSupport.status(first));
            }

            try (Response second = app.client().get("/limited", builder ->
                builder.header(HttpHeaders.Authorization, "Bearer alice"))) {
                assertEquals(HttpStatusCode.TooManyRequests, ServerCommonTestSupport.status(second));
                assertTrue(ServerCommonTestSupport.bodyAsText(second).contains("QUOTA_EXCEEDED"));
            }
        });
    }

    private static void registerPageRoute(ServerCommonTestSupport.ApplicationHarness app) {
        app.routing(route -> route.get("/test", scope -> {
            int page = RouteUtilsJava.getPageParam(scope.getCall(), 1);
            scope.getCall().respondText(Integer.toString(page));
        }));
    }

    private static void registerLimitRoute(ServerCommonTestSupport.ApplicationHarness app) {
        app.routing(route -> route.get("/test", scope -> {
            int limit = RouteUtilsJava.getLimitParam(scope.getCall(), 50, 200);
            scope.getCall().respondText(Integer.toString(limit));
        }));
    }

    private static void registerRequiredParamRoute(ServerCommonTestSupport.ApplicationHarness app) {
        app.routing(route -> route.get("/test", scope -> {
            String value = RouteUtilsJava.getRequiredParam(scope.getCall(), "name");
            scope.getCall().respondText(value);
        }));
    }
}
