package com.nyx.admin;

import com.nyx.common.ErrorDetail;
import com.nyx.common.ErrorResponse;
import com.nyx.common.ErrorResponsesConfig;
import com.nyx.http.AuthMode;
import com.nyx.http.ContentType;
import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import com.nyx.http.Route;
import com.nyx.http.RoutingCall;
import com.nyx.http.TriConsumer;
import com.nyx.http.UserIdPrincipal;
import com.nyx.json.NyxJson;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class AdminApiTestSupport {
    private static final okhttp3.MediaType JSON_MEDIA_TYPE = okhttp3.MediaType.get("application/json");

    private AdminApiTestSupport() {
    }

    public static void testApplication(ThrowingConsumer<ApplicationHarness> block) throws Exception {
        try (ApplicationHarness harness = new ApplicationHarness()) {
            block.accept(harness);
        }
    }

    public static HttpStatusCode status(Response response) {
        return HttpStatusCode.fromValue(response.code());
    }

    public static String bodyAsText(Response response) {
        try {
            return response.peekBody(Long.MAX_VALUE).string();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read response body", exception);
        }
    }

    @FunctionalInterface
    public interface ThrowingConsumer<T> {
        void accept(T value) throws Exception;
    }

    public static final class ApplicationHarness implements AutoCloseable {
        private final Javalin app;
        private final HttpTestClient client;
        private final Map<String, Function<BearerCredential, UserIdPrincipal>> bearerHandlers = new LinkedHashMap<>();
        private final Map<String, Function<BasicCredential, UserIdPrincipal>> basicHandlers = new LinkedHashMap<>();

        private ApplicationHarness() {
            app = Javalin.create(config -> {
                config.startup.showJavalinBanner = false;
                config.jsonMapper(new JavalinJackson(NyxJson.newMapper(), false));
            });
            ErrorResponsesConfig.configureErrorHandling(app);
            app.start(0);

            String baseUrl = "http://127.0.0.1:" + app.port();
            OkHttpClient okHttp = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Request request = chain.request();
                    Request.Builder builder = request.newBuilder();
                    if (request.header(HttpHeaders.AcceptEncoding) == null) {
                        builder.header(HttpHeaders.AcceptEncoding, "identity");
                    }
                    return chain.proceed(builder.build());
                })
                .build();
            client = new HttpTestClient(okHttp, baseUrl);
        }

        public void installContentNegotiation(Object mapper) {
            mapper.getClass();
        }

        public void installStatusPages() {
        }

        public void installAuthentication(Consumer<AuthenticationConfig> configure) {
            AuthenticationConfig config = new AuthenticationConfig();
            configure.accept(config);
            bearerHandlers.putAll(config.bearerHandlers);
            basicHandlers.putAll(config.basicHandlers);
        }

        public void routing(Consumer<Route> configure) {
            configure.accept(new Route(app, authEvaluator()));
        }

        public HttpTestClient client() {
            return client;
        }

        public Javalin app() {
            return app;
        }

        @Override
        public void close() {
            app.stop();
        }

        private TriConsumer<RoutingCall, AuthMode, List<String>> authEvaluator() {
            return (call, authMode, authProviders) -> {
                if (authMode == AuthMode.PUBLIC || authProviders.isEmpty()) {
                    return;
                }

                String authorization = call.getRequest().getHeaders().get(HttpHeaders.Authorization);
                if (authorization != null) {
                    if (authorization.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
                        BearerCredential credential = new BearerCredential(authorization.substring("Bearer ".length()).trim());
                        for (String provider : authProviders) {
                            Function<BearerCredential, UserIdPrincipal> handler = bearerHandlers.get(provider);
                            if (handler == null) {
                                continue;
                            }
                            UserIdPrincipal principal = handler.apply(credential);
                            if (principal != null) {
                                call.attribute(Route.AUTH_PRINCIPAL_ATTRIBUTE, principal);
                                return;
                            }
                        }
                    } else if (authorization.regionMatches(true, 0, "Basic ", 0, "Basic ".length())) {
                        BasicCredential credential = decodeBasicCredential(authorization);
                        if (credential != null) {
                            for (String provider : authProviders) {
                                Function<BasicCredential, UserIdPrincipal> handler = basicHandlers.get(provider);
                                if (handler == null) {
                                    continue;
                                }
                                UserIdPrincipal principal = handler.apply(credential);
                                if (principal != null) {
                                    call.attribute(Route.AUTH_PRINCIPAL_ATTRIBUTE, principal);
                                    return;
                                }
                            }
                        }
                    }
                }

                call.respond(
                    HttpStatusCode.Companion.getUnauthorized(),
                    new ErrorResponse(new ErrorDetail("UNAUTHORIZED", "Authentication required"))
                );
                call.abort();
            };
        }
    }

    public static final class AuthenticationConfig {
        private final Map<String, Function<BearerCredential, UserIdPrincipal>> bearerHandlers = new LinkedHashMap<>();
        private final Map<String, Function<BasicCredential, UserIdPrincipal>> basicHandlers = new LinkedHashMap<>();

        public void bearer(String name, Consumer<BearerAuthConfig> configure) {
            BearerAuthConfig config = new BearerAuthConfig();
            configure.accept(config);
            if (config.handler == null) {
                throw new IllegalStateException("Bearer authentication handler is required for provider '" + name + "'");
            }
            bearerHandlers.put(name, config.handler);
        }

        public void basic(String name, Consumer<BasicAuthConfig> configure) {
            BasicAuthConfig config = new BasicAuthConfig();
            configure.accept(config);
            if (config.handler == null) {
                throw new IllegalStateException("Basic authentication handler is required for provider '" + name + "'");
            }
            basicHandlers.put(name, config.handler);
        }
    }

    public static final class BearerAuthConfig {
        private Function<BearerCredential, UserIdPrincipal> handler;

        public void authenticate(Function<BearerCredential, UserIdPrincipal> handler) {
            this.handler = handler;
        }
    }

    public static final class BasicAuthConfig {
        private String realm = "nyx";
        private Function<BasicCredential, UserIdPrincipal> handler;

        public String getRealm() {
            return realm;
        }

        public void setRealm(String realm) {
            this.realm = realm;
        }

        public void validate(Function<BasicCredential, UserIdPrincipal> handler) {
            this.handler = handler;
        }
    }

    public record BearerCredential(String token) {
    }

    public record BasicCredential(String name, String password) {
    }

    public static final class TestRequestBuilder {
        private final Map<String, String> headers = new LinkedHashMap<>();
        private String contentType;
        private String bodyText;

        public void header(String name, String value) {
            headers.put(name, value);
        }

        public void contentType(ContentType contentType) {
            this.contentType = contentType.getValue();
            header(HttpHeaders.ContentType, contentType.getValue());
        }

        public void basicAuth(String username, String password) {
            String value = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
            header(HttpHeaders.Authorization, "Basic " + value);
        }

        public void bearerAuth(String token) {
            header(HttpHeaders.Authorization, "Bearer " + token);
        }

        public void setBody(String body) {
            bodyText = body;
        }

        private Response apply(String method, String path, OkHttpClient client, String baseUrl) throws IOException {
            Request.Builder builder = new Request.Builder().url(baseUrl + normalizePath(path));
            headers.forEach(builder::header);
            switch (method) {
                case "GET" -> builder.get();
                case "POST" -> builder.post(requestBody());
                case "PUT" -> builder.put(requestBody());
                case "DELETE" -> builder.delete(requestBody());
                default -> throw new IllegalArgumentException("Unsupported method: " + method);
            }
            return client.newCall(builder.build()).execute();
        }

        private RequestBody requestBody() {
            if (bodyText != null) {
                okhttp3.MediaType mediaType = contentType == null ? JSON_MEDIA_TYPE : okhttp3.MediaType.get(contentType);
                return RequestBody.create(bodyText, mediaType);
            }
            return RequestBody.create(new byte[0], null);
        }
    }

    public static final class HttpTestClient {
        private final OkHttpClient okHttp;
        private final String baseUrl;

        private HttpTestClient(OkHttpClient okHttp, String baseUrl) {
            this.okHttp = okHttp;
            this.baseUrl = baseUrl;
        }

        public Response get(String path) throws IOException {
            return request("GET", path, builder -> {
            });
        }

        public Response get(String path, Consumer<TestRequestBuilder> configure) throws IOException {
            return request("GET", path, configure);
        }

        public Response post(String path) throws IOException {
            return request("POST", path, builder -> {
            });
        }

        public Response post(String path, Consumer<TestRequestBuilder> configure) throws IOException {
            return request("POST", path, configure);
        }

        public Response put(String path, Consumer<TestRequestBuilder> configure) throws IOException {
            return request("PUT", path, configure);
        }

        public Response delete(String path) throws IOException {
            return request("DELETE", path, builder -> {
            });
        }

        public Response delete(String path, Consumer<TestRequestBuilder> configure) throws IOException {
            return request("DELETE", path, configure);
        }

        private Response request(String method, String path, Consumer<TestRequestBuilder> configure) throws IOException {
            TestRequestBuilder builder = new TestRequestBuilder();
            configure.accept(builder);
            return detach(builder.apply(method, path, okHttp, baseUrl));
        }
    }

    private static BasicCredential decodeBasicCredential(String authorization) {
        String raw = authorization.substring("Basic ".length()).trim();
        try {
            String decoded = new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8);
            return new BasicCredential(
                decoded.substring(0, decoded.indexOf(':')),
                decoded.substring(decoded.indexOf(':') + 1)
            );
        } catch (Exception exception) {
            return null;
        }
    }

    private static String normalizePath(String path) {
        return path.replace("\\", "%5C");
    }

    private static Response detach(Response response) throws IOException {
        try (response) {
            ResponseBody body = response.body();
            okhttp3.MediaType mediaType = body != null ? body.contentType() : null;
            byte[] bytes = body != null ? body.bytes() : new byte[0];
            return response.newBuilder().body(ResponseBody.create(bytes, mediaType)).build();
        }
    }
}
