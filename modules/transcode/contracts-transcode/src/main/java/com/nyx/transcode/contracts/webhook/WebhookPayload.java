package com.nyx.transcode.contracts.webhook;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

public record WebhookPayload(
    @JsonProperty("id") String id,
    @JsonProperty("event") String event,
    @JsonProperty("timestamp") String timestamp,
    @JsonProperty("data") JsonNode data
) {
    @JsonIgnore
    public String getId() {
        return id;
    }

    @JsonIgnore
    public String getEvent() {
        return event;
    }

    @JsonIgnore
    public String getTimestamp() {
        return timestamp;
    }

    @JsonIgnore
    public JsonNode getData() {
        return data;
    }
}
