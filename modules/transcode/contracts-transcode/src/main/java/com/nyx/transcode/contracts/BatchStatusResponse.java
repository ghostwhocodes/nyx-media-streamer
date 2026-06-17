package com.nyx.transcode.contracts;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record BatchStatusResponse(
    @JsonProperty("batch_id") String batchId,
    @JsonProperty("total") int total,
    @JsonProperty("pending") int pending,
    @JsonProperty("running") int running,
    @JsonProperty("completed") int completed,
    @JsonProperty("failed") int failed,
    @JsonProperty("cancelled") int cancelled,
    @JsonProperty("failed_jobs") List<TranscodeJob> failedJobs
) {
    public BatchStatusResponse {
        failedJobs = failedJobs == null ? List.of() : List.copyOf(failedJobs);
    }

    @JsonIgnore
    public String getBatchId() {
        return batchId;
    }

    @JsonIgnore
    public int getTotal() {
        return total;
    }

    @JsonIgnore
    public int getPending() {
        return pending;
    }

    @JsonIgnore
    public int getRunning() {
        return running;
    }

    @JsonIgnore
    public int getCompleted() {
        return completed;
    }

    @JsonIgnore
    public int getFailed() {
        return failed;
    }

    @JsonIgnore
    public int getCancelled() {
        return cancelled;
    }

    @JsonIgnore
    public List<TranscodeJob> getFailedJobs() {
        return failedJobs;
    }
}
