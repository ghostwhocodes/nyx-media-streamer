package com.nyx.media.contracts;

public record MediaObjectCorrelation(
    String correlationId,
    String objectId,
    String kind,
    String createdAt,
    String updatedAt
) {
}
