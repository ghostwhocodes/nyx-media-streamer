package com.nyx.transcode;

import com.nyx.transcode.contracts.webhook.WebhookUrlValidator;
import com.nyx.transcode.webhook.WebhookService;

public record WebhookResources(
    WebhookService service,
    WebhookUrlValidator urlValidator
) {
    public WebhookResources() {
        this(null, null);
    }
}
