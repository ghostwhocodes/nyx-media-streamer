package com.nyx.transcode.contracts;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

public record BatchJobError(
    @JsonProperty("input_path") String inputPath,
    @JsonProperty("error") String error
) {
    @JsonIgnore
    public String getInputPath() {
        return inputPath;
    }

    @JsonIgnore
    public String getError() {
        return error;
    }
}
