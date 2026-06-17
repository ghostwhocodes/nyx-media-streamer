package com.nyx.http;

import io.javalin.http.Context;

public final class RoutingResponse {
    private final Context context;

    public RoutingResponse(Context context) {
        this.context = context;
    }

    public void header(String name, String value) {
        context.header(name, value);
    }

    public HttpStatusCode status() {
        return HttpStatusCode.fromJavalinStatus(context.status());
    }
}
