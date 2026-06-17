package com.nyx.ffmpeg;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.ffmpeg.model.ProbeResult;
import com.nyx.ffmpeg.model.ProbeStreams;
import com.nyx.ffmpeg.model.VideoStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FfmpegVideoPreviewCoverageTest {
    @TempDir
    Path tempDir;

    @Test
    void defaultAndSemaphoreConstructorsPreserveZeroDurationAndReleasePermits() throws IOException {
        Path source = createVideoPlaceholder();

        FfmpegVideoPreviewGenerator defaultGenerator = new FfmpegVideoPreviewGenerator(fakeProber(0.0, 1000, 500));
        VideoPreviewPlan defaultPlan = defaultGenerator.plan(source, new VideoPreviewRequest(null, null, null, 50));

        assertEquals(0L, defaultPlan.getSeekMillis());
        assertEquals(100, defaultPlan.getOutputWidth());
        assertEquals(50, defaultPlan.getOutputHeight());

        Path fixture = createFixtureImage("preview-semaphore.jpg");
        Semaphore semaphore = new Semaphore(1);
        FfmpegVideoPreviewGenerator semaphoreGenerator = new FfmpegVideoPreviewGenerator(
            fakeProber(60.0, 1280, 720),
            createCopyScript("preview-ok.sh", fixture),
            semaphore
        );

        VideoPreviewPlan plan = semaphoreGenerator.plan(source, new VideoPreviewRequest(null, 50, null, null));
        byte[] bytes = semaphoreGenerator.generate(source, plan);

        assertArrayEquals(Files.readAllBytes(fixture), bytes);
        assertEquals(1, semaphore.availablePermits());
    }

    @Test
    void planRejectsInvalidRequestsAndMissingSourceFiles() throws IOException {
        FfmpegVideoPreviewGenerator generator = new FfmpegVideoPreviewGenerator(fakeProber(60.0, 1920, 1080));
        Path source = createVideoPlaceholder();

        assertThrows(IllegalArgumentException.class, () -> generator.plan(tempDir.resolve("missing.mp4"), new VideoPreviewRequest()));
        assertThrows(IllegalArgumentException.class, () -> generator.plan(source, new VideoPreviewRequest(-1L, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> generator.plan(source, new VideoPreviewRequest(null, 101, null, null)));
        assertThrows(IllegalArgumentException.class, () -> generator.plan(source, new VideoPreviewRequest(null, null, 0, null)));
        assertThrows(IllegalArgumentException.class, () -> generator.plan(source, new VideoPreviewRequest(null, null, null, 0)));
        assertThrows(IllegalArgumentException.class, () -> generator.plan(source, new VideoPreviewRequest(1_000L, 10, null, null)));
    }

    @Test
    void planWrapsProbeFailuresAndRejectsMissingVideoDimensions() throws IOException {
        Path source = createVideoPlaceholder();

        FfmpegVideoPreviewGenerator failingGenerator = new FfmpegVideoPreviewGenerator(failingProber("probe exploded"));
        IllegalStateException probeFailure = assertThrows(
            IllegalStateException.class,
            () -> failingGenerator.plan(source, new VideoPreviewRequest())
        );
        assertTrue(probeFailure.getMessage().contains("Failed to probe video preview source"));

        FfmpegVideoPreviewGenerator missingDimensionsGenerator = new FfmpegVideoPreviewGenerator(fakeProber(10.0, 0, 720));
        IllegalArgumentException dimensionsFailure = assertThrows(
            IllegalArgumentException.class,
            () -> missingDimensionsGenerator.plan(source, new VideoPreviewRequest())
        );
        assertTrue(dimensionsFailure.getMessage().contains("missing dimensions"));
    }

    @Test
    void generateFailsWhenFfmpegReturnsNonZero() throws IOException {
        Path source = createVideoPlaceholder();
        FfmpegVideoPreviewGenerator generator = new FfmpegVideoPreviewGenerator(
            fakeProber(60.0, 1280, 720),
            createScript("preview-fail.sh", "echo failed >&2\nexit 1\n")
        );

        VideoPreviewPlan plan = generator.plan(source, new VideoPreviewRequest());

        IOException failure = assertThrows(IOException.class, () -> generator.generate(source, plan));
        assertTrue(failure.getMessage().contains("Video preview generation failed"));
    }

    @Test
    void generateFailsWhenFfmpegDoesNotCreateOutputFile() throws IOException {
        Path source = createVideoPlaceholder();
        FfmpegVideoPreviewGenerator generator = new FfmpegVideoPreviewGenerator(
            fakeProber(60.0, 1280, 720),
            createScript(
                "preview-no-output.sh",
                "out=\"\"\n"
                    + "for arg in \"$@\"; do\n"
                    + "  out=\"$arg\"\n"
                    + "done\n"
                    + "rm -f \"$out\"\n"
                    + "exit 0\n"
            )
        );

        VideoPreviewPlan plan = generator.plan(source, new VideoPreviewRequest());

        IOException failure = assertThrows(IOException.class, () -> generator.generate(source, plan));
        assertTrue(failure.getMessage().contains("Video preview frame was not created"));
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

    private MediaProber failingProber(String message) {
        return new MediaProber() {
            @Override
            public ProbeResult probe(Path path) {
                throw new IllegalStateException(message, new IOException(message));
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
        Path source = tempDir.resolve("video.mp4");
        Files.write(source, new byte[256]);
        return source;
    }

    private Path createFixtureImage(String name) throws IOException {
        Path output = tempDir.resolve(name);
        BufferedImage image = new BufferedImage(32, 18, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, "jpg", output.toFile());
        return output;
    }

    private String createCopyScript(String name, Path fixture) throws IOException {
        return createScript(
            name,
            "out=\"\"\n"
                + "for arg in \"$@\"; do\n"
                + "  out=\"$arg\"\n"
                + "done\n"
                + "cp \"" + fixture.toAbsolutePath() + "\" \"$out\"\n"
        );
    }

    private String createScript(String name, String body) throws IOException {
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
}
