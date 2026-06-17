package com.nyx;

import com.nyx.config.AuthConfig;
import com.nyx.config.DatabaseConfig;
import com.nyx.config.ServerConfig;
import com.nyx.http.AuthMode;
import com.nyx.http.ContentType;
import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import com.nyx.http.Route;
import com.nyx.http.RouteHandlerScope;
import com.nyx.http.RoutingCall;
import com.nyx.http.TriConsumer;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class AppTestSupport {
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json");
    private static final RequestBody EMPTY_BODY = RequestBody.create(new byte[0], null);

    private AppTestSupport() {
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
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Failed to read response body", exception);
        }
    }

    public static ContentType contentType(Response response) {
        String value = response.header(HttpHeaders.ContentType);
        if (value == null) {
            return null;
        }
        return ContentType.parse(value.substring(0, value.indexOf(';') >= 0 ? value.indexOf(';') : value.length()));
    }

    @FunctionalInterface
    public interface ThrowingConsumer<T> {
        void accept(T value) throws Exception;
    }

    public static final class ApplicationHarness implements AutoCloseable {
        private io.javalin.Javalin appInstance;
        private TriConsumer<RoutingCall, AuthMode, List<String>> authEvaluator;
        private HttpTestClient httpClient;
        private boolean started;

        public io.javalin.Javalin app() {
            return ensureStarted().appInstance;
        }

        public HttpTestClient client() {
            return ensureStarted().httpClient;
        }

        public void configurePlugins() {
            configurePlugins(null, null, null, null);
        }

        public void configurePlugins(ServerConfig serverConfig) {
            configurePlugins(serverConfig, null, null, null);
        }

        public void configurePlugins(ServerConfig serverConfig, AuthConfig authConfig) {
            configurePlugins(serverConfig, authConfig, null, null);
        }

        public void configurePlugins(io.micrometer.prometheusmetrics.PrometheusMeterRegistry metricsRegistry) {
            configurePlugins(null, null, metricsRegistry, null);
        }

        public void configurePlugins(
            ServerConfig serverConfig,
            AuthConfig authConfig,
            io.micrometer.prometheusmetrics.PrometheusMeterRegistry metricsRegistry,
            ConcurrentHashMap<String, String> runtimeUsers
        ) {
            ServerConfig effectiveConfig = serverConfig != null ? serverConfig : defaultServerConfig(authConfig);
            AuthConfig effectiveAuth = authConfig != null ? authConfig : effectiveConfig.getAuth();
            ConcurrentHashMap<String, String> effectiveUsers = runtimeUsers != null
                ? runtimeUsers
                : new ConcurrentHashMap<>(effectiveConfig.getAuth().getUsers());
            var runtime = ApplicationRuntime.createConfiguredApp(
                effectiveConfig,
                effectiveAuth,
                metricsRegistry,
                effectiveUsers
            );
            replaceApp(runtime.app(), runtime.authEvaluator());
        }

        public void configureRouting(ServerConfig config, ConcurrentHashMap<String, String> runtimeUsers) {
            replaceApp(AppRouting.createApplication(config, runtimeUsers), null);
        }

        public void routing(ThrowingConsumer<RouteHarness> block) throws Exception {
            io.javalin.Javalin app = appInstance;
            if (app == null) {
                throw new IllegalStateException("Call configurePlugins() or configureRouting() before registering routes");
            }
            block.accept(new RouteHarness(new Route(app, authEvaluator)));
        }

        @Override
        public void close() {
            if (started && appInstance != null) {
                try {
                    appInstance.stop();
                } catch (Exception ignored) {
                }
            }
        }

        private void replaceApp(
            io.javalin.Javalin app,
            TriConsumer<RoutingCall, AuthMode, List<String>> authEvaluator
        ) {
            if (started) {
                throw new IllegalStateException("Application is already started");
            }
            this.appInstance = app;
            this.authEvaluator = authEvaluator;
        }

        private ApplicationHarness ensureStarted() {
            if (httpClient != null && appInstance != null) {
                return this;
            }

            if (appInstance == null) {
                appInstance = ApplicationRuntime.createConfiguredApp(
                    defaultServerConfig(null),
                    new ConcurrentHashMap<>()
                ).app();
            }
            appInstance.start(0);
            started = true;

            String baseUrl = "http://127.0.0.1:" + appInstance.port();
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
            httpClient = new HttpTestClient(okHttp, baseUrl);
            return this;
        }
    }

    public static final class RouteHarness {
        private final Route delegate;

        private RouteHarness(Route delegate) {
            this.delegate = delegate;
        }

        public Route raw() {
            return delegate;
        }

        public void route(String path, ThrowingConsumer<RouteHarness> block) {
            delegate.route(path, child -> {
                try {
                    block.accept(new RouteHarness(child));
                } catch (Exception exception) {
                    throwUnchecked(exception);
                }
            });
        }

        public RouteHarness withAuth(AuthMode authMode, List<String> authProviders) {
            return new RouteHarness(delegate.withAuth(authMode, authProviders));
        }

        public void get(String path, ThrowingConsumer<RouteHandlerScope> handler) {
            delegate.get(path, scope -> invokeHandler(scope, handler));
        }

        public void post(String path, ThrowingConsumer<RouteHandlerScope> handler) {
            delegate.post(path, scope -> invokeHandler(scope, handler));
        }

        public void put(String path, ThrowingConsumer<RouteHandlerScope> handler) {
            delegate.put(path, scope -> invokeHandler(scope, handler));
        }

        public void patch(String path, ThrowingConsumer<RouteHandlerScope> handler) {
            delegate.patch(path, scope -> invokeHandler(scope, handler));
        }

        public void delete(String path, ThrowingConsumer<RouteHandlerScope> handler) {
            delegate.delete(path, scope -> invokeHandler(scope, handler));
        }

        private static void invokeHandler(
            RouteHandlerScope scope,
            ThrowingConsumer<RouteHandlerScope> handler
        ) {
            try {
                handler.accept(scope);
            } catch (Exception exception) {
                throwUnchecked(exception);
            }
        }
    }

    public static final class TestRequestBuilder {
        private final java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        private String contentType;
        private String body;

        public void header(String name, String value) {
            headers.put(name, value);
        }

        public void contentType(ContentType contentType) {
            this.contentType = contentType.getValue();
            header(HttpHeaders.ContentType, contentType.getValue());
        }

        public void setBody(String body) {
            this.body = body;
        }

        public void basicAuth(String username, String password) {
            String token = Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
            header(HttpHeaders.Authorization, "Basic " + token);
        }

        private Response apply(String method, String path, OkHttpClient client, String baseUrl) throws java.io.IOException {
            Request.Builder builder = new Request.Builder().url(baseUrl + path);
            headers.forEach(builder::header);
            switch (method) {
                case "GET" -> builder.get();
                case "OPTIONS" -> builder.method("OPTIONS", EMPTY_BODY);
                case "DELETE" -> builder.delete();
                case "POST" -> builder.post(requestBody());
                case "PUT" -> builder.put(requestBody());
                default -> throw new IllegalArgumentException("Unsupported method: " + method);
            }
            return client.newCall(builder.build()).execute();
        }

        private RequestBody requestBody() {
            return RequestBody.create(body == null ? "" : body, contentType == null ? JSON_MEDIA_TYPE : MediaType.get(contentType));
        }
    }

    public static final class HttpTestClient {
        private final OkHttpClient okHttp;
        private final String baseUrl;

        private HttpTestClient(OkHttpClient okHttp, String baseUrl) {
            this.okHttp = okHttp;
            this.baseUrl = baseUrl;
        }

        public Response get(String path) throws java.io.IOException {
            return request("GET", path, builder -> { });
        }

        public Response get(String path, Consumer<TestRequestBuilder> configure) throws java.io.IOException {
            return request("GET", path, configure);
        }

        public Response options(String path) throws java.io.IOException {
            return request("OPTIONS", path, builder -> { });
        }

        public Response options(String path, Consumer<TestRequestBuilder> configure) throws java.io.IOException {
            return request("OPTIONS", path, configure);
        }

        public Response post(String path) throws java.io.IOException {
            return request("POST", path, builder -> { });
        }

        public Response post(String path, Consumer<TestRequestBuilder> configure) throws java.io.IOException {
            return request("POST", path, configure);
        }

        public Response put(String path, Consumer<TestRequestBuilder> configure) throws java.io.IOException {
            return request("PUT", path, configure);
        }

        public Response delete(String path) throws java.io.IOException {
            return request("DELETE", path, builder -> { });
        }

        public Response delete(String path, Consumer<TestRequestBuilder> configure) throws java.io.IOException {
            return request("DELETE", path, configure);
        }

        private Response request(String method, String path, Consumer<TestRequestBuilder> configure) throws java.io.IOException {
            TestRequestBuilder builder = new TestRequestBuilder();
            configure.accept(builder);
            return detach(builder.apply(method, path, okHttp, baseUrl));
        }
    }

    private static ServerConfig defaultServerConfig(AuthConfig authConfig) {
        return AppTestData.testServerConfig(
            "0.0.0.0",
            8080,
            List.of(),
            List.of(),
            AppTestData.testFfmpegConfig(),
            AppTestData.testTranscodeConfig(),
            new DatabaseConfig(Path.of("/tmp/nyx-app-test")),
            new com.nyx.config.ThumbnailConfig(),
            new com.nyx.config.AudioConfig(),
            authConfig != null ? authConfig : AppTestData.testAuthConfig(),
            new com.nyx.config.RateLimitConfig(),
            new com.nyx.config.CsrfConfig(),
            new com.nyx.config.TlsConfig(),
            new com.nyx.config.WebhookConfig(),
            new com.nyx.config.QuotaConfig(),
            new com.nyx.config.BackupConfig(),
            new com.nyx.config.StorageConfig()
        );
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T throwUnchecked(Throwable throwable) throws E {
        throw (E) throwable;
    }

    private static Response detach(Response response) throws java.io.IOException {
        try (response) {
            ResponseBody body = response.body();
            MediaType mediaType = body != null ? body.contentType() : null;
            byte[] bytes = body != null ? body.bytes() : new byte[0];
            return response.newBuilder().body(ResponseBody.create(bytes, mediaType)).build();
        }
    }
}
