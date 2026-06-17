package com.nyx.http;

import java.util.List;
import java.util.Objects;

public final class OpenApiOperation {
    private final String method;
    private final String path;
    private final String description;
    private final List<OpenApiParameter> parameters;
    private final boolean hasRequestBody;
    private final List<OpenApiResponse> responses;

    public OpenApiOperation(String method, String path) {
        this(method, path, null, List.of(), false, List.of());
    }

    public OpenApiOperation(
        String method,
        String path,
        String description,
        List<OpenApiParameter> parameters,
        boolean hasRequestBody,
        List<OpenApiResponse> responses
    ) {
        this.method = method;
        this.path = path;
        this.description = description;
        this.parameters = List.copyOf(parameters);
        this.hasRequestBody = hasRequestBody;
        this.responses = List.copyOf(responses);
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public String getDescription() {
        return description;
    }

    public List<OpenApiParameter> getParameters() {
        return parameters;
    }

    public boolean getHasRequestBody() {
        return hasRequestBody;
    }

    public List<OpenApiResponse> getResponses() {
        return responses;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OpenApiOperation that)) {
            return false;
        }
        return hasRequestBody == that.hasRequestBody
            && Objects.equals(method, that.method)
            && Objects.equals(path, that.path)
            && Objects.equals(description, that.description)
            && Objects.equals(parameters, that.parameters)
            && Objects.equals(responses, that.responses);
    }

    @Override
    public int hashCode() {
        return Objects.hash(method, path, description, parameters, hasRequestBody, responses);
    }

    @Override
    public String toString() {
        return "OpenApiOperation(method=" + method + ", path=" + path + ", description=" + description
            + ", parameters=" + parameters + ", hasRequestBody=" + hasRequestBody + ", responses=" + responses + ")";
    }
}
