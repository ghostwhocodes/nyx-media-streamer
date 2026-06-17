package com.nyx.transcode.webhook;

import java.io.IOException;
import java.net.URI;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public interface WebhookHttpClient extends AutoCloseable {
    WebhookHttpResponse postJson(URI uri, String payloadJson, @Nullable String signatureHeader)
            throws IOException, InterruptedException;

    @Override
    void close();
}
