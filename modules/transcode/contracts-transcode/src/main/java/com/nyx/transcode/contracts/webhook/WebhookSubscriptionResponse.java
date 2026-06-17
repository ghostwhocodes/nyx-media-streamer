package com.nyx.transcode.contracts.webhook;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record WebhookSubscriptionResponse(
    @JsonProperty("id") String id,
    @JsonProperty("url") String url,
    @JsonProperty("events") List<String> events,
    @JsonProperty("isActive") boolean isActive,
    @JsonProperty("createdAt") String createdAt,
    @JsonProperty("updatedAt") String updatedAt
) {
    public WebhookSubscriptionResponse {
        events = events == null ? List.of() : List.copyOf(events);
    }

    @JsonIgnore
    public String getId() {
        return id;
    }

    @JsonIgnore
    public String getUrl() {
        return url;
    }

    @JsonIgnore
    public List<String> getEvents() {
        return events;
    }

    @JsonIgnore
    public String getCreatedAt() {
        return createdAt;
    }

    @JsonIgnore
    public String getUpdatedAt() {
        return updatedAt;
    }
}
