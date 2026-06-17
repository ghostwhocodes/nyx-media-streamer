package com.nyx.common;

import java.util.Objects;

public record ErrorResponse(ErrorDetail error) {
    public ErrorResponse {
        Objects.requireNonNull(error, "error");
    }
}
