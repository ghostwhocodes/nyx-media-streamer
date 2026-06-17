package com.nyx.transcode.contracts;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.nyx.stream.representation.contracts.StreamRepresentation;
import java.time.Instant;
import java.util.List;

public record TranscodeJob(
    @JsonProperty("id") String id,
    @JsonProperty("status") JobStatus status,
    @JsonProperty("input_path") String inputPath,
    @JsonProperty("profile") String profile,
    @JsonProperty("representation") StreamRepresentation representation,
    @JsonProperty("representations") List<TranscodeRepresentation> representations,
    @JsonIgnore TranscodeExecutionMode executionMode,
    @JsonIgnore String specKey,
    @JsonProperty("segments_produced") int segmentsProduced,
    @JsonProperty("retry_count") int retryCount,
    @JsonProperty("stderr_initial") String stderrInitial,
    @JsonProperty("stderr_fallback") String stderrFallback,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("updated_at") Instant updatedAt,
    @JsonProperty("completed_at") Instant completedAt,
    @JsonProperty("manifest_url") String manifestUrl,
    @JsonProperty("hls_url") String hlsUrl,
    @JsonProperty("progress_url") String progressUrl,
    @JsonProperty("batch_id") String batchId,
    @JsonIgnore String owner,
    @JsonIgnore long outputSizeBytes
) {
    public TranscodeJob(
        String id,
        JobStatus status,
        String inputPath,
        String profile,
        StreamRepresentation representation
    ) {
        this(
            id,
            status,
            inputPath,
            profile,
            representation,
            List.of(),
            TranscodeExecutionMode.VIDEO_TRANSCODE,
            null,
            0,
            0,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            0L
        );
    }

    public TranscodeJob {
        representation = representation == null ? StreamRepresentation.HLS_DASH_FMP4 : representation;
        representations = representations == null ? List.of() : List.copyOf(representations);
        executionMode = executionMode == null ? TranscodeExecutionMode.VIDEO_TRANSCODE : executionMode;
        specKey = specKey == null
            ? TranscodeContracts.buildTranscodeSpecKey(
                inputPath,
                null,
                profile,
                representation,
                executionMode,
                representations,
                "extract",
                null,
                "all",
                "auto"
            )
            : specKey;
    }

    @JsonIgnore
    public String getId() {
        return id;
    }

    @JsonIgnore
    public JobStatus getStatus() {
        return status;
    }

    @JsonIgnore
    public String getInputPath() {
        return inputPath;
    }

    @JsonIgnore
    public String getProfile() {
        return profile;
    }

    @JsonIgnore
    public StreamRepresentation getRepresentation() {
        return representation;
    }

    @JsonIgnore
    public List<TranscodeRepresentation> getRepresentations() {
        return representations;
    }

    @JsonIgnore
    public TranscodeExecutionMode getExecutionMode() {
        return executionMode;
    }

    @JsonIgnore
    public String getSpecKey() {
        return specKey;
    }

    @JsonIgnore
    public int getSegmentsProduced() {
        return segmentsProduced;
    }

    @JsonIgnore
    public int getRetryCount() {
        return retryCount;
    }

    @JsonIgnore
    public String getStderrInitial() {
        return stderrInitial;
    }

    @JsonIgnore
    public String getStderrFallback() {
        return stderrFallback;
    }

    @JsonIgnore
    public Instant getCreatedAt() {
        return createdAt;
    }

    @JsonIgnore
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @JsonIgnore
    public Instant getCompletedAt() {
        return completedAt;
    }

    @JsonIgnore
    public String getManifestUrl() {
        return manifestUrl;
    }

    @JsonIgnore
    public String getHlsUrl() {
        return hlsUrl;
    }

    @JsonIgnore
    public String getProgressUrl() {
        return progressUrl;
    }

    @JsonIgnore
    public String getBatchId() {
        return batchId;
    }

    @JsonIgnore
    public String getOwner() {
        return owner;
    }

    @JsonIgnore
    public long getOutputSizeBytes() {
        return outputSizeBytes;
    }
}
