package com.nyx.transcode.watch;

import com.nyx.concurrent.BlockingStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PollingSegmentWatcher implements SegmentWatcher {
    private final long pollIntervalMs;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public PollingSegmentWatcher() {
        this(500L);
    }

    public PollingSegmentWatcher(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    @Override
    public BlockingStream<Path> watch(Path directory) {
        return new BlockingStream<>(emit -> {
            var seen = ConcurrentHashMap.<Path>newKeySet();
            running.set(true);

            if (Files.isDirectory(directory)) {
                seen.addAll(listRegularFiles(directory));
            }

            while (running.get()) {
                try {
                    Thread.sleep(pollIntervalMs);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!Files.isDirectory(directory)) {
                    continue;
                }

                for (Path file : listRegularFiles(directory)) {
                    if (seen.add(file) && !emit.emit(file)) {
                        return;
                    }
                }
            }
        });
    }

    @Override
    public void close() {
        running.set(false);
    }

    private static List<Path> listRegularFiles(Path directory) {
        try (var stream = Files.list(directory)) {
            return stream.filter(Files::isRegularFile).toList();
        } catch (Exception exception) {
            sneakyThrow(exception);
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable throwable) throws T {
        throw (T) throwable;
    }
}
