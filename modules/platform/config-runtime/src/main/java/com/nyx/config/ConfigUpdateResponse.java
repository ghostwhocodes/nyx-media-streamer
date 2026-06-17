package com.nyx.config;

import java.util.List;

public record ConfigUpdateResponse(
    SanitizedConfig config,
    boolean restartRequired,
    List<String> restartReasons
) {
    public ConfigUpdateResponse {
        restartReasons = List.copyOf(restartReasons);
    }

    public ConfigUpdateResponse(SanitizedConfig config, boolean restartRequired) {
        this(config, restartRequired, List.of());
    }
}
