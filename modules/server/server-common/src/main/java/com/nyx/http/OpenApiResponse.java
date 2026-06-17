package com.nyx.http;

import java.util.Objects;

public final class OpenApiResponse {
    private final int code;
    private final String description;

    public OpenApiResponse(int code) {
        this(code, null);
    }

    public OpenApiResponse(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OpenApiResponse that)) {
            return false;
        }
        return code == that.code && Objects.equals(description, that.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, description);
    }

    @Override
    public String toString() {
        return "OpenApiResponse(code=" + code + ", description=" + description + ")";
    }
}
