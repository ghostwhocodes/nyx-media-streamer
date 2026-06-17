package com.nyx.transcode.contracts.webhook;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record CreateWebhookRequest(
    @JsonProperty("url") String url,
    @JsonProperty("secret") String secret,
    @JsonProperty("events") List<String> events
) {
    public CreateWebhookRequest(String url, List<String> events) {
        this(url, null, events);
    }

    public CreateWebhookRequest {
        events = events == null ? List.of() : List.copyOf(events);
    }

    @JsonIgnore
    public String getUrl() {
        return url;
    }

    @JsonIgnore
    public String getSecret() {
        return secret;
    }

    @JsonIgnore
    public List<String> getEvents() {
        return events;
    }
}
