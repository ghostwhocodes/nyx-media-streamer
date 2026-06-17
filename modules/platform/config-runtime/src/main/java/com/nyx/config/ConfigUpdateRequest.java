package com.nyx.config;

import java.util.List;

public record ConfigUpdateRequest(
    List<String> corsOrigins
) {
    public ConfigUpdateRequest {
        corsOrigins = List.copyOf(corsOrigins);
    }
}
