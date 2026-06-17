package com.nyx.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.common.ServerCommonTestSupport;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.sse.SseClient;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

class RouteTest {
    @Test
    void routingCallHandlesRequestAndResponseHelpers() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            Path tempDir = Files.createTempDirectory("nyx-route-test");
            Path file = Files.createFile(tempDir.resolve("payload.bin"));
            Files.writeString(file, "file-body");

            try {
                Route route = new Route(app.app());
                registerRouteHelpers(route, file);

                try (Response headerResponse = app.client().get("/headers?echo=hello", builder ->
                    builder.header(HttpHeaders.XRequestId, "req-1"))) {
                    assertEquals(HttpStatusCode.Accepted, ServerCommonTestSupport.status(headerResponse));
                    assertEquals(ContentType.Text.Plain.getValue(), ServerCommonTestSupport.contentType(headerResponse));
                    assertEquals("hello", headerResponse.header("X-Echo"));
                    String statusHeader = headerResponse.header("X-Status");
                    assertNotNull(statusHeader);
                    assertTrue(statusHeader.contains("202"));
                    assertEquals("req-1", ServerCommonTestSupport.bodyAsText(headerResponse));
                }

                try (Response jsonResponse = app.client().post("/json", builder -> {
                    builder.contentType(ContentType.Application.Json);
                    builder.setBody("{\"value\":\"created\"}");
                })) {
                    assertEquals(HttpStatusCode.Created, ServerCommonTestSupport.status(jsonResponse));
                    assertTrue(ServerCommonTestSupport.bodyAsText(jsonResponse).contains("created"));
                }

                try (Response textResponse = app.client().post("/text", builder -> {
                    builder.contentType(ContentType.Text.Plain);
                    builder.setBody("hello");
                })) {
                    assertEquals(HttpStatusCode.OK, ServerCommonTestSupport.status(textResponse));
                    assertEquals("hello:hello", ServerCommonTestSupport.bodyAsText(textResponse));
                }

                try (Response bytesResponse = app.client().post("/bytes", builder -> {
                    builder.contentType(ContentType.Application.OctetStream);
                    builder.setBody("bytes".getBytes(StandardCharsets.UTF_8));
                })) {
                    assertEquals(HttpStatusCode.OK, ServerCommonTestSupport.status(bytesResponse));
                    assertEquals(ContentType.Application.OctetStream.getValue(), ServerCommonTestSupport.contentType(bytesResponse));
                    assertEquals("bytes", ServerCommonTestSupport.bodyAsText(bytesResponse));
                }

                try (Response invalidResponse = app.client().post("/invalid", builder -> {
                    builder.contentType(ContentType.Application.Json);
                    builder.setBody("{");
                })) {
                    assertEquals(HttpStatusCode.BadRequest, ServerCommonTestSupport.status(invalidResponse));
                    assertTrue(ServerCommonTestSupport.bodyAsText(invalidResponse).contains("Invalid request body"));
                }

                try (Response writerResponse = app.client().put("/bytes-writer", builder -> {
                })) {
                    assertEquals(HttpStatusCode.OK, ServerCommonTestSupport.status(writerResponse));
                    assertEquals("written", ServerCommonTestSupport.bodyAsText(writerResponse));
                }

                try (Response outputResponse = app.client().get("/output-stream")) {
                    assertEquals(HttpStatusCode.OK, ServerCommonTestSupport.status(outputResponse));
                    assertEquals(ContentType.Image.JPEG.getValue(), ServerCommonTestSupport.contentType(outputResponse));
                    assertEquals("jpeg", ServerCommonTestSupport.bodyAsText(outputResponse));
                }

                try (Response fileResponse = app.client().get("/file")) {
                    assertEquals(HttpStatusCode.OK, ServerCommonTestSupport.status(fileResponse));
                    assertEquals(ContentType.Application.OctetStream.getValue(), ServerCommonTestSupport.contentType(fileResponse));
                    assertEquals("file-body", ServerCommonTestSupport.bodyAsText(fileResponse));
                }

                try (Response attributeResponse = app.client().get("/attribute")) {
                    assertEquals("42", ServerCommonTestSupport.bodyAsText(attributeResponse));
                }

                try (Response principalResponse = app.client().get("/principal")) {
                    assertEquals("alice", ServerCommonTestSupport.bodyAsText(principalResponse));
                }

                try (Response ipResponse = app.client().get("/ip")) {
                    assertFalse(ServerCommonTestSupport.bodyAsText(ipResponse).isBlank());
                }

                try (Response nestedRootResponse = app.client().get("/root")) {
                    assertEquals("nested-root", ServerCommonTestSupport.bodyAsText(nestedRootResponse));
                }

                try (Response statusResponse = app.client().delete("/status")) {
                    assertEquals(HttpStatusCode.NoContent, ServerCommonTestSupport.status(statusResponse));
                    assertEquals("", ServerCommonTestSupport.bodyAsText(statusResponse));
                }
            } finally {
                ServerCommonTestSupport.deleteRecursively(tempDir);
            }
        });
    }

    @Test
    void nestedRoutesAuthEvaluationAndDocsRegistrationWorkTogether() throws Exception {
        OpenApiRegistry docs = new OpenApiRegistry();
        java.util.ArrayList<RecordedAuthState> authModes = new java.util.ArrayList<>();

        ServerCommonTestSupport.testApplication(app -> {
            Route route = new Route(app.app(), docs, (call, authMode, authProviders) -> {
                authModes.add(new RecordedAuthState(authMode, List.copyOf(authProviders)));
                if (call.getRequest().getHeaders().get(HttpHeaders.Authorization) == null
                    || call.getRequest().getHeaders().get(HttpHeaders.Authorization).isBlank()) {
                    call.respond(HttpStatusCode.Unauthorized, "missing auth");
                    call.abort();
                } else {
                    call.attribute(Route.AUTH_PRINCIPAL_ATTRIBUTE, new UserIdPrincipal("alice"));
                }
            });

            route.route("/api/", api -> {
                api.withAuth(AuthMode.REQUIRED, List.of("bearer")).get(
                    "/items/{id}",
                    config -> {
                        config.setDescription("Fetch item");
                        config.request(request -> {
                            request.pathParameter("id", parameter -> {
                                parameter.setDescription("Item identifier");
                            });
                        });
                        config.response(response -> {
                            response.code(HttpStatusCode.OK, doc -> {
                                doc.setDescription("Fetched");
                            });
                        });
                    },
                    scope -> {
                        UserIdPrincipal principal = scope.getCall().principal(UserIdPrincipal.class);
                        String template = scope.getCall().getContext().attribute(Route.ROUTE_TEMPLATE_ATTRIBUTE);
                        String response = String.join(
                            ":",
                            principal == null ? "null" : principal.getName(),
                            template,
                            scope.getCall().getPathParameters().get("id")
                        );
                        scope.getCall().respondText(response);
                    }
                );
            });

            try (Response unauthorized = app.client().get("/api/items/42")) {
                assertEquals(HttpStatusCode.Unauthorized, ServerCommonTestSupport.status(unauthorized));
                assertEquals("missing auth", ServerCommonTestSupport.bodyAsText(unauthorized));
            }

            try (Response authorized = app.client().get("/api/items/42", builder ->
                builder.header(HttpHeaders.Authorization, "Bearer token"))) {
                assertEquals(HttpStatusCode.OK, ServerCommonTestSupport.status(authorized));
                assertEquals("alice:/api/items/{id}:42", ServerCommonTestSupport.bodyAsText(authorized));
            }
        });

        assertFalse(authModes.isEmpty());
        assertEquals(AuthMode.REQUIRED, authModes.getFirst().authMode());
        assertEquals(List.of("bearer"), authModes.getFirst().authProviders());

        Map<String, Object> spec = docs.buildSpec("Nyx", "1.0.0");
        Map<?, ?> paths = assertInstanceOf(Map.class, spec.get("paths"));
        Object itemPath = paths.get("/api/items/{id}");
        assertNotNull(itemPath);
        Map<?, ?> get = assertInstanceOf(Map.class, assertInstanceOf(Map.class, itemPath).get("get"));
        assertEquals("Fetch item", get.get("description"));
        Object responsesValue = get.get("responses");
        assertNotNull(responsesValue);
        Map<?, ?> responses = assertInstanceOf(Map.class, responsesValue);
        Object successValue = responses.get("200");
        assertNotNull(successValue);
        Map<?, ?> success = assertInstanceOf(Map.class, successValue);
        assertEquals("Fetched", success.get("description"));
        assertNull(spec.get("description"));
    }

    @Test
    void routingPrimitivesDelegateDirectlyToTheUnderlyingContext() throws Exception {
        RecordedContextState state = new RecordedContextState();
        state.requestHeaders.put(HttpHeaders.Authorization, "Bearer proxy");
        state.queryParameters.put("page", "3");
        state.pathParameters.put("id", "42");
        state.requestBody = "hello";
        state.requestBytes = "bytes".getBytes(StandardCharsets.UTF_8);

        RoutingCall call = new RoutingCall(fakeContext(state));
        RouteHandlerScope scope = new RouteHandlerScope(call);
        ServerSentEvent event = new ServerSentEvent("payload", "update");

        assertEquals("Bearer proxy", call.getRequest().getHeaders().get(HttpHeaders.Authorization));
        assertEquals("3", call.getRequest().getQueryParameters().get("page"));
        assertEquals("42", call.getParameters().get("id"));
        assertEquals("42", scope.getCall().getPathParameters().get("id"));
        assertEquals("hello", call.receiveText());

        call.attribute(Route.AUTH_PRINCIPAL_ATTRIBUTE, new UserIdPrincipal("proxy"));
        UserIdPrincipal principal = call.principal(UserIdPrincipal.class);
        assertNotNull(principal);
        assertEquals("proxy", principal.getName());

        call.respond("plain");
        call.respond("binary".getBytes(StandardCharsets.UTF_8));
        call.respondText("text", ContentType.Text.Plain, HttpStatusCode.Accepted);
        call.respondBytes("stream".getBytes(StandardCharsets.UTF_8), ContentType.Application.OctetStream);
        call.respondOutputStream(ContentType.Text.Plain, output -> {
            writeUtf8(output, "out");
        });
        call.redirect("/target");

        var sseConstructor = SseClient.class.getDeclaredConstructor(Context.class);
        sseConstructor.setAccessible(true);
        SseClient stringEventClient = sseConstructor.newInstance(fakeContext(state));
        SseClient fallbackClient = sseConstructor.newInstance(fakeContext(state));
        new SseRouteHandlerScope(call, stringEventClient).send(event);
        new SseRouteHandlerScope(call, fallbackClient).send(new ServerSentEvent("fallback"));

        assertEquals("/target", state.redirectedTo);
        assertEquals(HttpStatus.ACCEPTED, state.status);
        assertEquals(ContentType.Text.Plain.getValue(), state.contentType);
        assertTrue(state.compressionDisabled);
        assertEquals("127.0.0.1", call.requestIp());
        assertEquals("payload", event.getData());
        assertEquals("update", event.getEvent());
        String written = state.outputBytes.toString(StandardCharsets.UTF_8);
        assertTrue(written.contains("plainbinarytextstreamout"));
        assertTrue(written.contains("event: update"));
        assertTrue(written.contains("data: payload"));
        assertTrue(written.contains("event: message"));
        assertTrue(written.contains("data: fallback"));
    }

    private static void registerRouteHelpers(Route route, Path file) {
        route.get("/headers", scope -> {
            RoutingCall call = scope.getCall();
            String requestId = call.getRequest().getHeaders().get(HttpHeaders.XRequestId);
            if (requestId == null) {
                requestId = "none";
            }
            String echo = call.getRequest().getQueryParameters().get("echo");
            if (echo == null) {
                echo = "missing";
            }
            call.getResponse().header("X-Echo", echo);
            call.respondText(requestId, ContentType.Text.Plain, HttpStatusCode.Accepted);
            call.getResponse().header("X-Status", call.getResponse().status().toString());
        });
        route.post("/json", scope -> {
            Payload payload = scope.getCall().receive(Payload.class);
            scope.getCall().respond(HttpStatusCode.Created, payload);
        });
        route.post("/text", scope -> {
            String payload = scope.getCall().receive(String.class);
            scope.getCall().respondText(payload + ":" + scope.getCall().receiveText());
        });
        route.post("/bytes", scope -> {
            byte[] payload = scope.getCall().receive(byte[].class);
            scope.getCall().respondBytes(payload, ContentType.Application.OctetStream);
        });
        route.post("/invalid", scope -> {
            scope.getCall().receive(Payload.class);
            scope.getCall().respondText("unreachable");
        });
        route.put("/bytes-writer", scope -> {
            scope.getCall().respondBytesWriter(output -> {
                writeUtf8(output, "written");
            });
        });
        route.patch("/object", scope -> {
            scope.getCall().respond(Map.of("status", "patched"));
        });
        route.delete("/status", scope -> {
            scope.getCall().respond(HttpStatusCode.NoContent);
        });
        route.get("/output-stream", scope -> {
            scope.getCall().respondOutputStream(ContentType.Image.JPEG, output -> {
                writeUtf8(output, "jpeg");
            });
        });
        route.get("/file", scope -> {
            scope.getCall().respondFile(file, ContentType.Application.OctetStream.getValue());
        });
        route.get("/attribute", scope -> {
            scope.getCall().attribute("answer", 42);
            Integer answer = scope.getCall().getContext().attribute("answer");
            scope.getCall().respondText(Integer.toString(answer));
        });
        route.get("/principal", scope -> {
            scope.getCall().attribute(Route.AUTH_PRINCIPAL_ATTRIBUTE, new UserIdPrincipal("alice"));
            UserIdPrincipal principal = scope.getCall().principal(UserIdPrincipal.class);
            scope.getCall().respondText(principal == null ? "none" : principal.getName());
        });
        route.get("/ip", scope -> {
            scope.getCall().respondText(scope.getCall().requestIp());
        });
        route.route("/root", nested -> {
            nested.get("", scope -> {
                scope.getCall().respondText("nested-root");
            });
        });
        route.sse("/events", scope -> {
            scope.getCall().getResponse().header("X-Preflight", "1");
        }, scope -> {
            scope.send(new ServerSentEvent("payload", "update"));
        });
    }

    private static Context fakeContext(RecordedContextState state) {
        ServletOutputStream outputStream = new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener writeListener) {
            }

            @Override
            public void write(int byteValue) {
                state.outputBytes.write(byteValue);
            }
        };

        HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(
            RouteTest.class.getClassLoader(),
            new Class<?>[]{HttpServletResponse.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getOutputStream" -> outputStream;
                case "flushBuffer" -> null;
                default -> defaultValue(method.getReturnType());
            }
        );

        return (Context) Proxy.newProxyInstance(
            RouteTest.class.getClassLoader(),
            new Class<?>[]{Context.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "header" -> switch (args == null ? 0 : args.length) {
                    case 1 -> state.requestHeaders.get(args[0]);
                    case 2 -> {
                        state.responseHeaders.put((String) args[0], (String) args[1]);
                        yield null;
                    }
                    default -> throw new IllegalStateException("Unexpected header signature");
                };
                case "res" -> response;
                case "queryParam" -> state.queryParameters.get(args[0]);
                case "pathParamMap" -> state.pathParameters;
                case "body" -> state.requestBody;
                case "bodyAsBytes" -> state.requestBytes;
                case "attribute" -> switch (args == null ? 0 : args.length) {
                    case 1 -> state.attributes.get(args[0]);
                    case 2 -> {
                        state.attributes.put((String) args[0], args[1]);
                        yield null;
                    }
                    default -> throw new IllegalStateException("Unexpected attribute signature");
                };
                case "result" -> {
                    Object value = args[0];
                    if (value instanceof String text) {
                        state.outputBytes.write(text.getBytes(StandardCharsets.UTF_8));
                    } else if (value instanceof byte[] bytes) {
                        state.outputBytes.write(bytes);
                    } else {
                        throw new IllegalStateException("Unexpected result payload: " + value);
                    }
                    yield null;
                }
                case "contentType" -> {
                    state.contentType = (String) args[0];
                    yield null;
                }
                case "status" -> switch (args == null ? 0 : args.length) {
                    case 0 -> state.status;
                    case 1 -> {
                        Object value = args[0];
                        if (value instanceof HttpStatus httpStatus) {
                            state.status = httpStatus;
                        } else if (value instanceof Integer statusCode) {
                            state.status = HttpStatus.forStatus(statusCode);
                        } else {
                            throw new IllegalStateException("Unexpected status payload: " + value);
                        }
                        yield null;
                    }
                    default -> throw new IllegalStateException("Unexpected status signature");
                };
                case "outputStream" -> outputStream;
                case "disableCompression" -> {
                    state.compressionDisabled = true;
                    yield null;
                }
                case "redirect" -> {
                    state.redirectedTo = (String) args[0];
                    yield null;
                }
                case "ip" -> state.ip;
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }

    private static void writeUtf8(java.io.OutputStream output, String text) {
        try {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Failed to write test payload", exception);
        }
    }

    private static final class RecordedContextState {
        private final Map<String, Object> attributes = new LinkedHashMap<>();
        private final Map<String, String> requestHeaders = new LinkedHashMap<>();
        private final Map<String, String> queryParameters = new LinkedHashMap<>();
        private final Map<String, String> pathParameters = new LinkedHashMap<>();
        private String requestBody = "";
        private byte[] requestBytes = new byte[0];
        private HttpStatus status = HttpStatus.OK;
        private String contentType;
        private String redirectedTo;
        private boolean compressionDisabled;
        private String ip = "127.0.0.1";
        private final Map<String, String> responseHeaders = new LinkedHashMap<>();
        private final ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
    }

    private record RecordedAuthState(AuthMode authMode, List<String> authProviders) {
    }

    private static final class Payload {
        private String value;

        private Payload() {
        }

        private Payload(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }
}
