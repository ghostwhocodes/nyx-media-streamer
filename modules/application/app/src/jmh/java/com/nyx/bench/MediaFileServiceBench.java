package com.nyx.bench;

import com.nyx.ffmpeg.ProbeService;
import com.nyx.media.MediaFileService;
import com.nyx.media.contracts.AudioListing;
import com.nyx.media.contracts.SortOrder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class MediaFileServiceBench {
    private Path tempDir;
    private MediaFileService service;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        tempDir = Files.createTempDirectory("nyx-bench");
        for (int i = 0; i < 1_000; i++) {
            Files.createFile(tempDir.resolve("file" + String.format("%04d", i) + ".mp3"));
        }
        service = new MediaFileService(
            List.of(150, 300, 600),
            List.of(tempDir),
            new ProbeService(),
            null,
            null,
            null,
            null,
            null
        );
    }

    @Benchmark
    public AudioListing listDirectory() {
        return service.listAudio(tempDir, 1, 20, SortOrder.NAME);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        if (tempDir == null) {
            return;
        }
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }
}
