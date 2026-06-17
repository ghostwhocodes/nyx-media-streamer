package com.nyx.ffmpeg;

import com.nyx.ffmpeg.model.ProbeResult;
import com.nyx.ffmpeg.model.ProbeStreams;
import com.nyx.ffmpeg.model.VideoStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmpegVideoPreviewGeneratorTest {
    @TempDir
    Path tempDir;

    @Test
    void planDefaultsToTenPercentAnd320x180Box() throws IOException {
        Path source = createVideoPlaceholder();
        FfmpegVideoPreviewGenerator generator = new FfmpegVideoPreviewGenerator(
            fakeProber(120.0, 1920, 1080),
            createCopyScript(createFixtureImage("preview-default.jpg"))
        );

        VideoPreviewPlan plan = generator.plan(source, new VideoPreviewRequest());

        assertEquals(12_000L, plan.getSeekMillis());
        assertEquals(320, plan.getOutputWidth());
        assertEquals(180, plan.getOutputHeight());
        assertEquals("image/jpeg", plan.getOutputMimeType());
    }

    @Test
    void planClampsExplicitPositionAndPreservesAspectRatioInsideRequestedBox() throws IOException {
        Path source = createVideoPlaceholder();
        FfmpegVideoPreviewGenerator generator = new FfmpegVideoPreviewGenerator(
            fakeProber(5.0, 1000, 500),
            createCopyScript(createFixtureImage("preview-box.jpg"))
        );

        VideoPreviewPlan plan = generator.plan(source, new VideoPreviewRequest(9_000L, null, 400, 300));

        assertEquals(4_999L, plan.getSeekMillis());
        assertEquals(400, plan.getOutputWidth());
        assertEquals(200, plan.getOutputHeight());
    }

    @Test
    void generateReturnsFfmpegOutputBytes() throws IOException {
        Path source = createVideoPlaceholder();
        Path fixture = createFixtureImage("preview-generate.jpg");
        FfmpegVideoPreviewGenerator generator = new FfmpegVideoPreviewGenerator(
            fakeProber(60.0, 1280, 720),
            createCopyScript(fixture)
        );

        VideoPreviewOutput output = generator.getPreview(source, new VideoPreviewRequest(null, 25, 240, null));

        assertArrayEquals(Files.readAllBytes(fixture), output.bytes());
        assertEquals(15_000L, output.plan().getSeekMillis());
        assertEquals(240, output.plan().getOutputWidth());
        assertEquals(135, output.plan().getOutputHeight());
    }

    @Test
    void planRejectsProbeResultsWithoutVideoStreams() throws IOException {
        Path source = createVideoPlaceholder();
        FfmpegVideoPreviewGenerator generator = new FfmpegVideoPreviewGenerator(
            new MediaProber() {
                @Override
                public ProbeResult probe(Path path) {
                    return new ProbeResult(
                        path.toString(),
                        "mp4",
                        10.0,
                        128L,
                        new ProbeStreams(List.of(), List.of(), List.of())
                    );
                }

                @Override
                public ProbeResult probeCached(Path path) {
                    return probe(path);
                }

                @Override
                public void clearCache() {
                }
            },
            createCopyScript(createFixtureImage("preview-no-video.jpg"))
        );

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> generator.plan(source, new VideoPreviewRequest())
        );
        assertTrue(exception.getMessage().contains("not a video"));
    }

    private MediaProber fakeProber(double durationSecs, int width, int height) {
        ProbeResult result = new ProbeResult(
            tempDir.resolve("video.mp4").toString(),
            "mp4",
            durationSecs,
            1_024L,
            new ProbeStreams(
                List.of(new VideoStream(0, "h264", width, height, 24.0, 1_000)),
                List.of(),
                List.of()
            )
        );
        return new MediaProber() {
            @Override
            public ProbeResult probe(Path path) {
                return copyProbeResult(path, result);
            }

            @Override
            public ProbeResult probeCached(Path path) {
                return probe(path);
            }

            @Override
            public void clearCache() {
            }
        };
    }

    private static ProbeResult copyProbeResult(Path path, ProbeResult result) {
        return new ProbeResult(
            path.toString(),
            result.getFormat(),
            result.getDurationSecs(),
            result.getSizeBytes(),
            result.getStreams(),
            result.getTags()
        );
    }

    private Path createVideoPlaceholder() throws IOException {
        return createVideoPlaceholder("video.mp4");
    }

    private Path createVideoPlaceholder(String name) throws IOException {
        Path source = tempDir.resolve(name);
        Files.write(source, new byte[256]);
        return source;
    }

    private Path createFixtureImage(String name) throws IOException {
        Path output = tempDir.resolve(name);
        BufferedImage image = new BufferedImage(32, 18, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, "jpg", output.toFile());
        return output;
    }

    private String createCopyScript(Path fixture) throws IOException {
        Path script = tempDir.resolve("fake-ffmpeg-" + fixture.getFileName() + ".sh");
        Files.writeString(
            script,
            "#!/bin/sh\n"
                + "out=\"\"\n"
                + "for arg in \"$@\"; do\n"
                + "  out=\"$arg\"\n"
                + "done\n"
                + "cp \"" + fixture.toAbsolutePath() + "\" \"$out\"\n"
        );
        assertTrue(script.toFile().setExecutable(true), "Failed to mark fake ffmpeg script executable");
        return script.toAbsolutePath().toString();
    }
}
