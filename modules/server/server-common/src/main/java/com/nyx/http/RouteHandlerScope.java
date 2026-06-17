package com.nyx.http;

public final class RouteHandlerScope {
    private final RoutingCall call;

    public RouteHandlerScope(RoutingCall call) {
        this.call = call;
    }

    public RoutingCall getCall() {
        return call;
    }
}
