package com.nyx;

import io.javalin.Javalin;

final class CompatibilityShimServer {
    private final String id;
    private final String host;
    private final int configuredPort;
    private final Javalin app;
    private final ServerLifecycle lifecycle;
    private volatile boolean started;
    private volatile boolean cleanupRequired;

    CompatibilityShimServer(String id, String host, int configuredPort, Javalin app) {
        this(id, host, configuredPort, app, new JavalinServerLifecycle(app));
    }

    CompatibilityShimServer(
        String id,
        String host,
        int configuredPort,
        Javalin app,
        ServerLifecycle lifecycle
    ) {
        this.id = id;
        this.host = host;
        this.configuredPort = configuredPort;
        this.app = app;
        this.lifecycle = lifecycle;
    }

    String id() {
        return id;
    }

    String host() {
        return host;
    }

    int configuredPort() {
        return configuredPort;
    }

    Javalin app() {
        return app;
    }

    boolean started() {
        return started;
    }

    int port() {
        return started ? lifecycle.port() : configuredPort;
    }

    void start() {
        if (started) {
            return;
        }
        if (cleanupRequired) {
            throw new IllegalStateException("Compatibility shim server '" + id + "' requires cleanup before restart");
        }
        cleanupRequired = true;
        try {
            lifecycle.start(host, configuredPort);
            started = true;
        } catch (Throwable error) {
            started = false;
            try {
                lifecycle.stop();
                cleanupRequired = false;
            } catch (Throwable stopError) {
                error.addSuppressed(stopError);
            }
            throw error;
        }
    }

    void stop() {
        if (!cleanupRequired) {
            return;
        }
        lifecycle.stop();
        started = false;
        cleanupRequired = false;
    }

    interface ServerLifecycle {
        void start(String host, int port);

        void stop();

        int port();
    }

    private record JavalinServerLifecycle(Javalin app) implements ServerLifecycle {
        @Override
        public void start(String host, int port) {
            app.start(host, port);
        }

        @Override
        public void stop() {
            app.stop();
        }

        @Override
        public int port() {
            return app.port();
        }
    }
}
