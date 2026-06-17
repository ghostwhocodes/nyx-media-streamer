package com.nyx.media.contracts;

import com.fasterxml.jackson.databind.node.ObjectNode;

public record MediaObjectCorrelationPayload(
    String correlationId,
    ObjectNode payload,
    String createdAt,
    String updatedAt
) {
}
