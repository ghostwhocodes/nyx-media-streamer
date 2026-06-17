package com.nyx.transcode.contracts;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record BatchCancelResponse(
    @JsonProperty("cancelled") List<String> cancelled,
    @JsonProperty("not_found") List<String> notFound
) {
    public BatchCancelResponse {
        cancelled = cancelled == null ? List.of() : List.copyOf(cancelled);
        notFound = notFound == null ? List.of() : List.copyOf(notFound);
    }

    @JsonIgnore
    public List<String> getCancelled() {
        return cancelled;
    }

    @JsonIgnore
    public List<String> getNotFound() {
        return notFound;
    }
}
