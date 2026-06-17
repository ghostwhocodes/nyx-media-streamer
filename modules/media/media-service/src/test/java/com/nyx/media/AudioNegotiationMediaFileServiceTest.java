package com.nyx.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.nyx.ffmpeg.MediaProber;
import com.nyx.ffmpeg.model.AudioStream;
import com.nyx.ffmpeg.model.ProbeResult;
import com.nyx.ffmpeg.model.ProbeStreams;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioNegotiationMediaFileServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void describeAudioSourceMapsProbeResultsIntoSharedPlaybackCharacteristics() throws Exception {
        Path audioFile = tempDir.resolve("album.flac");
        Files.write(audioFile, new byte[128]);

        MediaFileService service = new MediaFileService(
            java.util.List.of(tempDir),
            new FakeMediaProber(
                new ProbeResult(
                    audioFile.toString(),
                    "flac",
                    123.456,
                    128,
                    new ProbeStreams(
                        java.util.List.of(),
                        java.util.List.of(new AudioStream(0, "flac", 2, 932, 96_000, "eng", "Stereo")),
                        java.util.List.of()
                    )
                )
            )
        );

        var source = service.describeAudioSource(audioFile);

        assertEquals(audioFile.toString(), source.path());
        assertEquals("flac", source.characteristics().container());
        assertEquals(123_456L, source.characteristics().durationMillis());
        assertEquals(128L, source.characteristics().sizeBytes());
        assertEquals("flac", source.characteristics().audioStreams().getFirst().codec());
        assertEquals(2, source.characteristics().audioStreams().getFirst().channels());
        assertEquals(932, source.characteristics().audioStreams().getFirst().bitrateKbps());
        assertEquals(96_000, source.characteristics().audioStreams().getFirst().sampleRateHz());
    }

    @Test
    void resolveAudioNegotiationRequestHydratesMissingSourceCharacteristics() throws Exception {
        Path audioFile = tempDir.resolve("song.mp3");
        Files.write(audioFile, new byte[64]);

        MediaFileService service = new MediaFileService(
            java.util.List.of(tempDir),
            new FakeMediaProber(
                new ProbeResult(
                    audioFile.toString(),
                    "mp3",
                    42.0,
                    64,
                    new ProbeStreams(
                        java.util.List.of(),
                        java.util.List.of(new AudioStream(0, "mp3", 2, 192, 44_100, null, null)),
                        java.util.List.of()
                    )
                )
            )
        );
        var request = ModuleMediaTestSupport.audioNegotiationRequest(
            ModuleMediaTestSupport.mediaSourceRef(audioFile.toString()),
            5_000
        );

        var resolved = service.resolveAudioNegotiationRequest(request);

        assertEquals(5_000L, resolved.startPositionMillis());
        assertNotNull(resolved.source().characteristics());
        assertEquals("mp3", resolved.source().characteristics().container());
        assertEquals("mp3", resolved.source().characteristics().audioStreams().getFirst().codec());
        assertEquals(44_100, resolved.source().characteristics().audioStreams().getFirst().sampleRateHz());
    }

    @Test
    void describeAudioSourceFallsBackToExtensionBasedCharacteristicsWhenProbeFailsForKnownAudioTypes() throws Exception {
        Path audioFile = tempDir.resolve("fallback.mp3");
        Files.write(audioFile, new byte[96]);

        MediaFileService service = new MediaFileService(java.util.List.of(tempDir), new FailingMediaProber());

        var source = service.describeAudioSource(audioFile);

        assertEquals(audioFile.toString(), source.path());
        assertEquals("mp3", source.characteristics().container());
        assertEquals(96L, source.characteristics().sizeBytes());
        assertEquals("mp3", source.characteristics().audioStreams().getFirst().codec());
    }

    private static final class FakeMediaProber implements MediaProber {
        private final ProbeResult result;

        private FakeMediaProber(ProbeResult result) {
            this.result = result;
        }

        @Override
        public ProbeResult probe(Path path) {
            return result;
        }

        @Override
        public ProbeResult probeCached(Path path) {
            return result;
        }

        @Override
        public void clearCache() {
        }
    }

    private static final class FailingMediaProber implements MediaProber {
        @Override
        public ProbeResult probe(Path path) {
            throw new IllegalStateException("ffprobe failed");
        }

        @Override
        public ProbeResult probeCached(Path path) {
            throw new IllegalStateException("ffprobe failed");
        }

        @Override
        public void clearCache() {
        }
    }
}
