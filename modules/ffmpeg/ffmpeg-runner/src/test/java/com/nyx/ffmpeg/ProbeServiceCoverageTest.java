package com.nyx.ffmpeg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.ffmpeg.model.ProbeResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProbeServiceCoverageTest {
    @TempDir
    Path tempDir;

    @Test
    void probeAndProbeCachedRecordMissesHitsAndDurations() throws IOException {
        RecordingMetricsCollector metrics = new RecordingMetricsCollector();
        ProbeService service = new ProbeService(createProbeScript("ffprobe-ok.sh", validProbeJson()), metrics);
        Path source = createVideoPlaceholder("cached-video.mkv");

        ProbeResult direct = service.probe(source);
        ProbeResult firstCached = service.probeCached(source);
        ProbeResult secondCached = service.probeCached(source);

        assertEquals("mp4", direct.getFormat());
        assertEquals("mp4", firstCached.getFormat());
        assertEquals("mp4", secondCached.getFormat());
        assertEquals(1, metrics.cacheMisses);
        assertEquals(1, metrics.cacheHits);
        assertEquals(1, metrics.durationRecords);
        assertTrue(metrics.lastDurationNanos > 0L);
        assertEquals(1, service.getCacheSize());
    }

    @Test
    void probeFailureAndInvalidJsonPreserveHistoricalExceptions() throws IOException {
        ProbeService failingService = new ProbeService(createProbeScript("ffprobe-fail.sh", "echo broken >&2\nexit 1\n"));
        Path source = createVideoPlaceholder("broken-video.mkv");

        ProbeException probeFailure = assertThrows(
            ProbeException.class,
            () -> failingService.probe(source)
        );
        assertTrue(probeFailure.getMessage().contains("ffprobe failed with exit code 1"));

        ProbeService parseService = new ProbeService();
        assertThrows(IOException.class, () -> parseService.parseProbeOutput("{", "/broken.mkv"));
    }

    @Test
    void parseProbeOutputFallsBackOnInvalidNumbersAndMissingTags() {
        ProbeService service = new ProbeService();
        String json = """
            {
              "format": {
                "tags": {
                  "TITLE": "Coverage Movie"
                },
                "duration": "bad-duration",
                "size": "bad-size"
              },
              "streams": [
                {
                  "index": 0,
                  "codec_type": "video",
                  "r_frame_rate": "bad-rate",
                  "bit_rate": "bad-bitrate"
                },
                {
                  "index": 1,
                  "codec_type": "audio",
                  "bit_rate": "bad-audio-bitrate",
                  "sample_rate": "bad-sample-rate"
                }
              ]
            }
            """;

        ProbeResult result = service.parseProbeOutput(json, "/fallbacks.mkv");

        assertEquals("unknown", result.getFormat());
        assertEquals(0.0, result.getDurationSecs(), 0.0);
        assertEquals(0L, result.getSizeBytes());
        assertEquals("unknown", result.getStreams().getVideo().getFirst().getCodec());
        assertEquals(0.0, result.getStreams().getVideo().getFirst().getFps(), 0.0);
        assertNull(result.getStreams().getVideo().getFirst().getBitrateKbps());
        assertEquals("unknown", result.getStreams().getAudio().getFirst().getCodec());
        assertNull(result.getStreams().getAudio().getFirst().getBitrateKbps());
        assertNull(result.getStreams().getAudio().getFirst().getSampleRateHz());
        assertNull(result.getStreams().getAudio().getFirst().getLanguage());
        assertEquals("Coverage Movie", result.getTags().get("title"));
    }

    @Test
    void parseProbeOutputHandlesPlainAndZeroDenominatorFrameRates() {
        ProbeService service = new ProbeService();
        String json = """
            {
              "format": {
                "format_name": "mp4"
              },
              "streams": [
                {
                  "index": 0,
                  "codec_name": "h264",
                  "codec_type": "video",
                  "r_frame_rate": "25"
                },
                {
                  "index": 1,
                  "codec_name": "h264",
                  "codec_type": "video",
                  "r_frame_rate": "1/0"
                }
              ]
            }
            """;

        ProbeResult result = service.parseProbeOutput(json, "/fps.mkv");

        assertEquals(25.0, result.getStreams().getVideo().get(0).getFps(), 0.0);
        assertEquals(0.0, result.getStreams().getVideo().get(1).getFps(), 0.0);
    }

    private Path createVideoPlaceholder(String name) throws IOException {
        Path source = tempDir.resolve(name);
        Files.write(source, new byte[128]);
        return source;
    }

    private String createProbeScript(String name, String body) throws IOException {
        Path script = tempDir.resolve(name);
        Files.writeString(script, "#!/bin/bash\n" + body);
        Files.setPosixFilePermissions(
            script,
            Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            )
        );
        return script.toAbsolutePath().toString();
    }

    private String validProbeJson() {
        return "cat <<'EOF'\n"
            + "{\n"
            + "  \"format\": {\n"
            + "    \"format_name\": \"mp4\",\n"
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
            + "exit 0\n";
    }

    private static final class RecordingMetricsCollector implements ProbeMetricsCollector {
        private int cacheHits;
        private int cacheMisses;
        private int durationRecords;
        private long lastDurationNanos;

        @Override
        public void recordProbeCacheHit() {
            cacheHits++;
        }

        @Override
        public void recordProbeCacheMiss() {
            cacheMisses++;
        }

        @Override
        public void recordProbeDuration(long nanos) {
            durationRecords++;
            lastDurationNanos = nanos;
        }
    }
}
