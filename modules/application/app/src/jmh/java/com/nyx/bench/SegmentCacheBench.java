package com.nyx.bench;

import com.nyx.transcode.SegmentCache;
import java.nio.file.Path;
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
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class SegmentCacheBench {
    private SegmentCache cache;
    private final Path benchPath = Path.of("/tmp/nyx-bench.ts");

    @Setup(Level.Trial)
    public void setup() {
        cache = new SegmentCache(1, 1_000);
        for (int i = 0; i < 999; i++) {
            cache.register(Path.of("/tmp/bench-seg-" + i + ".ts"), "warmup-job");
        }
        cache.startGracePeriod("warmup-job");
    }

    @Benchmark
    public void register() {
        cache.register(benchPath, "bench-job");
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        cache.purgeAll();
    }
}
