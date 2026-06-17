package com.nyx.ffmpeg;

import com.nyx.ffmpeg.model.ProbeResult;
import com.nyx.ffmpeg.model.ProbeStreams;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbeResultTagsTest {
    private final ProbeService probeService = new ProbeService();

    @Test
    void parseProbeOutputExtractsFormatTagsAndNormalizesToLowercaseKeys() {
        String json = """
            {
              "format": {
                "format_name": "mp3",
                "duration": "120.5",
                "size": "1234567",
                "tags": {
                  "ARTIST": "Test Artist",
                  "album": "Test Album",
                  "Title": "Test Song"
                }
              },
              "streams": []
            }
            """;

        ProbeResult result = probeService.parseProbeOutput(json, "/test.mp3");

        assertEquals("Test Artist", result.getTags().get("artist"));
        assertEquals("Test Album", result.getTags().get("album"));
        assertEquals("Test Song", result.getTags().get("title"));
    }

    @Test
    void parseProbeOutputHandlesMissingTagsGracefully() {
        String json = "{\"format\":{\"format_name\":\"mp4\",\"duration\":\"60\",\"size\":\"100\"},\"streams\":[]}";

        ProbeResult result = probeService.parseProbeOutput(json, "/test.mp4");

        assertTrue(result.getTags().isEmpty());
    }

    @Test
    void parseProbeOutputHandlesNullFormatTags() {
        String json = """
            {
              "format": {
                "format_name": "mkv",
                "duration": "90",
                "size": "5000"
              },
              "streams": []
            }
            """;

        ProbeResult result = probeService.parseProbeOutput(json, "/test.mkv");

        assertTrue(result.getTags().isEmpty());
    }

    @Test
    void parseProbeOutputHandlesEmptyTagsMap() {
        String json = """
            {
              "format": {
                "format_name": "wav",
                "duration": "30",
                "size": "2000",
                "tags": {}
              },
              "streams": []
            }
            """;

        ProbeResult result = probeService.parseProbeOutput(json, "/test.wav");

        assertTrue(result.getTags().isEmpty());
    }

    @Test
    void parseProbeOutputPreservesTagValuesAsIs() {
        String json = """
            {
              "format": {
                "format_name": "flac",
                "duration": "200",
                "size": "50000",
                "tags": {
                  "GENRE": "Electronic / Ambient",
                  "DATE": "2024",
                  "COMMENT": "Encoded with FFmpeg"
                }
              },
              "streams": []
            }
            """;

        ProbeResult result = probeService.parseProbeOutput(json, "/test.flac");

        assertEquals("Electronic / Ambient", result.getTags().get("genre"));
        assertEquals("2024", result.getTags().get("date"));
        assertEquals("Encoded with FFmpeg", result.getTags().get("comment"));
    }

    @Test
    void parseProbeOutputHandlesDuplicateKeysWithDifferentCases() {
        String json = """
            {
              "format": {
                "format_name": "mp3",
                "duration": "100",
                "size": "1000",
                "tags": {
                  "artist": "LowercaseArtist",
                  "TRACKNUMBER": "5"
                }
              },
              "streams": []
            }
            """;

        ProbeResult result = probeService.parseProbeOutput(json, "/test.mp3");

        assertEquals("LowercaseArtist", result.getTags().get("artist"));
        assertEquals("5", result.getTags().get("tracknumber"));
    }

    @Test
    void probeResultIncludesTagsInDataClass() {
        Map<String, String> tags = Map.of("artist", "Test", "album", "Album");

        ProbeResult result = new ProbeResult(
            "/test.mp3",
            "mp3",
            120.0,
            1_234_567L,
            emptyStreams(),
            tags
        );

        assertEquals("Test", result.getTags().get("artist"));
        assertEquals("Album", result.getTags().get("album"));
        assertEquals(2, result.getTags().size());
    }

    @Test
    void probeResultDefaultsTagsToEmptyMap() {
        ProbeResult result = new ProbeResult(
            "/test.mp4",
            "mp4",
            60.0,
            100L,
            emptyStreams()
        );

        assertTrue(result.getTags().isEmpty());
    }

    @Test
    void parseProbeOutputExtractsAudioStreamTags() {
        String json = """
            {
              "format": {
                "format_name": "mkv",
                "duration": "7200",
                "size": "5000000"
              },
              "streams": [
                {
                  "index": 0,
                  "codec_name": "aac",
                  "codec_type": "audio",
                  "channels": 2,
                  "tags": {
                    "language": "eng",
                    "title": "Stereo English"
                  }
                },
                {
                  "index": 1,
                  "codec_name": "aac",
                  "codec_type": "audio",
                  "channels": 6,
                  "tags": {
                    "language": "jpn",
                    "title": "5.1 Japanese"
                  }
                }
              ]
            }
            """;

        ProbeResult result = probeService.parseProbeOutput(json, "/movie.mkv");

        assertEquals(2, result.getStreams().getAudio().size());

        var eng = result.getStreams().getAudio().get(0);
        assertEquals("eng", eng.getLanguage());
        assertEquals("Stereo English", eng.getTitle());
        assertEquals(2, eng.getChannels());

        var jpn = result.getStreams().getAudio().get(1);
        assertEquals("jpn", jpn.getLanguage());
        assertEquals("5.1 Japanese", jpn.getTitle());
        assertEquals(6, jpn.getChannels());
    }

    @Test
    void parseProbeOutputExtractsSubtitleStreamTags() {
        String json = """
            {
              "format": {
                "format_name": "mkv",
                "duration": "3600",
                "size": "2000000"
              },
              "streams": [
                {
                  "index": 0,
                  "codec_name": "subrip",
                  "codec_type": "subtitle",
                  "tags": {
                    "language": "eng",
                    "title": "Full Subtitles"
                  }
                }
              ]
            }
            """;

        ProbeResult result = probeService.parseProbeOutput(json, "/movie.mkv");

        assertEquals(1, result.getStreams().getSubtitle().size());
        assertEquals("eng", result.getStreams().getSubtitle().get(0).getLanguage());
        assertEquals("Full Subtitles", result.getStreams().getSubtitle().get(0).getTitle());
    }

    @Test
    void parseProbeOutputHandlesStreamsWithNoTags() {
        String json = """
            {
              "format": {
                "format_name": "mp4",
                "duration": "60",
                "size": "1000"
              },
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
                  "codec_name": "aac",
                  "codec_type": "audio",
                  "channels": 2
                }
              ]
            }
            """;

        ProbeResult result = probeService.parseProbeOutput(json, "/video.mp4");

        assertEquals(1, result.getStreams().getVideo().size());
        assertEquals(1, result.getStreams().getAudio().size());
        assertTrue(result.getTags().isEmpty());
    }

    private static ProbeStreams emptyStreams() {
        return new ProbeStreams(List.of(), List.of(), List.of());
    }
}
