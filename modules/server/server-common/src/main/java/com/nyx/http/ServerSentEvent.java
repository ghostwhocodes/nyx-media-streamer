package com.nyx.http;

import java.util.Objects;

public final class ServerSentEvent {
    private final String data;
    private final String event;

    public ServerSentEvent() {
        this(null, null);
    }

    public ServerSentEvent(String data) {
        this(data, null);
    }

    public ServerSentEvent(String data, String event) {
        this.data = data;
        this.event = event;
    }

    public String getData() {
        return data;
    }

    public String getEvent() {
        return event;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ServerSentEvent that)) {
            return false;
        }
        return Objects.equals(data, that.data) && Objects.equals(event, that.event);
    }

    @Override
    public int hashCode() {
        return Objects.hash(data, event);
    }

    @Override
    public String toString() {
        return "ServerSentEvent(data=" + data + ", event=" + event + ")";
    }
}
