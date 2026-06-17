package com.nyx.transcode.webhook;

import org.jspecify.annotations.NullMarked;

@NullMarked
public record WebhookHttpResponse(int statusCode) {
}
