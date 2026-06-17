package com.nyx.http;

import io.javalin.http.sse.SseClient;

public final class SseRouteHandlerScope {
    private final RoutingCall call;
    private final SseClient client;

    public SseRouteHandlerScope(RoutingCall call, SseClient client) {
        this.call = call;
        this.client = client;
    }

    public RoutingCall getCall() {
        return call;
    }

    public void send(ServerSentEvent event) {
        String payload = event.getData() == null ? "" : event.getData();
        if (event.getEvent() != null) {
            client.sendEvent(event.getEvent(), payload);
        } else {
            client.sendEvent(payload);
        }
    }
}
