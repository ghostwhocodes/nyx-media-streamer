package com.nyx.transcode.webhook;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public final class JavaNetWebhookHttpClient implements WebhookHttpClient {
    private final HttpClient client;
    private final Duration requestTimeout;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public JavaNetWebhookHttpClient(HttpClient client, Duration requestTimeout) {
        this.client = Objects.requireNonNull(client, "client");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    }

    @Override
    public WebhookHttpResponse postJson(URI uri, String payloadJson, @Nullable String signatureHeader)
            throws IOException, InterruptedException {
        if (closed.get()) {
            throw new IllegalStateException("Webhook HTTP client is closed");
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(Objects.requireNonNull(uri, "uri"))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Objects.requireNonNull(payloadJson, "payloadJson")));
        if (signatureHeader != null) {
            requestBuilder.header("X-Nyx-Signature", signatureHeader);
        }

        HttpResponse<Void> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.discarding());
        return new WebhookHttpResponse(response.statusCode());
    }

    @Override
    public void close() {
        closed.set(true);
    }
}
