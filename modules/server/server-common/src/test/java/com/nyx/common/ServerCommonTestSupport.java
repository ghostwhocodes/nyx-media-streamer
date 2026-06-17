package com.nyx.common;

import com.nyx.http.ContentType;
import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import com.nyx.http.Route;
import com.nyx.json.NyxJson;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.nio.file.Files;
import java.nio.file.Path;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class ServerCommonTestSupport {
    private static final okhttp3.MediaType JSON_MEDIA_TYPE = okhttp3.MediaType.get("application/json");

    private ServerCommonTestSupport() {
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

    public static String contentType(Response response) {
        String value = response.header(HttpHeaders.ContentType);
        if (value == null) {
            return null;
        }
        int separator = value.indexOf(';');
        return separator >= 0 ? value.substring(0, separator) : value;
    }

    public static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException exception) {
                        throw new IllegalStateException("Failed to delete " + path, exception);
                    }
                });
        } catch (IllegalStateException exception) {
            if (exception.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw exception;
        }
    }

    @FunctionalInterface
    public interface ThrowingConsumer<T> {
        void accept(T value) throws Exception;
    }

    public static final class ApplicationHarness implements AutoCloseable {
        private final Javalin app;
        private final HttpTestClient client;

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

        public Route route() {
            return new Route(app);
        }

        public void routing(Consumer<Route> configure) {
            configure.accept(route());
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
    }

    public static final class TestRequestBuilder {
        private final Map<String, String> headers = new LinkedHashMap<>();
        private String contentType;
        private byte[] bodyBytes;
        private String bodyText;

        public void header(String name, String value) {
            headers.put(name, value);
        }

        public void contentType(ContentType contentType) {
            this.contentType = contentType.getValue();
            header(HttpHeaders.ContentType, contentType.getValue());
        }

        public void setBody(String body) {
            bodyText = body;
            bodyBytes = null;
        }

        public void setBody(byte[] body) {
            bodyBytes = body;
            bodyText = null;
        }

        private Response apply(String method, String path, OkHttpClient client, String baseUrl) throws IOException {
            Request.Builder builder = new Request.Builder().url(baseUrl + normalizePath(path));
            headers.forEach(builder::header);
            switch (method) {
                case "GET" -> builder.get();
                case "DELETE" -> builder.delete(requestBody());
                case "POST" -> builder.post(requestBody());
                case "PUT" -> builder.put(requestBody());
                default -> throw new IllegalArgumentException("Unsupported method: " + method);
            }
            return client.newCall(builder.build()).execute();
        }

        private RequestBody requestBody() {
            okhttp3.MediaType mediaType = contentType == null ? JSON_MEDIA_TYPE : okhttp3.MediaType.get(contentType);
            if (bodyBytes != null) {
                return RequestBody.create(bodyBytes, mediaType);
            }
            if (bodyText != null) {
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
            return builder.apply(method, path, okHttp, baseUrl);
        }
    }

    private static String normalizePath(String path) {
        return path.replace("\\", "%5C");
    }
}
