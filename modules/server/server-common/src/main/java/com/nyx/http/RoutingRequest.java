package com.nyx.http;

import io.javalin.http.Context;

public final class RoutingRequest {
    private final Parameters headers;
    private final Parameters queryParameters;

    public RoutingRequest(Context context) {
        this.headers = new Parameters(context::header);
        this.queryParameters = new Parameters(context::queryParam);
    }

    public Parameters getHeaders() {
        return headers;
    }

    public Parameters getQueryParameters() {
        return queryParameters;
    }
}
