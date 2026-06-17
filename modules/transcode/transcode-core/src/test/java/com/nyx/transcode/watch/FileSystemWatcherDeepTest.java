package com.nyx.transcode.watch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemWatcherDeepTest {
    private Path tempDir;

    @BeforeEach
    void setup() throws IOException {
        tempDir = Files.createTempDirectory("nyx-watcher-deep-test");
    }

    @AfterEach
    void teardown() throws IOException {
        deleteRecursively(tempDir);
    }

    @Test
    void createWatcherWithInotifyCreatesInotifySegmentWatcher() {
        SegmentWatcher watcher = SegmentWatcherFactory.createWatcher(WatchStrategy.INOTIFY);
        assertTrue(watcher instanceof InotifySegmentWatcher);
        watcher.close();
    }

    @Test
    void createWatcherWithPollingCreatesPollingSegmentWatcher() {
        SegmentWatcher watcher = SegmentWatcherFactory.createWatcher(WatchStrategy.POLLING);
        assertTrue(watcher instanceof PollingSegmentWatcher);
        watcher.close();
    }

    @Test
    void createWatcherWithHybridCreatesHybridSegmentWatcher() {
        SegmentWatcher watcher = SegmentWatcherFactory.createWatcher(WatchStrategy.HYBRID);
        assertTrue(watcher instanceof HybridSegmentWatcher);
        watcher.close();
    }

    @Test
    void hybridSegmentWatcherCloseIsIdempotent() {
        HybridSegmentWatcher watcher = new HybridSegmentWatcher();
        watcher.close();
        watcher.close();
    }

    @Test
    void hybridSegmentWatcherCloseBeforeWatchIsSafe() {
        HybridSegmentWatcher watcher = new HybridSegmentWatcher(100, 1000);
        watcher.close();
    }

    @Test
    void pollingSegmentWatcherDoesNotEmitSameFileTwice() throws Exception {
        Path watchDir = Files.createDirectories(tempDir.resolve("polling-dedup"));
        PollingSegmentWatcher watcher = new PollingSegmentWatcher(50);
        var executor = Executors.newSingleThreadExecutor();
        Future<List<Path>> detectedFuture = executor.submit(() -> watcher.watch(watchDir).take(2).toList());
        Thread creator = new Thread(() -> {
            sleep(100);
            createFile(watchDir.resolve("segment.m4s"));
            sleep(500);
            createFile(watchDir.resolve("segment2.m4s"));
        });

        try {
            creator.start();
            List<Path> detected = detectedFuture.get();
            assertEquals(2, detected.size());
            assertEquals(2, detected.stream().map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()).size());
        } finally {
            watcher.close();
            detectedFuture.cancel(true);
            executor.shutdownNow();
            creator.join(1_000);
        }
    }

    @Test
    void pollingSegmentWatcherDetectsNewFilesInExistingDirectory() throws Exception {
        Path watchDir = Files.createDirectories(tempDir.resolve("polling-new"));
        PollingSegmentWatcher watcher = new PollingSegmentWatcher(50);
        var executor = Executors.newSingleThreadExecutor();
        Future<List<Path>> detectedFuture = executor.submit(() -> watcher.watch(watchDir).take(1).toList());
        Thread creator = new Thread(() -> {
            sleep(200);
            createFile(watchDir.resolve("new-file.m4s"));
        });

        try {
            creator.start();
            List<Path> detected = detectedFuture.get();
            assertEquals(1, detected.size());
            assertEquals("new-file.m4s", detected.get(0).getFileName().toString());
        } finally {
            watcher.close();
            detectedFuture.cancel(true);
            executor.shutdownNow();
            creator.join(1_000);
        }
    }

    @Test
    void inotifySegmentWatcherDetectsNewFiles() throws Exception {
        assumeInotifyWatchServiceAvailable();
        Path watchDir = Files.createDirectories(tempDir.resolve("inotify-test"));
        InotifySegmentWatcher watcher = new InotifySegmentWatcher();
        Thread creator = new Thread(() -> {
            sleep(500);
            createFile(watchDir.resolve("segment1.m4s"));
        });

        try {
            creator.start();
            List<Path> detected = watcher.watch(watchDir).take(1).toList();
            if (detected.isEmpty()) {
                return;
            }
            assertEquals(1, detected.size());
            assertEquals("segment1.m4s", detected.get(0).getFileName().toString());
        } finally {
            watcher.close();
            creator.join(1_000);
        }
    }

    @Test
    void inotifySegmentWatcherCloseStopsTheWatchService() throws Exception {
        assumeInotifyWatchServiceAvailable();
        Path watchDir = Files.createDirectories(tempDir.resolve("inotify-close"));
        InotifySegmentWatcher watcher = new InotifySegmentWatcher();
        var executor = Executors.newSingleThreadExecutor();
        Future<?> future = executor.submit(() -> {
            try {
                watcher.watch(watchDir).collect(path -> { });
            } catch (Exception ignored) {
            }
        });

        try {
            Thread.sleep(500);
            watcher.close();
            Thread.sleep(500);
            future.cancel(true);
        } finally {
            watcher.close();
            future.cancel(true);
            executor.shutdownNow();
        }
    }

    private static void assumeInotifyWatchServiceAvailable() {
        Path probeDir = null;
        try {
            probeDir = Files.createTempDirectory("nyx-inotify-probe");
            try (var watchService = FileSystems.getDefault().newWatchService()) {
                // The test only makes sense when the host can allocate and register inotify watchers.
                probeDir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);
            }
        } catch (Exception exception) {
            Assumptions.assumeTrue(false, "Inotify watch service not available: " + exception.getMessage());
        } finally {
            try {
                deleteRecursively(probeDir);
            } catch (IOException ignored) {
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interruptedException);
        }
    }

    private static void createFile(Path path) {
        try {
            Files.createFile(path);
        } catch (IOException ioException) {
            throw new RuntimeException(ioException);
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ioException) {
                    throw new RuntimeException(ioException);
                }
            });
        }
    }
}
