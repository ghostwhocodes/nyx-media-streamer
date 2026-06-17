package com.nyx.admin;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ReadinessResponse(
    String status,
    boolean dbWritable,
    boolean dbConnectivity,
    boolean diskSpaceOk,
    @JsonProperty("circuit_breaker_open") boolean circuitBreakerOpen
) {
    public ReadinessResponse(
        String status,
        boolean dbWritable,
        boolean dbConnectivity
    ) {
        this(status, dbWritable, dbConnectivity, true, false);
    }
}
