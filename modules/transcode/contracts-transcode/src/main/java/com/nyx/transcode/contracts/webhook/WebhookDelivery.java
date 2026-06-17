package com.nyx.transcode.contracts.webhook;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

public record WebhookDelivery(
    @JsonProperty("id") String id,
    @JsonProperty("subscriptionId") String subscriptionId,
    @JsonProperty("event") String event,
    @JsonProperty("payload") String payload,
    @JsonProperty("statusCode") Integer statusCode,
    @JsonProperty("attempt") int attempt,
    @JsonProperty("deliveredAt") String deliveredAt,
    @JsonProperty("createdAt") String createdAt
) {
    public WebhookDelivery(String id, String subscriptionId, String event, String payload, String createdAt) {
        this(id, subscriptionId, event, payload, null, 1, null, createdAt);
    }

    @JsonIgnore
    public String getId() {
        return id;
    }

    @JsonIgnore
    public String getSubscriptionId() {
        return subscriptionId;
    }

    @JsonIgnore
    public String getEvent() {
        return event;
    }

    @JsonIgnore
    public String getPayload() {
        return payload;
    }

    @JsonIgnore
    public Integer getStatusCode() {
        return statusCode;
    }

    @JsonIgnore
    public int getAttempt() {
        return attempt;
    }

    @JsonIgnore
    public String getDeliveredAt() {
        return deliveredAt;
    }

    @JsonIgnore
    public String getCreatedAt() {
        return createdAt;
    }
}
