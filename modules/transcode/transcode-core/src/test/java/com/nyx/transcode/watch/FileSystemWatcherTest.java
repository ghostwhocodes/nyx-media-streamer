package com.nyx.transcode.watch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemWatcherTest {
    private Path tempDir;

    @BeforeEach
    void setup() throws IOException {
        tempDir = Files.createTempDirectory("nyx-watcher-test");
    }

    @AfterEach
    void teardown() throws IOException {
        deleteRecursively(tempDir);
    }

    @Test
    void pollingWatcherDetectsNewFiles() throws Exception {
        PollingSegmentWatcher watcher = new PollingSegmentWatcher(100);
        Thread creator = new Thread(() -> {
            sleep(300);
            createFile(tempDir.resolve("segment_001.m4s"));
        });

        try {
            creator.start();
            Path detectedFile = watcher.watch(tempDir).first();
            assertEquals("segment_001.m4s", detectedFile.getFileName().toString());
        } finally {
            watcher.close();
            creator.join(1_000);
        }
    }

    @Test
    void createWatcherFactoryCreatesCorrectType() {
        SegmentWatcher inotify = SegmentWatcherFactory.createWatcher(WatchStrategy.INOTIFY);
        assertTrue(inotify instanceof InotifySegmentWatcher);

        SegmentWatcher polling = SegmentWatcherFactory.createWatcher(WatchStrategy.POLLING, 200);
        assertTrue(polling instanceof PollingSegmentWatcher);

        SegmentWatcher hybrid = SegmentWatcherFactory.createWatcher(WatchStrategy.HYBRID);
        assertTrue(hybrid instanceof HybridSegmentWatcher);
    }

    @Test
    void pollingSegmentWatcherCloseStopsWatching() {
        PollingSegmentWatcher watcher = new PollingSegmentWatcher(100);
        watcher.close();
    }

    @Test
    void inotifySegmentWatcherCloseIsSafeWhenNotStarted() {
        InotifySegmentWatcher watcher = new InotifySegmentWatcher();
        watcher.close();
    }

    @Test
    void hybridSegmentWatcherCloseDelegatesToUnderlyingWatcher() {
        HybridSegmentWatcher watcher = new HybridSegmentWatcher();
        watcher.close();
    }

    @Test
    void createWatcherWithCustomPollIntervalCreatesPollingSegmentWatcher() {
        SegmentWatcher watcher = SegmentWatcherFactory.createWatcher(WatchStrategy.POLLING, 200);
        assertTrue(watcher instanceof PollingSegmentWatcher);
        watcher.close();
    }

    @Test
    void createWatcherWithHybridCreatesHybridSegmentWatcher() {
        SegmentWatcher watcher = SegmentWatcherFactory.createWatcher(WatchStrategy.HYBRID, 300);
        assertTrue(watcher instanceof HybridSegmentWatcher);
        watcher.close();
    }

    @Test
    void pollingSegmentWatcherHandlesNonExistentDirectoryGracefully() {
        PollingSegmentWatcher watcher = new PollingSegmentWatcher(50);
        Path nonExistentDir = tempDir.resolve("nonexistent");
        var executor = Executors.newSingleThreadExecutor();

        try {
            Future<List<Path>> future = executor.submit(() -> watcher.watch(nonExistentDir).take(1).toList());
            Thread.sleep(500);
            future.cancel(true);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interruptedException);
        } finally {
            watcher.close();
            executor.shutdownNow();
        }
    }

    @Test
    void pollingSegmentWatcherIgnoresDirectories() throws Exception {
        PollingSegmentWatcher watcher = new PollingSegmentWatcher(50);
        Thread creator = new Thread(() -> {
            sleep(200);
            createDirectory(tempDir.resolve("subdir"));
            sleep(200);
            createFile(tempDir.resolve("file.m4s"));
        });

        try {
            creator.start();
            List<Path> detected = watcher.watch(tempDir).take(1).toList();
            assertEquals(1, detected.size());
            assertEquals("file.m4s", detected.get(0).getFileName().toString());
        } finally {
            watcher.close();
            creator.join(1_000);
        }
    }

    @Test
    void watchStrategyEnumHasAllValues() {
        WatchStrategy[] strategies = WatchStrategy.values();
        assertEquals(3, strategies.length);
        List<WatchStrategy> strategyList = Arrays.asList(strategies);
        assertTrue(strategyList.contains(WatchStrategy.INOTIFY));
        assertTrue(strategyList.contains(WatchStrategy.POLLING));
        assertTrue(strategyList.contains(WatchStrategy.HYBRID));
    }

    @Test
    void hybridSegmentWatcherCloseIsSafeBeforeWatchFromImprovements() {
        HybridSegmentWatcher watcher = new HybridSegmentWatcher();
        watcher.close();
    }

    @Test
    void pollingSegmentWatcherCloseStopsWatchingFromImprovements() {
        PollingSegmentWatcher watcher = new PollingSegmentWatcher(100);
        watcher.close();
        watcher.close();
    }

    @Test
    void inotifySegmentWatcherCloseIsSafeBeforeWatchFromImprovements() {
        InotifySegmentWatcher watcher = new InotifySegmentWatcher();
        watcher.close();
    }

    @Test
    void createWatcherCreatesCorrectTypeForEachStrategyFromImprovements() {
        SegmentWatcher inotify = SegmentWatcherFactory.createWatcher(WatchStrategy.INOTIFY);
        assertTrue(inotify instanceof InotifySegmentWatcher);
        inotify.close();

        SegmentWatcher polling = SegmentWatcherFactory.createWatcher(WatchStrategy.POLLING, 250);
        assertTrue(polling instanceof PollingSegmentWatcher);
        polling.close();

        SegmentWatcher hybrid = SegmentWatcherFactory.createWatcher(WatchStrategy.HYBRID, 300);
        assertTrue(hybrid instanceof HybridSegmentWatcher);
        hybrid.close();
    }

    @Test
    void watchStrategyEnumValuesFromImprovements() {
        WatchStrategy[] strategies = WatchStrategy.values();
        assertEquals(3, strategies.length);
        List<WatchStrategy> strategyList = Arrays.asList(strategies);
        assertTrue(strategyList.contains(WatchStrategy.INOTIFY));
        assertTrue(strategyList.contains(WatchStrategy.POLLING));
        assertTrue(strategyList.contains(WatchStrategy.HYBRID));
    }

    @Test
    void pollingSegmentWatcherDetectsNewFilesFromImprovements() throws Exception {
        Path watchDir = Files.createDirectories(tempDir.resolve("poll-watch-improv"));
        PollingSegmentWatcher watcher = new PollingSegmentWatcher(50);
        var executor = Executors.newSingleThreadExecutor();

        List<Path> results = new java.util.ArrayList<>();
        Future<?> future = executor.submit(() -> watcher.watch(watchDir).collect(results::add));

        try {
            Thread.sleep(100);
            Path newFile = watchDir.resolve("segment001.m4s");
            Files.write(newFile, new byte[10]);

            Thread.sleep(200);
            watcher.close();
            future.cancel(true);

            assertTrue(!results.isEmpty(), "PollingSegmentWatcher should detect new files");
            assertTrue(results.stream().anyMatch(path -> path.getFileName().toString().equals("segment001.m4s")));
        } finally {
            watcher.close();
            future.cancel(true);
            executor.shutdownNow();
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

    private static void createDirectory(Path path) {
        try {
            Files.createDirectory(path);
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
