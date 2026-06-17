package com.nyx;

import io.javalin.Javalin;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ApplicationServerGroup implements AutoCloseable {
    private final Javalin mainApp;
    private final MainAppLifecycle mainAppLifecycle;
    private final List<CompatibilityShimServer> compatibilityServers;
    private final Runnable shutdownAction;
    private final AtomicBoolean shutdownComplete = new AtomicBoolean(false);
    private final AtomicBoolean coordinatedStopInProgress = new AtomicBoolean(false);
    private volatile boolean started;
    private volatile boolean mainStartAttempted;
    private volatile boolean externalMainStopPendingShutdown;

    ApplicationServerGroup(
        Javalin mainApp,
        List<CompatibilityShimServer> compatibilityServers,
        Runnable shutdownAction
    ) {
        this(mainApp, compatibilityServers, shutdownAction, new JavalinMainAppLifecycle(mainApp));
    }

    ApplicationServerGroup(
        Javalin mainApp,
        List<CompatibilityShimServer> compatibilityServers,
        Runnable shutdownAction,
        MainAppLifecycle mainAppLifecycle
    ) {
        this.mainApp = mainApp;
        this.mainAppLifecycle = mainAppLifecycle;
        this.compatibilityServers = List.copyOf(compatibilityServers);
        this.shutdownAction = shutdownAction;
        this.mainApp.unsafe.events.serverStopping(this::handleExternalMainAppStopping);
        this.mainApp.unsafe.events.serverStopped(this::handleExternalMainAppStopped);
        this.mainApp.unsafe.events.serverStopFailed(this::handleExternalMainAppStopFailed);
    }

    public Javalin mainApp() {
        return mainApp;
    }

    List<CompatibilityShimServer> compatibilityServers() {
        return compatibilityServers;
    }

    boolean closed() {
        return shutdownComplete.get();
    }

    public void start() {
        if (shutdownComplete.get()) {
            throw new IllegalStateException("ApplicationServerGroup is already closed");
        }
        if (coordinatedStopInProgress.get()) {
            throw new IllegalStateException("ApplicationServerGroup is stopping");
        }
        if (started) {
            return;
        }
        try {
            for (CompatibilityShimServer compatibilityServer : compatibilityServers) {
                compatibilityServer.start();
            }
            mainStartAttempted = true;
            mainAppLifecycle.start();
            started = true;
        } catch (Throwable error) {
            if (coordinatedStopInProgress.compareAndSet(false, true)) {
                try {
                    Throwable stopFailure = stopCompatibilityServers();
                    if (mainStartAttempted) {
                        stopFailure = appendFailure(stopFailure, stopMainApplication());
                    }
                    if (stopFailure == null) {
                        finishShutdown();
                    } else {
                        error.addSuppressed(stopFailure);
                    }
                } catch (Throwable cleanupFailure) {
                    error.addSuppressed(cleanupFailure);
                } finally {
                    if (!shutdownComplete.get()) {
                        coordinatedStopInProgress.set(false);
                    }
                }
            }
            throwUnchecked(error);
        }
    }

    public void stop() {
        if (shutdownComplete.get()) {
            return;
        }
        try {
            if (!coordinatedStopInProgress.compareAndSet(false, true)) {
                return;
            }
            Throwable stopFailure = stopCompatibilityServers();
            if (mainStartAttempted) {
                stopFailure = appendFailure(stopFailure, stopMainApplication());
            }
            if (stopFailure != null) {
                throwUnchecked(stopFailure);
            }
            finishShutdown();
        } catch (Throwable error) {
            coordinatedStopInProgress.set(false);
            throwUnchecked(error);
        }
    }

    private void handleExternalMainAppStopping() {
        if (shutdownComplete.get() || coordinatedStopInProgress.get() || !mainStartAttempted) {
            return;
        }
        Throwable stopFailure = stopCompatibilityServers();
        if (stopFailure != null) {
            throwUnchecked(stopFailure);
        }
        externalMainStopPendingShutdown = true;
    }

    private void handleExternalMainAppStopped() {
        if (shutdownComplete.get() || coordinatedStopInProgress.get() || !externalMainStopPendingShutdown) {
            return;
        }
        try {
            finishShutdown();
        } catch (Throwable error) {
            externalMainStopPendingShutdown = false;
            throwUnchecked(error);
        }
    }

    private void handleExternalMainAppStopFailed() {
        if (coordinatedStopInProgress.get()) {
            return;
        }
        externalMainStopPendingShutdown = false;
    }

    private Throwable stopCompatibilityServers() {
        Throwable failure = null;
        for (CompatibilityShimServer compatibilityServer : compatibilityServers) {
            try {
                compatibilityServer.stop();
            } catch (Throwable error) {
                failure = appendFailure(failure, error);
            }
        }
        return failure;
    }

    private Throwable stopMainApplication() {
        try {
            mainAppLifecycle.stop();
            return null;
        } catch (Throwable error) {
            return error;
        }
    }

    private void finishShutdown() {
        shutdownAction.run();
        started = false;
        mainStartAttempted = false;
        externalMainStopPendingShutdown = false;
        shutdownComplete.set(true);
    }

    private static Throwable appendFailure(Throwable existing, Throwable failure) {
        if (failure == null) {
            return existing;
        }
        if (existing == null) {
            return failure;
        }
        existing.addSuppressed(failure);
        return existing;
    }

    private static void throwUnchecked(Throwable error) {
        if (error instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (error instanceof Error fatalError) {
            throw fatalError;
        }
        throw new IllegalStateException("Unexpected checked exception in application lifecycle", error);
    }

    @Override
    public void close() {
        stop();
    }

    interface MainAppLifecycle {
        void start();

        void stop();
    }

    private record JavalinMainAppLifecycle(Javalin app) implements MainAppLifecycle {
        @Override
        public void start() {
            app.start();
        }

        @Override
        public void stop() {
            app.stop();
        }
    }
}
