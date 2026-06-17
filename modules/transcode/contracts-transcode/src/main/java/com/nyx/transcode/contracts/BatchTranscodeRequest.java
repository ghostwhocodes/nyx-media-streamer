package com.nyx.transcode.contracts;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record BatchTranscodeRequest(
    @JsonProperty("requests") List<TranscodeRequest> requests
) {
    public BatchTranscodeRequest {
        requests = requests == null ? List.of() : List.copyOf(requests);
    }

    @JsonIgnore
    public List<TranscodeRequest> getRequests() {
        return requests;
    }
}
