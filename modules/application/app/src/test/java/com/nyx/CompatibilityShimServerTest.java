package com.nyx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.config.FfmpegConfig;
import com.nyx.config.DatabaseConfig;
import com.nyx.config.MediaRootConfig;
import com.nyx.config.ServerConfig;
import io.javalin.Javalin;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.NoSuchFileException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class CompatibilityShimServerTest {
    @TempDir
    Path tempDir;

    @Test
    void failedStartAttemptsImmediateCleanup() {
        FakeServerLifecycle lifecycle = new FakeServerLifecycle(new RuntimeException("compatibility bind failed"), null);
        CompatibilityShimServer server = new CompatibilityShimServer(
            "qloud",
            "127.0.0.1",
            8081,
            Javalin.create(),
            lifecycle
        );

        RuntimeException error = assertThrows(RuntimeException.class, server::start);

        assertEquals("compatibility bind failed", error.getMessage());
        assertEquals(1, lifecycle.startCalls());
        assertEquals(1, lifecycle.stopCalls());
        assertFalse(server.started());

        server.stop();
        assertEquals(1, lifecycle.stopCalls());
    }

    @Test
    void failedStartWithCleanupFailureRequiresManualCleanupBeforeRestart() {
        Javalin app = Javalin.create();
        FakeServerLifecycle lifecycle = new FakeServerLifecycle(
            new RuntimeException("compatibility bind failed"),
            new RuntimeException("cleanup failed")
        );
        CompatibilityShimServer server = new CompatibilityShimServer("qloud", "127.0.0.1", 8081, app, lifecycle);

        assertEquals("qloud", server.id());
        assertEquals("127.0.0.1", server.host());
        assertEquals(8081, server.configuredPort());
        assertSame(app, server.app());
        assertEquals(8081, server.port());

        RuntimeException error = assertThrows(RuntimeException.class, server::start);

        assertEquals("compatibility bind failed", error.getMessage());
        assertEquals(1, error.getSuppressed().length);
        assertEquals("cleanup failed", error.getSuppressed()[0].getMessage());

        IllegalStateException restartError = assertThrows(IllegalStateException.class, server::start);
        assertTrue(restartError.getMessage().contains("requires cleanup"));
    }

    @Test
    void repeatedStartIsIdempotentAfterSuccessfulStartup() {
        FakeServerLifecycle lifecycle = new FakeServerLifecycle(null, null);
        CompatibilityShimServer server = new CompatibilityShimServer(
            "qloud",
            "127.0.0.1",
            8081,
            Javalin.create(),
            lifecycle
        );

        server.start();
        server.start();

        assertEquals(1, lifecycle.startCalls());
        server.stop();
    }

    @Test
    void postAllocationStartupFailureRunsSharedShutdownAndPreservesCleanupFailure() {
        AtomicInteger shutdownSteps = new AtomicInteger(0);

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
            AppRouting.runPostAllocationStartup(
                () -> {
                    throw new IllegalStateException("config init failed");
                },
                () -> {
                    shutdownSteps.incrementAndGet();
                    throw new IllegalStateException("cleanup failed");
                }
            )
        );

        assertEquals("config init failed", error.getMessage());
        assertEquals(1, shutdownSteps.get());
        assertEquals(1, error.getSuppressed().length);
        assertEquals("cleanup failed", error.getSuppressed()[0].getMessage());
    }

    @Test
    void preRouteConstructionFailureCleansUpSharedRuntimeResources() throws Exception {
        long baselineHikariThreads = countThreadsByPrefix("HikariPool-");
        long baselineCleanupThreads = countThreadsByPrefix("nyx-cache-cleanup");

        ServerConfig config = testConfig(0, 0, true);
        ConcurrentHashMap<String, String> runtimeUsers = new ConcurrentHashMap<>(config.getAuth().getUsers());
        AppComponent appComponent = DaggerAppComponent.factory().create(config, runtimeUsers);
        AppRouting.AppRuntimeDependencies dependencies = AppRouting.createRuntimeDependencies(
            config,
            runtimeUsers,
            appComponent.cleanupScheduler(),
            appComponent.backgroundExecutor(),
            appComponent.metricsRegistry(),
            appComponent.metricsService()
        );
        AtomicBoolean shutdownRan = new AtomicBoolean(false);
        Runnable sharedShutdownAction = AppRouting.shutdownAction(
            dependencies,
            LoggerFactory.getLogger(CompatibilityShimServerTest.class)
        );
        Runnable shutdownAction = () -> {
            shutdownRan.set(true);
            sharedShutdownAction.run();
        };

        try {
            IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                AppRouting.runStartupWithFailureCleanup(() -> {
                    throw new IllegalStateException("route construction failed");
                }, shutdownAction)
            );

            assertEquals("route construction failed", error.getMessage());
            assertTrue(shutdownRan.get());
            awaitThreadCount("HikariPool-", baselineHikariThreads);
            awaitThreadCount("nyx-cache-cleanup", baselineCleanupThreads);
        } finally {
            shutdownAction.run();
        }
    }

    @Test
    void applicationServerGroupStartIsIdempotentAndRejectsRestartAfterClosure() {
        FakeServerLifecycle compatibilityLifecycle = new FakeServerLifecycle(null, null);
        CompatibilityShimServer compatibilityServer = new CompatibilityShimServer(
            "qloud",
            "127.0.0.1",
            8081,
            Javalin.create(),
            compatibilityLifecycle
        );
        FakeMainAppLifecycle mainLifecycle = new FakeMainAppLifecycle(null, null);
        ApplicationServerGroup group = new ApplicationServerGroup(
            Javalin.create(),
            List.of(compatibilityServer),
            () -> { },
            mainLifecycle
        );

        group.start();
        group.start();

        assertEquals(1, compatibilityLifecycle.startCalls());
        assertEquals(1, mainLifecycle.startCalls());

        group.stop();
        group.stop();

        assertTrue(group.closed());
        assertEquals(1, compatibilityLifecycle.stopCalls());
        assertEquals(1, mainLifecycle.stopCalls());

        IllegalStateException restartError = assertThrows(IllegalStateException.class, group::start);
        assertEquals("ApplicationServerGroup is already closed", restartError.getMessage());

        ApplicationServerGroup stoppingGroup = new ApplicationServerGroup(
            Javalin.create(),
            List.of(),
            () -> { },
            new FakeMainAppLifecycle(null, null)
        );
        setAtomicBooleanField(stoppingGroup, "coordinatedStopInProgress", true);
        IllegalStateException stoppingError = assertThrows(IllegalStateException.class, stoppingGroup::start);
        assertEquals("ApplicationServerGroup is stopping", stoppingError.getMessage());

        stoppingGroup.stop();
        assertFalse(stoppingGroup.closed());
    }

    @Test
    void startupFailureAddsSharedShutdownFailureAsSuppressedAndPreservesOriginalError() {
        FakeServerLifecycle compatibilityLifecycle = new FakeServerLifecycle(null, null);
        CompatibilityShimServer compatibilityServer = new CompatibilityShimServer(
            "qloud",
            "127.0.0.1",
            8081,
            Javalin.create(),
            compatibilityLifecycle
        );
        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
            new ApplicationServerGroup(
                Javalin.create(),
                List.of(compatibilityServer),
                () -> {
                    throw new IllegalStateException("shared shutdown failed");
                },
                new FakeMainAppLifecycle(new IllegalStateException("main start failed"), null)
            ).start()
        );

        assertEquals("main start failed", error.getMessage());
        assertEquals(1, error.getSuppressed().length);
        assertEquals("shared shutdown failed", error.getSuppressed()[0].getMessage());
        assertEquals(1, compatibilityLifecycle.stopCalls());
    }

    @Test
    void createApplicationServerGroupStartsAndStopsEnabledCompatibilityListener() throws Exception {
        ServerConfig config = testConfig(0, 0, true);
        try (ApplicationServerGroup group = AppRouting.createApplicationServerGroup(config)) {
            assertEquals(1, group.compatibilityServers().size());

            group.start();

            CompatibilityShimServer qloud = group.compatibilityServers().getFirst();
            assertTrue(qloud.started());
            assertNotEquals(0, group.mainApp().port());
            assertNotEquals(0, qloud.port());
            assertNotEquals(group.mainApp().port(), qloud.port());
            assertTrue(portOpen("127.0.0.1", group.mainApp().port()));
            assertTrue(portOpen("127.0.0.1", qloud.port()));
        }
    }

    @Test
    void applicationServerGroupInternalLifecycleCallbacksResetPendingFlagsAndPropagateFailures() {
        CompatibilityShimServer failingCompatibilityServer = new CompatibilityShimServer(
            "qloud",
            "127.0.0.1",
            8081,
            Javalin.create(),
            new FakeServerLifecycle(null, new RuntimeException("compatibility stop failed"))
        );
        ApplicationServerGroup stoppingFailureGroup = new ApplicationServerGroup(
            Javalin.create(),
            List.of(failingCompatibilityServer),
            () -> { },
            new FakeMainAppLifecycle(null, null)
        );
        setBooleanField(failingCompatibilityServer, "cleanupRequired", true);
        setBooleanField(stoppingFailureGroup, "mainStartAttempted", true);

        RuntimeException stopFailure = assertThrows(
            RuntimeException.class,
            () -> invokePrivateVoid(stoppingFailureGroup, "handleExternalMainAppStopping")
        );
        assertEquals("compatibility stop failed", stopFailure.getMessage());

        ApplicationServerGroup stopFailedGroup = new ApplicationServerGroup(
            Javalin.create(),
            List.of(),
            () -> { },
            new FakeMainAppLifecycle(null, null)
        );
        setBooleanField(stopFailedGroup, "externalMainStopPendingShutdown", true);
        invokePrivateVoid(stopFailedGroup, "handleExternalMainAppStopFailed");
        assertFalse(booleanField(stopFailedGroup, "externalMainStopPendingShutdown"));

        setBooleanField(stopFailedGroup, "externalMainStopPendingShutdown", true);
        setAtomicBooleanField(stopFailedGroup, "coordinatedStopInProgress", true);
        invokePrivateVoid(stopFailedGroup, "handleExternalMainAppStopFailed");
        assertTrue(booleanField(stopFailedGroup, "externalMainStopPendingShutdown"));

        ApplicationServerGroup shutdownFailureGroup = new ApplicationServerGroup(
            Javalin.create(),
            List.of(),
            () -> {
                throw new RuntimeException("shutdown failed");
            },
            new FakeMainAppLifecycle(null, null)
        );
        setBooleanField(shutdownFailureGroup, "externalMainStopPendingShutdown", true);

        RuntimeException shutdownFailure = assertThrows(
            RuntimeException.class,
            () -> invokePrivateVoid(shutdownFailureGroup, "handleExternalMainAppStopped")
        );
        assertEquals("shutdown failed", shutdownFailure.getMessage());
        assertFalse(booleanField(shutdownFailureGroup, "externalMainStopPendingShutdown"));
    }

    @Test
    void applicationServerGroupHelperMethodsPreserveSuppressedFailuresAndWrapCheckedExceptions() {
        RuntimeException existing = new RuntimeException("existing");
        RuntimeException followup = new RuntimeException("followup");

        Throwable combined = invokePrivateStatic(
            ApplicationServerGroup.class,
            "appendFailure",
            new Class<?>[] {Throwable.class, Throwable.class},
            existing,
            followup
        );
        assertSame(existing, combined);
        assertEquals(1, existing.getSuppressed().length);
        assertEquals("followup", existing.getSuppressed()[0].getMessage());

        Throwable returnedFailure = invokePrivateStatic(
            ApplicationServerGroup.class,
            "appendFailure",
            new Class<?>[] {Throwable.class, Throwable.class},
            null,
            followup
        );
        assertSame(followup, returnedFailure);

        AssertionError fatal = assertThrows(
            AssertionError.class,
            () -> invokePrivateStatic(
                ApplicationServerGroup.class,
                "throwUnchecked",
                new Class<?>[] {Throwable.class},
                new AssertionError("fatal")
            )
        );
        assertEquals("fatal", fatal.getMessage());

        IllegalStateException wrapped = assertThrows(
            IllegalStateException.class,
            () -> invokePrivateStatic(
                ApplicationServerGroup.class,
                "throwUnchecked",
                new Class<?>[] {Throwable.class},
                new IOException("checked")
            )
        );
        assertEquals("Unexpected checked exception in application lifecycle", wrapped.getMessage());
        assertTrue(wrapped.getCause() instanceof IOException);
    }

    @Test
    void mainAppStopShutsDownCompatibilityListener() throws Exception {
        ServerConfig config = testConfig(0, 0, true);
        try (ApplicationServerGroup group = AppRouting.createApplicationServerGroup(
            config,
            new ConcurrentHashMap<>(config.getAuth().getUsers())
        )) {
            group.start();

            CompatibilityShimServer qloud = group.compatibilityServers().getFirst();
            int qloudPort = qloud.port();

            group.mainApp().stop();

            awaitPortClosed("127.0.0.1", qloudPort);
            assertFalse(qloud.started());
        }
    }

    @Test
    void disabledCompatibilityModeStartsOnlyTheMainListener() throws Exception {
        ServerConfig config = testConfig(0, 0, false);
        try (ApplicationServerGroup group = AppRouting.createApplicationServerGroup(
            config,
            new ConcurrentHashMap<>(config.getAuth().getUsers())
        )) {
            assertTrue(group.compatibilityServers().isEmpty());

            group.start();

            assertNotEquals(0, group.mainApp().port());
            assertTrue(portOpen("127.0.0.1", group.mainApp().port()));
        }
    }

    @Test
    void startupFailureAfterCompatibilityListenerStartsCleansItUp() throws Exception {
        int occupiedMainPort;
        try (ServerSocket conflict = new ServerSocket(0, 1)) {
            conflict.setReuseAddress(true);
            occupiedMainPort = conflict.getLocalPort();

            int compatibilityPort = allocateFreePort();
            ServerConfig config = testConfig(occupiedMainPort, compatibilityPort, true);
            ApplicationServerGroup group = AppRouting.createApplicationServerGroup(
                config,
                new ConcurrentHashMap<>(config.getAuth().getUsers())
            );

            RuntimeException error = assertThrows(RuntimeException.class, group::start);
            assertTrue(error.getMessage().contains(Integer.toString(occupiedMainPort)));

            CompatibilityShimServer qloud = group.compatibilityServers().getFirst();
            awaitPortClosed("127.0.0.1", compatibilityPort);
            assertFalse(qloud.started());

            group.close();
        }
    }

    @Test
    void compatibilityListenerPortConflictCleansUpFailedStart() throws Exception {
        int occupiedCompatibilityPort = -1;
        try (ServerSocket conflict = new ServerSocket(0, 1)) {
            conflict.setReuseAddress(true);
            occupiedCompatibilityPort = conflict.getLocalPort();

            ServerConfig config = testConfig(0, occupiedCompatibilityPort, true);
            ApplicationServerGroup group = AppRouting.createApplicationServerGroup(
                config,
                new ConcurrentHashMap<>(config.getAuth().getUsers())
            );

            RuntimeException error = assertThrows(RuntimeException.class, group::start);
            assertTrue(error.getMessage().contains(Integer.toString(occupiedCompatibilityPort)));

            CompatibilityShimServer qloud = group.compatibilityServers().getFirst();
            assertFalse(qloud.started());

            group.close();
        }

        try (ServerSocket rebound = new ServerSocket()) {
            rebound.setReuseAddress(true);
            rebound.bind(new InetSocketAddress("127.0.0.1", occupiedCompatibilityPort));
        }
    }

    @Test
    void stopPropagatesCompatibilityListenerStopFailuresAndSkipsSharedShutdown() {
        FakeServerLifecycle compatibilityLifecycle = new FakeServerLifecycle(null, new RuntimeException("compatibility stop failed"));
        CompatibilityShimServer compatibilityServer = new CompatibilityShimServer(
            "qloud",
            "127.0.0.1",
            8081,
            Javalin.create(),
            compatibilityLifecycle
        );
        AtomicBoolean shutdownRan = new AtomicBoolean(false);
        ApplicationServerGroup group = new ApplicationServerGroup(
            Javalin.create(),
            List.of(compatibilityServer),
            () -> shutdownRan.set(true),
            new FakeMainAppLifecycle(null, null)
        );

        group.start();

        RuntimeException error = assertThrows(RuntimeException.class, group::stop);

        assertEquals("compatibility stop failed", error.getMessage());
        assertFalse(shutdownRan.get());
        assertTrue(compatibilityServer.started());
    }

    @Test
    void stopPropagatesMainListenerStopFailuresAndSkipsSharedShutdown() {
        FakeServerLifecycle compatibilityLifecycle = new FakeServerLifecycle(null, null);
        CompatibilityShimServer compatibilityServer = new CompatibilityShimServer(
            "qloud",
            "127.0.0.1",
            8081,
            Javalin.create(),
            compatibilityLifecycle
        );
        AtomicBoolean shutdownRan = new AtomicBoolean(false);
        ApplicationServerGroup group = new ApplicationServerGroup(
            Javalin.create(),
            List.of(compatibilityServer),
            () -> shutdownRan.set(true),
            new FakeMainAppLifecycle(null, new RuntimeException("main stop failed"))
        );

        group.start();

        RuntimeException error = assertThrows(RuntimeException.class, group::stop);

        assertEquals("main stop failed", error.getMessage());
        assertFalse(shutdownRan.get());
        assertFalse(compatibilityServer.started());
    }

    @Test
    void startupFailureCleanupPropagatesMainListenerStopFailuresAndSkipsSharedShutdown() {
        FakeServerLifecycle compatibilityLifecycle = new FakeServerLifecycle(null, null);
        CompatibilityShimServer compatibilityServer = new CompatibilityShimServer(
            "qloud",
            "127.0.0.1",
            8081,
            Javalin.create(),
            compatibilityLifecycle
        );
        AtomicBoolean shutdownRan = new AtomicBoolean(false);
        ApplicationServerGroup group = new ApplicationServerGroup(
            Javalin.create(),
            List.of(compatibilityServer),
            () -> shutdownRan.set(true),
            new FakeMainAppLifecycle(new RuntimeException("main start failed"), new RuntimeException("main stop failed"))
        );

        RuntimeException error = assertThrows(RuntimeException.class, group::start);

        assertEquals("main start failed", error.getMessage());
        assertEquals(1, error.getSuppressed().length);
        assertEquals("main stop failed", error.getSuppressed()[0].getMessage());
        assertFalse(shutdownRan.get());
        assertFalse(compatibilityServer.started());
    }

    @Test
    void preListenerStartupHealthCheckFailureCleansUpSharedRuntimeResources() throws Exception {
        long baselineHikariThreads = countThreadsByPrefix("HikariPool-");
        long baselineCleanupThreads = countThreadsByPrefix("nyx-cache-cleanup");

        Path fakeFfmpeg = writeFailingFfmpegBinary(tempDir.resolve("fake-ffmpeg"));
        ServerConfig config = testConfig(
            0,
            0,
            true,
            AppTestData.testFfmpegConfig(
                fakeFfmpeg.toString(),
                fakeFfmpeg.toString(),
                "99.0",
                2,
                4,
                8,
                FfmpegConfig.DEFAULT_QUALITY_PRESETS,
                "polling",
                500L
            )
        );

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
            AppRouting.createApplicationServerGroup(
                config,
                new ConcurrentHashMap<>(config.getAuth().getUsers())
            )
        );

        assertTrue(error.getMessage().contains("below minimum required 99.0"));
        awaitThreadCount("HikariPool-", baselineHikariThreads);
        awaitThreadCount("nyx-cache-cleanup", baselineCleanupThreads);
    }

    @Test
    void tlsConfigurationFailureClosesPreparedApplicationServerGroup() throws Exception {
        long baselineHikariThreads = countThreadsByPrefix("HikariPool-");
        long baselineCleanupThreads = countThreadsByPrefix("nyx-cache-cleanup");

        Path ffmpeg = writeSuccessfulBinary(tempDir.resolve("ffmpeg-ok"), "ffmpeg");
        Path ffprobe = writeSuccessfulBinary(tempDir.resolve("ffprobe-ok"), "ffprobe");
        Path mediaRoot = Files.createDirectories(tempDir.resolve("tls-media"));
        Path dbDir = Files.createDirectories(tempDir.resolve("tls-db"));
        Path missingKeystore = tempDir.resolve("missing-keystore.jks");
        ServerConfig config = AppTestData.testServerConfig(
            "127.0.0.1",
            0,
            List.of(),
            List.of(new MediaRootConfig(mediaRoot, "local", "library")),
            AppTestData.testFfmpegConfig(
                ffmpeg.toString(),
                ffprobe.toString(),
                "6.0",
                2,
                4,
                8,
                FfmpegConfig.DEFAULT_QUALITY_PRESETS,
                "polling",
                500L
            ),
            AppTestData.testTranscodeConfig(),
            new DatabaseConfig(dbDir),
            new com.nyx.config.ThumbnailConfig(),
            new com.nyx.config.AudioConfig(),
            AppTestData.testAuthConfig(),
            new com.nyx.config.RateLimitConfig(),
            new com.nyx.config.CsrfConfig(),
            new com.nyx.config.TlsConfig(true, missingKeystore.toString(), "password", "nyx", "password", 0),
            new com.nyx.config.WebhookConfig(),
            new com.nyx.config.QuotaConfig(),
            new com.nyx.config.BackupConfig(),
            new com.nyx.config.StorageConfig(),
            AppTestData.disabledCompatibilityConfig()
        );

        ApplicationServerGroup group = AppRouting.createApplicationServerGroup(
            config,
            new ConcurrentHashMap<>(config.getAuth().getUsers())
        );
        try {
            Exception error = assertThrows(Exception.class, () -> Main.startApplication(group, config));

            assertTrue(error instanceof NoSuchFileException);
            assertTrue(group.closed());
            awaitThreadCount("HikariPool-", baselineHikariThreads);
            awaitThreadCount("nyx-cache-cleanup", baselineCleanupThreads);
        } finally {
            if (!group.closed()) {
                group.close();
            }
        }
    }

    private ServerConfig testConfig(int mainPort, int compatibilityPort, boolean compatibilityEnabled) throws IOException {
        return testConfig(mainPort, compatibilityPort, compatibilityEnabled, AppTestData.testFfmpegConfig());
    }

    private ServerConfig testConfig(
        int mainPort,
        int compatibilityPort,
        boolean compatibilityEnabled,
        com.nyx.config.FfmpegConfig ffmpegConfig
    ) throws IOException {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media-" + mainPort + "-" + compatibilityPort));
        Path dbDir = Files.createDirectories(tempDir.resolve("db-" + mainPort + "-" + compatibilityPort));
        return AppTestData.testServerConfig(
            "127.0.0.1",
            mainPort,
            List.of(),
            List.of(new MediaRootConfig(mediaRoot, "local", "library")),
            ffmpegConfig,
            AppTestData.testTranscodeConfig(),
            new DatabaseConfig(dbDir),
            new com.nyx.config.ThumbnailConfig(),
            new com.nyx.config.AudioConfig(),
            AppTestData.testAuthConfig(),
            new com.nyx.config.RateLimitConfig(),
            new com.nyx.config.CsrfConfig(),
            new com.nyx.config.TlsConfig(),
            new com.nyx.config.WebhookConfig(),
            new com.nyx.config.QuotaConfig(),
            new com.nyx.config.BackupConfig(),
            new com.nyx.config.StorageConfig(),
            new com.nyx.config.CompatibilityConfig(
                new com.nyx.config.QloudCompatibilityConfig(compatibilityEnabled, "127.0.0.1", compatibilityPort)
            )
        );
    }

    private static int allocateFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static boolean portOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 250);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void awaitPortClosed(String host, int port) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            if (!portOpen(host, port)) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("Port stayed open: " + host + ":" + port);
    }

    private static long countThreadsByPrefix(String prefix) {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(Thread::isAlive)
            .filter(thread -> thread.getName().startsWith(prefix))
            .count();
    }

    private static void awaitThreadCount(String prefix, long expectedCount) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            if (countThreadsByPrefix(prefix) == expectedCount) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError(
            "Thread count for prefix '" + prefix + "' stayed at " + countThreadsByPrefix(prefix) + " instead of " + expectedCount
        );
    }

    private static boolean booleanField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getBoolean(target);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void setBooleanField(Object target, String fieldName, boolean value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.setBoolean(target, value);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void setAtomicBooleanField(Object target, String fieldName, boolean value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            ((AtomicBoolean) field.get(target)).set(value);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void invokePrivateVoid(Object target, String methodName) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(target);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error fatalError) {
                throw fatalError;
            }
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T invokePrivateStatic(
        Class<?> owner,
        String methodName,
        Class<?>[] parameterTypes,
        Object... arguments
    ) {
        try {
            Method method = owner.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return (T) method.invoke(null, arguments);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error fatalError) {
                throw fatalError;
            }
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private static Path writeFailingFfmpegBinary(Path scriptPath) throws IOException {
        Files.writeString(
            scriptPath,
            """
                #!/bin/sh
                case "$1" in
                  -version)
                    echo "ffmpeg version 1.0"
                    ;;
                  -hwaccels)
                    echo "Hardware acceleration methods:"
                    ;;
                  -encoders)
                    echo "------"
                    ;;
                esac
                """
        );
        if (!scriptPath.toFile().setExecutable(true)) {
            throw new IllegalStateException("Failed to mark fake ffmpeg script executable: " + scriptPath);
        }
        return scriptPath;
    }

    private static Path writeSuccessfulBinary(Path scriptPath, String binaryName) throws IOException {
        Files.writeString(
            scriptPath,
            """
                #!/bin/sh
                case "$1" in
                  -version)
                    echo "%s version 99.0"
                    ;;
                  -hwaccels)
                    echo "Hardware acceleration methods:"
                    echo "vaapi"
                    ;;
                  -encoders)
                    echo "------"
                    echo " V..... h264"
                    ;;
                esac
                """.formatted(binaryName)
        );
        if (!scriptPath.toFile().setExecutable(true)) {
            throw new IllegalStateException("Failed to mark fake binary executable: " + scriptPath);
        }
        return scriptPath;
    }

    private static final class FakeServerLifecycle implements CompatibilityShimServer.ServerLifecycle {
        private final RuntimeException startFailure;
        private final RuntimeException stopFailure;
        private int startCalls;
        private int stopCalls;

        private FakeServerLifecycle(RuntimeException startFailure, RuntimeException stopFailure) {
            this.startFailure = startFailure;
            this.stopFailure = stopFailure;
        }

        @Override
        public void start(String host, int port) {
            startCalls++;
            if (startFailure != null) {
                throw startFailure;
            }
        }

        @Override
        public void stop() {
            stopCalls++;
            if (stopFailure != null) {
                throw stopFailure;
            }
        }

        @Override
        public int port() {
            return 0;
        }

        int startCalls() {
            return startCalls;
        }

        int stopCalls() {
            return stopCalls;
        }
    }

    private static final class FakeMainAppLifecycle implements ApplicationServerGroup.MainAppLifecycle {
        private final RuntimeException startFailure;
        private final RuntimeException stopFailure;
        private int startCalls;
        private int stopCalls;

        private FakeMainAppLifecycle(RuntimeException startFailure, RuntimeException stopFailure) {
            this.startFailure = startFailure;
            this.stopFailure = stopFailure;
        }

        @Override
        public void start() {
            startCalls++;
            if (startFailure != null) {
                throw startFailure;
            }
        }

        @Override
        public void stop() {
            stopCalls++;
            if (stopFailure != null) {
                throw stopFailure;
            }
        }

        int startCalls() {
            return startCalls;
        }

        int stopCalls() {
            return stopCalls;
        }
    }
}
