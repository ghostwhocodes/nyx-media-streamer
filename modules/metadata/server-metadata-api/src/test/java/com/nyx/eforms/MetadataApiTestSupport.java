package com.nyx.eforms;

import com.nyx.common.ErrorResponsesConfig;
import com.nyx.http.ContentType;
import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import com.nyx.http.Route;
import com.nyx.json.NyxJson;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class MetadataApiTestSupport {
    private static final okhttp3.MediaType JSON_MEDIA_TYPE = okhttp3.MediaType.get("application/json");

    private MetadataApiTestSupport() {
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

    public static byte[] readRawBytes(Response response) {
        try {
            if (response.body() == null) {
                throw new IllegalStateException("Response body is missing");
            }
            return response.body().bytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read response body bytes", exception);
        }
    }

    public static ContentType contentType(Response response) {
        String value = response.header(HttpHeaders.ContentType);
        if (value == null) {
            return null;
        }
        int separator = value.indexOf(';');
        return ContentType.parse(separator >= 0 ? value.substring(0, separator) : value);
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

        public Javalin app() {
            return app;
        }

        public Route route() {
            return new Route(app);
        }

        public HttpTestClient client() {
            return client;
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
            return detach(builder.apply(method, path, okHttp, baseUrl));
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
