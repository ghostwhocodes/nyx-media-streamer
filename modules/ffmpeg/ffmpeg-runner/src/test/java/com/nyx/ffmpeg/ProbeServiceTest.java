package com.nyx.ffmpeg;

import com.nyx.ffmpeg.model.ProbeResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbeServiceTest {
    private final ProbeService probeService = new ProbeService();

    @Test
    void parsesFfprobeJsonOutputIntoProbeResult() throws IOException {
        String json = loadFixture("ffprobe-output.json");

        ProbeResult result = probeService.parseProbeOutput(json, "/media/movies/example.mkv");

        assertEquals("/media/movies/example.mkv", result.getPath());
        assertEquals("matroska,webm", result.getFormat());
        assertEquals(7_823.5, result.getDurationSecs(), 0.0);
        assertEquals(42_000_000_000L, result.getSizeBytes());

        assertEquals(1, result.getStreams().getVideo().size());
        var video = result.getStreams().getVideo().get(0);
        assertEquals(0, video.getIndex());
        assertEquals("h264", video.getCodec());
        assertEquals(1_920, video.getWidth());
        assertEquals(1_080, video.getHeight());
        assertEquals(23.976, video.getFps(), 0.001);
        assertEquals(8_500, video.getBitrateKbps());

        assertEquals(2, result.getStreams().getAudio().size());
        var audio0 = result.getStreams().getAudio().get(0);
        assertEquals(1, audio0.getIndex());
        assertEquals("dts", audio0.getCodec());
        assertEquals(6, audio0.getChannels());
        assertEquals("eng", audio0.getLanguage());
        assertEquals("DTS-HD MA 5.1", audio0.getTitle());

        var audio1 = result.getStreams().getAudio().get(1);
        assertEquals("jpn", audio1.getLanguage());
        assertEquals(2, audio1.getChannels());

        assertEquals(2, result.getStreams().getSubtitle().size());
        assertEquals("subrip", result.getStreams().getSubtitle().get(0).getCodec());
        assertEquals("hdmv_pgs_subtitle", result.getStreams().getSubtitle().get(1).getCodec());
    }

    @Test
    void handlesMissingOptionalFieldsGracefully() {
        String json = """
            {
              "streams": [
                {
                  "index": 0,
                  "codec_name": "h264",
                  "codec_type": "video",
                  "width": 1280,
                  "height": 720,
                  "r_frame_rate": "30/1"
                }
              ],
              "format": {
                "format_name": "mp4",
                "duration": "60.0"
              }
            }
            """;

        ProbeResult result = probeService.parseProbeOutput(json, "/test.mp4");

        assertEquals("mp4", result.getFormat());
        assertEquals(60.0, result.getDurationSecs(), 0.0);
        assertEquals(0L, result.getSizeBytes());
        assertNull(result.getStreams().getVideo().get(0).getBitrateKbps());
        assertEquals(30.0, result.getStreams().getVideo().get(0).getFps(), 0.0);
    }

    @Test
    void samePathWithDifferentMtimeProducesDistinctCacheKeys() {
        ProbeCacheKey key1 = new ProbeCacheKey("/a.mkv", 1_000L, 100L);
        ProbeCacheKey key2 = new ProbeCacheKey("/a.mkv", 2_000L, 100L);

        assertNotEquals(key1, key2);
    }

    @Test
    void samePathWithDifferentSizeProducesDistinctCacheKeys() {
        ProbeCacheKey key1 = new ProbeCacheKey("/a.mkv", 1_000L, 100L);
        ProbeCacheKey key2 = new ProbeCacheKey("/a.mkv", 1_000L, 200L);

        assertNotEquals(key1, key2);
    }

    @Test
    void identicalPathMtimeSizeProducesEqualCacheKeys() {
        ProbeCacheKey key1 = new ProbeCacheKey("/a.mkv", 1_000L, 100L);
        ProbeCacheKey key2 = new ProbeCacheKey("/a.mkv", 1_000L, 100L);

        assertEquals(key1, key2);
        assertEquals(key1.hashCode(), key2.hashCode());
    }

    @Test
    void cacheSizeStartsAtZero() {
        assertEquals(0, new ProbeService().getCacheSize());
    }

    @Test
    void clearCacheResetsCacheSizeToZero() {
        ProbeService service = new ProbeService();
        assertEquals(0, service.getCacheSize());

        service.clearCache();

        assertEquals(0, service.getCacheSize());
    }

    @Test
    void probeCachedWithANonMediaFileFailsGracefullyAndDoesNotDeadlock() throws IOException {
        Path tempFile = Files.createTempFile("probe-test", ".txt");
        ExecutorService executor = Executors.newFixedThreadPool(5);
        try {
            Files.writeString(tempFile, "hello");
            ProbeService service = new ProbeService();

            List<Object> results = List.of(
                CompletableFuture.supplyAsync(() -> captureProbeOutcome(service, tempFile), executor),
                CompletableFuture.supplyAsync(() -> captureProbeOutcome(service, tempFile), executor),
                CompletableFuture.supplyAsync(() -> captureProbeOutcome(service, tempFile), executor),
                CompletableFuture.supplyAsync(() -> captureProbeOutcome(service, tempFile), executor),
                CompletableFuture.supplyAsync(() -> captureProbeOutcome(service, tempFile), executor)
            ).stream().map(CompletableFuture::join).toList();

            boolean allFailed = results.stream().allMatch(Throwable.class::isInstance);
            boolean allSucceeded = results.stream().noneMatch(Throwable.class::isInstance);
            assertTrue(allFailed || allSucceeded, "Mixed success/failure indicates race condition");
        } finally {
            executor.shutdownNow();
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void failedProbeDoesNotPermanentlyOccupyACacheSlot() throws IOException {
        Path tempFile = Files.createTempFile("probe-fail-test", ".txt");
        try {
            Files.writeString(tempFile, "not a media file");
            ProbeService service = new ProbeService();
            int sizeBefore = service.getCacheSize();

            assertThrows(ProbeException.class, () -> service.probeCached(tempFile));

            assertEquals(sizeBefore, service.getCacheSize(), "Failed probe should not permanently occupy cache");
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void parseProbeOutputHandlesVideoStream() {
        String json = """
            {
              "format": {
                "format_name": "mov,mp4,m4a,3gp,3g2,mj2",
                "duration": "120.5",
                "size": "1234567",
                "tags": {
                  "title": "Test Video",
                  "artist": "Test Artist"
                }
              },
              "streams": [
                {
                  "index": 0,
                  "codec_name": "h264",
                  "codec_type": "video",
                  "width": 1920,
                  "height": 1080,
                  "r_frame_rate": "30/1",
                  "avg_frame_rate": "30/1",
                  "bit_rate": "5000000"
                }
              ]
            }
            """;

        ProbeResult result = probeService.parseProbeOutput(json, "/test.mp4");

        assertEquals("/test.mp4", result.getPath());
        assertEquals("mov,mp4,m4a,3gp,3g2,mj2", result.getFormat());
        assertEquals(120.5, result.getDurationSecs(), 0.0);
        assertEquals(1_234_567L, result.getSizeBytes());
        assertEquals(1, result.getStreams().getVideo().size());
        assertEquals("h264", result.getStreams().getVideo().get(0).getCodec());
        assertEquals(1_920, result.getStreams().getVideo().get(0).getWidth());
        assertEquals(1_080, result.getStreams().getVideo().get(0).getHeight());
        assertEquals(30.0, result.getStreams().getVideo().get(0).getFps(), 0.0);
        assertEquals(5_000, result.getStreams().getVideo().get(0).getBitrateKbps());
        assertEquals("Test Video", result.getTags().get("title"));
        assertEquals("Test Artist", result.getTags().get("artist"));
    }

    @Test
    void parseProbeOutputHandlesAudioStream() {
        String json = """
            {
              "format": {
                "format_name": "mp3",
                "duration": "180.0",
                "size": "5000000"
              },
              "streams": [
                {
                  "index": 0,
                  "codec_name": "mp3",
                  "codec_type": "audio",
                  "channels": 2,
                  "sample_rate": "44100",
                  "tags": {
                    "language": "eng",
                    "title": "Song Title"
                  }
                }
              ]
            }
            """;

        ProbeResult result = probeService.parseProbeOutput(json, "/test.mp3");

        assertEquals(1, result.getStreams().getAudio().size());
        assertEquals("mp3", result.getStreams().getAudio().get(0).getCodec());
        assertEquals(2, result.getStreams().getAudio().get(0).getChannels());
        assertEquals(44_100, result.getStreams().getAudio().get(0).getSampleRateHz());
        assertEquals("eng", result.getStreams().getAudio().get(0).getLanguage());
        assertEquals("Song Title", result.getStreams().getAudio().get(0).getTitle());
    }

    @Test
    void parseProbeOutputHandlesSubtitleStream() {
        String json = """
            {
              "format": { "format_name": "matroska,webm" },
              "streams": [
                {
                  "index": 0,
                  "codec_name": "h264",
                  "codec_type": "video",
                  "width": 1920,
                  "height": 1080,
                  "r_frame_rate": "24/1"
                },
                {
                  "index": 1,
                  "codec_name": "subrip",
                  "codec_type": "subtitle",
                  "tags": {
                    "language": "eng",
                    "title": "English"
                  }
                }
              ]
            }
            """;

        ProbeResult result = probeService.parseProbeOutput(json, "/test.mkv");

        assertEquals(1, result.getStreams().getSubtitle().size());
        assertEquals("subrip", result.getStreams().getSubtitle().get(0).getCodec());
        assertEquals("eng", result.getStreams().getSubtitle().get(0).getLanguage());
        assertEquals("English", result.getStreams().getSubtitle().get(0).getTitle());
    }

    @Test
    void parseProbeOutputHandlesNullFormat() {
        ProbeResult result = probeService.parseProbeOutput("{ \"streams\": [] }", "/test.mp4");

        assertEquals("unknown", result.getFormat());
        assertEquals(0.0, result.getDurationSecs(), 0.0);
        assertEquals(0L, result.getSizeBytes());
    }

    @Test
    void parseProbeOutputHandlesStreamWithMissingFields() {
        String json = """
            {
              "format": { "format_name": "mp4" },
              "streams": [
                {
                  "index": 0,
                  "codec_type": "video"
                },
                {
                  "index": 1,
                  "codec_type": "audio"
                }
              ]
            }
            """;

        ProbeResult result = probeService.parseProbeOutput(json, "/test.mp4");

        assertEquals("unknown", result.getStreams().getVideo().get(0).getCodec());
        assertEquals(0, result.getStreams().getVideo().get(0).getWidth());
        assertEquals("unknown", result.getStreams().getAudio().get(0).getCodec());
        assertEquals(0, result.getStreams().getAudio().get(0).getChannels());
    }

    @Test
    void parseProbeOutputUsesAvgFrameRateFallback() {
        String json = """
            {
              "format": { "format_name": "mp4" },
              "streams": [
                {
                  "index": 0,
                  "codec_type": "video",
                  "avg_frame_rate": "25/1"
                }
              ]
            }
            """;

        ProbeResult result = probeService.parseProbeOutput(json, "/test.mp4");

        assertEquals(25.0, result.getStreams().getVideo().get(0).getFps(), 0.0);
    }

    @Test
    void parseProbeOutputHandlesFractionalFrameRate() {
        String json = """
            {
              "format": { "format_name": "mp4" },
              "streams": [
                {
                  "index": 0,
                  "codec_type": "video",
                  "r_frame_rate": "24000/1001"
                }
              ]
            }
            """;

        ProbeResult result = probeService.parseProbeOutput(json, "/test.mp4");

        assertTrue(result.getStreams().getVideo().get(0).getFps() > 23.0);
        assertTrue(result.getStreams().getVideo().get(0).getFps() < 24.0);
    }

    @Test
    void parseProbeOutputHandlesEmptyStreams() {
        String json = "{\"format\":{\"format_name\":\"mp4\",\"duration\":\"10.0\",\"size\":\"1000\"},\"streams\":[]}";

        ProbeResult result = probeService.parseProbeOutput(json, "/test.mp4");

        assertEquals("mp4", result.getFormat());
        assertEquals(10.0, result.getDurationSecs(), 0.0);
        assertEquals(1_000L, result.getSizeBytes());
        assertTrue(result.getStreams().getVideo().isEmpty());
        assertTrue(result.getStreams().getAudio().isEmpty());
        assertTrue(result.getStreams().getSubtitle().isEmpty());
    }

    @Test
    void parseProbeOutputNormalizesTagKeysToLowercase() {
        String json = """
            {"format":{"format_name":"mp3","duration":"120.5","size":"5000",
            "tags":{"ARTIST":"Test Artist","Album":"Test Album"}},"streams":[]}
            """;

        ProbeResult result = probeService.parseProbeOutput(json, "/test.mp3");

        assertEquals("Test Artist", result.getTags().get("artist"));
        assertEquals("Test Album", result.getTags().get("album"));
    }

    @Test
    void parseProbeOutputWithVideoAudioAndSubtitleStreams() {
        String json = """
            {
              "format": {
                "format_name": "matroska",
                "duration": "7200.0",
                "size": "2000000000",
                "tags": {
                  "ENCODER": "Lavf58.29.100",
                  "TITLE": "Movie"
                }
              },
              "streams": [
                {
                  "index": 0,
                  "codec_name": "hevc",
                  "codec_type": "video",
                  "width": 3840,
                  "height": 2160,
                  "r_frame_rate": "60000/1001",
                  "bit_rate": "20000000"
                },
                {
                  "index": 1,
                  "codec_name": "aac",
                  "codec_type": "audio",
                  "channels": 6,
                  "bit_rate": "384000",
                  "tags": { "language": "eng", "title": "Surround 5.1" }
                },
                {
                  "index": 2,
                  "codec_name": "aac",
                  "codec_type": "audio",
                  "channels": 2,
                  "tags": { "language": "jpn", "title": "Japanese Stereo" }
                },
                {
                  "index": 3,
                  "codec_name": "ass",
                  "codec_type": "subtitle",
                  "tags": { "language": "eng", "title": "Full" }
                },
                {
                  "index": 4,
                  "codec_name": "ass",
                  "codec_type": "subtitle",
                  "tags": { "language": "jpn" }
                }
              ]
            }
            """;

        ProbeResult result = probeService.parseProbeOutput(json, "/movie.mkv");

        assertEquals(1, result.getStreams().getVideo().size());
        assertEquals(2, result.getStreams().getAudio().size());
        assertEquals(2, result.getStreams().getSubtitle().size());
        assertEquals(3_840, result.getStreams().getVideo().get(0).getWidth());
        assertEquals(6, result.getStreams().getAudio().get(0).getChannels());
        assertEquals("jpn", result.getStreams().getAudio().get(1).getLanguage());
        assertEquals("Movie", result.getTags().get("title"));
    }

    @Test
    void parseProbeOutputExtractsFormatTagsFromImprovements() {
        String json = """
            {
              "format": {
                "format_name": "mp3",
                "duration": "180.5",
                "size": "2880000",
                "tags": {
                  "artist": "Test Artist",
                  "ALBUM": "Test Album",
                  "Title": "Test Title"
                }
              },
              "streams": [
                {
                  "index": 0,
                  "codec_name": "mp3",
                  "codec_type": "audio",
                  "channels": 2,
                  "tags": {
                    "language": "eng",
                    "title": "Audio Track"
                  }
                }
              ]
            }
            """;

        ProbeResult result = probeService.parseProbeOutput(json, "/test/file.mp3");

        assertEquals("mp3", result.getFormat());
        assertEquals(180.5, result.getDurationSecs(), 0.01);
        assertEquals(2_880_000L, result.getSizeBytes());
        assertEquals("Test Artist", result.getTags().get("artist"));
        assertEquals("Test Album", result.getTags().get("album"));
        assertEquals("Test Title", result.getTags().get("title"));
        assertEquals(1, result.getStreams().getAudio().size());
        assertEquals("mp3", result.getStreams().getAudio().get(0).getCodec());
        assertEquals(2, result.getStreams().getAudio().get(0).getChannels());
        assertEquals("eng", result.getStreams().getAudio().get(0).getLanguage());
        assertEquals("Audio Track", result.getStreams().getAudio().get(0).getTitle());
    }

    @Test
    void parseProbeOutputHandlesSubtitleStreamsWithTagsFromImprovements() {
        String json = """
            {
              "format": {
                "format_name": "matroska",
                "duration": "100.0",
                "size": "500000"
              },
              "streams": [
                {
                  "index": 0,
                  "codec_name": "h264",
                  "codec_type": "video",
                  "width": 1920,
                  "height": 1080,
                  "r_frame_rate": "24/1",
                  "bit_rate": "5000000"
                },
                {
                  "index": 1,
                  "codec_name": "subrip",
                  "codec_type": "subtitle",
                  "tags": {
                    "language": "fre",
                    "title": "French Subtitles"
                  }
                }
              ]
            }
            """;

        ProbeResult result = probeService.parseProbeOutput(json, "/test/video.mkv");

        assertEquals(1, result.getStreams().getVideo().size());
        assertEquals(1_920, result.getStreams().getVideo().get(0).getWidth());
        assertEquals(1_080, result.getStreams().getVideo().get(0).getHeight());
        assertEquals(24.0, result.getStreams().getVideo().get(0).getFps(), 0.1);
        assertEquals(5_000, result.getStreams().getVideo().get(0).getBitrateKbps());
        assertEquals(1, result.getStreams().getSubtitle().size());
        assertEquals("subrip", result.getStreams().getSubtitle().get(0).getCodec());
        assertEquals("fre", result.getStreams().getSubtitle().get(0).getLanguage());
        assertEquals("French Subtitles", result.getStreams().getSubtitle().get(0).getTitle());
    }

    @Test
    void parseProbeOutputHandlesEmptyTagsFromImprovements() {
        String json = """
            {
              "format": {
                "format_name": "mp4"
              },
              "streams": []
            }
            """;

        ProbeResult result = probeService.parseProbeOutput(json, "/test/empty.mp4");

        assertEquals("mp4", result.getFormat());
        assertEquals(0.0, result.getDurationSecs(), 0.0);
        assertEquals(0L, result.getSizeBytes());
        assertTrue(result.getTags().isEmpty());
    }

    @Test
    void parseProbeOutputHandlesMissingFormatFromImprovements() {
        String json = """
            {
              "streams": [
                {
                  "index": 0,
                  "codec_name": "aac",
                  "codec_type": "audio",
                  "channels": 6
                }
              ]
            }
            """;

        ProbeResult result = probeService.parseProbeOutput(json, "/test/noformat.mp4");

        assertEquals("unknown", result.getFormat());
        assertEquals(1, result.getStreams().getAudio().size());
        assertEquals(6, result.getStreams().getAudio().get(0).getChannels());
    }

    @Test
    void parseProbeOutputUsesAvgFrameRateWhenRFrameRateMissingFromImprovements() {
        String json = """
            {
              "format": { "format_name": "mp4", "duration": "60.0", "size": "1000" },
              "streams": [
                {
                  "index": 0,
                  "codec_name": "h264",
                  "codec_type": "video",
                  "width": 640,
                  "height": 480,
                  "avg_frame_rate": "30/1"
                }
              ]
            }
            """;

        ProbeResult result = probeService.parseProbeOutput(json, "/test/avgfps.mp4");

        assertEquals(30.0, result.getStreams().getVideo().get(0).getFps(), 0.1);
    }

    @Test
    void parseProbeOutputHandlesUnknownCodecNameFromImprovements() {
        String json = """
            {
              "format": { "format_name": "mp4" },
              "streams": [
                {
                  "index": 0,
                  "codec_type": "video",
                  "width": 100,
                  "height": 100
                },
                {
                  "index": 1,
                  "codec_type": "audio"
                }
              ]
            }
            """;

        ProbeResult result = probeService.parseProbeOutput(json, "/test/nocodec.mp4");

        assertEquals("unknown", result.getStreams().getVideo().get(0).getCodec());
        assertEquals("unknown", result.getStreams().getAudio().get(0).getCodec());
        assertEquals(0, result.getStreams().getAudio().get(0).getChannels());
    }

    @Test
    void clearCacheClearsInternalCacheFromImprovements() {
        ProbeService service = new ProbeService();

        service.clearCache();
    }

    @Test
    void cacheSizeReturnsZeroForEmptyCacheFromImprovements() {
        ProbeService service = new ProbeService();

        assertEquals(0, service.getCacheSize());
    }

    @Test
    void probeCacheKeyDataClassEqualityAndHashCode() {
        ProbeCacheKey key1 = new ProbeCacheKey("/path/file.mp4", 1_000L, 5_000L);
        ProbeCacheKey key2 = new ProbeCacheKey("/path/file.mp4", 1_000L, 5_000L);
        ProbeCacheKey key3 = new ProbeCacheKey("/path/file.mp4", 2_000L, 5_000L);

        assertEquals(key1, key2);
        assertEquals(key1.hashCode(), key2.hashCode());
        assertNotEquals(key1, key3);
    }

    @Test
    void probeCacheKeyToStringContainsPath() {
        ProbeCacheKey key = new ProbeCacheKey("/test/file.mp4", 100L, 200L);

        assertTrue(key.toString().contains("/test/file.mp4"));
    }

    private String loadFixture(String name) throws IOException {
        try (InputStream input = Objects.requireNonNull(
            getClass().getClassLoader().getResourceAsStream("fixtures/" + name),
            "Missing fixture " + name
        )) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Object captureProbeOutcome(ProbeService service, Path path) {
        try {
            return service.probeCached(path);
        } catch (Throwable throwable) {
            return throwable;
        }
    }
}
