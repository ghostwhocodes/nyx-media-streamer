package com.nyx.transcode.watch;

import com.nyx.concurrent.BlockingStream;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class HybridSegmentWatcher implements SegmentWatcher {
    private final long fallbackIntervalMs;
    private final long timeoutMs;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private SegmentWatcher delegate;
    private WatchService watchService;

    public HybridSegmentWatcher() {
        this(500L, 5_000L);
    }

    public HybridSegmentWatcher(long fallbackIntervalMs) {
        this(fallbackIntervalMs, 5_000L);
    }

    public HybridSegmentWatcher(long fallbackIntervalMs, long timeoutMs) {
        this.fallbackIntervalMs = fallbackIntervalMs;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public BlockingStream<Path> watch(Path directory) {
        return new BlockingStream<>(emit -> {
            WatchService localWatchService;
            try {
                localWatchService = FileSystems.getDefault().newWatchService();
                directory.register(localWatchService, StandardWatchEventKinds.ENTRY_CREATE);
            } catch (Exception exception) {
                sneakyThrow(exception);
                return;
            }

            running.set(true);
            watchService = localWatchService;

            boolean receivedEvent = false;
            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            try {
                while (running.get()) {
                    long pollTimeoutMs;
                    if (receivedEvent) {
                        pollTimeoutMs = 1_000L;
                    } else {
                        long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
                        if (remainingMs <= 0L) {
                            break;
                        }
                        pollTimeoutMs = Math.min(remainingMs, 1_000L);
                    }

                    WatchKey key;
                    try {
                        key = localWatchService.poll(pollTimeoutMs, TimeUnit.MILLISECONDS);
                    } catch (ClosedWatchServiceException ignored) {
                        break;
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (key == null) {
                        continue;
                    }

                    for (WatchEvent<?> event : key.pollEvents()) {
                        @SuppressWarnings("unchecked")
                        WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                        Path child = directory.resolve(pathEvent.context());
                        if (Files.isRegularFile(child)) {
                            receivedEvent = true;
                            if (!emit.emit(child)) {
                                return;
                            }
                        }
                    }
                    if (!key.reset()) {
                        break;
                    }
                }
            } finally {
                closeQuietly(localWatchService);
                if (watchService == localWatchService) {
                    watchService = null;
                }
            }

            if (!receivedEvent && running.get()) {
                PollingSegmentWatcher polling = new PollingSegmentWatcher(fallbackIntervalMs);
                delegate = polling;
                polling.watch(directory).collect(path -> {
                    if (!emit.emit(path)) {
                        polling.close();
                    }
                });
            }
        });
    }

    @Override
    public void close() {
        running.set(false);
        SegmentWatcher localDelegate = delegate;
        if (localDelegate != null) {
            localDelegate.close();
        }
        WatchService localWatchService = watchService;
        if (localWatchService != null) {
            closeQuietly(localWatchService);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable throwable) throws T {
        throw (T) throwable;
    }
}
