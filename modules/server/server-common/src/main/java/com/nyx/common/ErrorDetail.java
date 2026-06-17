package com.nyx.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ErrorDetail(String code, String message, Map<String, String> details) {
    public ErrorDetail {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        details = details == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public ErrorDetail(String code, String message) {
        this(code, message, Map.of());
    }
}
