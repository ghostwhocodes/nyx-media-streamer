package com.nyx.bench;

import com.nyx.ffmpeg.ProbeService;
import com.nyx.ffmpeg.model.ProbeResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
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
public class ProbeServiceBench {
    private ProbeService probeService;
    private Path mediaFile;
    private Path probeScript;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        probeScript = createProbeScript();
        probeService = new ProbeService(probeScript.toAbsolutePath().toString());
        mediaFile = Files.createTempFile("nyx-bench-probe", ".ts");
        Files.write(mediaFile, new byte[128]);
        probeService.probeCached(mediaFile);
    }

    @Benchmark
    public ProbeResult probeCachedResult() {
        return probeService.probeCached(mediaFile);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        if (mediaFile != null) {
            Files.deleteIfExists(mediaFile);
        }
        if (probeScript != null) {
            Files.deleteIfExists(probeScript);
        }
        if (probeService != null) {
            probeService.clearCache();
        }
    }

    private Path createProbeScript() throws IOException {
        Path script = Files.createTempFile("nyx-bench-ffprobe", ".sh");
        Files.writeString(
            script,
            "#!/bin/sh\n"
                + "cat <<'EOF'\n"
                + "{\n"
                + "  \"format\": {\n"
                + "    \"format_name\": \"mpegts\",\n"
                + "    \"duration\": \"30.0\",\n"
                + "    \"size\": \"2048\"\n"
                + "  },\n"
                + "  \"streams\": [\n"
                + "    {\n"
                + "      \"index\": 0,\n"
                + "      \"codec_name\": \"h264\",\n"
                + "      \"codec_type\": \"video\",\n"
                + "      \"width\": 1920,\n"
                + "      \"height\": 1080,\n"
                + "      \"r_frame_rate\": \"24/1\"\n"
                + "    }\n"
                + "  ]\n"
                + "}\n"
                + "EOF\n"
        );
        Files.setPosixFilePermissions(
            script,
            Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            )
        );
        return script;
    }
}
