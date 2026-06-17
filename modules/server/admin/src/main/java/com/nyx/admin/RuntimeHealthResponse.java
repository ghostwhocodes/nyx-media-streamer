package com.nyx.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public record RuntimeHealthResponse(
    String status,
    boolean ffmpegAvailable,
    int activeJobs,
    boolean dbWritable,
    boolean diskSpaceWarning,
    boolean dbConnectivity,
    boolean stuckJobsWarning,
    @JsonProperty("circuit_breaker_open") boolean circuitBreakerOpen,
    String lastBackupTimestamp,
    Long lastBackupBytes,
    String serverVersion,
    Map<String, String> build
) {
    public RuntimeHealthResponse(
        String status,
        boolean ffmpegAvailable,
        int activeJobs,
        boolean dbWritable
    ) {
        this(status, ffmpegAvailable, activeJobs, dbWritable, false, true, false, false, null, null, null, null);
    }

    public RuntimeHealthResponse(
        String status,
        boolean ffmpegAvailable,
        int activeJobs,
        boolean dbWritable,
        boolean diskSpaceWarning,
        boolean dbConnectivity,
        boolean stuckJobsWarning,
        boolean circuitBreakerOpen,
        String lastBackupTimestamp,
        Long lastBackupBytes
    ) {
        this(
            status,
            ffmpegAvailable,
            activeJobs,
            dbWritable,
            diskSpaceWarning,
            dbConnectivity,
            stuckJobsWarning,
            circuitBreakerOpen,
            lastBackupTimestamp,
            lastBackupBytes,
            null,
            null
        );
    }

    public RuntimeHealthResponse {
        build = build == null ? Map.of() : Map.copyOf(build);
    }
}
