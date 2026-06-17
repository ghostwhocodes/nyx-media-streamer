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

public final class InotifySegmentWatcher implements SegmentWatcher {
    private final AtomicBoolean running = new AtomicBoolean(true);
    private WatchService watchService;

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

            try {
                while (running.get()) {
                    WatchKey key;
                    try {
                        key = localWatchService.poll(1, TimeUnit.SECONDS);
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
                        if (Files.isRegularFile(child) && !emit.emit(child)) {
                            return;
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
        });
    }

    @Override
    public void close() {
        running.set(false);
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
