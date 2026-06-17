package com.nyx.transcode.contracts;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record BatchSubmitResponse(
    @JsonProperty("batch_id") String batchId,
    @JsonProperty("jobs") List<TranscodeJob> jobs,
    @JsonProperty("errors") List<BatchJobError> errors
) {
    public BatchSubmitResponse(String batchId, List<TranscodeJob> jobs) {
        this(batchId, jobs, List.of());
    }

    public BatchSubmitResponse {
        jobs = jobs == null ? List.of() : List.copyOf(jobs);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    @JsonIgnore
    public String getBatchId() {
        return batchId;
    }

    @JsonIgnore
    public List<TranscodeJob> getJobs() {
        return jobs;
    }

    @JsonIgnore
    public List<BatchJobError> getErrors() {
        return errors;
    }
}
