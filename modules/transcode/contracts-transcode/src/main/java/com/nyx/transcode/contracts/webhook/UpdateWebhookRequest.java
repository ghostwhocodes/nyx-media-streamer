package com.nyx.transcode.contracts.webhook;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record UpdateWebhookRequest(
    @JsonProperty("url") String url,
    @JsonProperty("secret") String secret,
    @JsonProperty("events") List<String> events,
    @JsonProperty("isActive") Boolean isActive
) {
    public UpdateWebhookRequest() {
        this(null, null, null, null);
    }

    public UpdateWebhookRequest {
        events = events == null ? null : List.copyOf(events);
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

    @JsonIgnore
    public Boolean getIsActive() {
        return isActive;
    }
}
