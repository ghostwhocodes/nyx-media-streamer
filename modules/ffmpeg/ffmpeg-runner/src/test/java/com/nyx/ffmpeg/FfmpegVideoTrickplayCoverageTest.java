package com.nyx.ffmpeg;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.ffmpeg.model.ProbeResult;
import com.nyx.ffmpeg.model.ProbeStreams;
import com.nyx.ffmpeg.model.VideoStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FfmpegVideoTrickplayCoverageTest {
    @TempDir
    Path tempDir;

    @Test
    void defaultConstructorUsesZeroDurationFallbacksAndHeightScaling() throws IOException {
        Path source = createVideoPlaceholder();
        FfmpegVideoTrickplayGenerator generator = new FfmpegVideoTrickplayGenerator(fakeProber(0.0, 1000, 500));

        VideoTrickplayPlan plan = generator.plan(
            source,
            new VideoTrickplayRequest(Set.of(TrickplayAssetKind.STORYBOARD_SHEET), null, null, 50, null, null)
        );

        assertEquals(10_000L, plan.intervalMillis());
        assertEquals(100, plan.thumbnailWidth());
        assertEquals(50, plan.thumbnailHeight());
        assertEquals(1, plan.assets().size());
        assertEquals(1, plan.timeline().size());
    }

    @Test
    void customIntervalBucketAndSemaphorePathsRemainStable() throws IOException {
        Path source = createVideoPlaceholder();
        Path fixture = createFixtureImage("trickplay-semaphore.jpg");
        Semaphore semaphore = new Semaphore(1);
        FfmpegVideoTrickplayGenerator generator = new FfmpegVideoTrickplayGenerator(
            fakeProber(13.0, 1000, 500),
            createCopyScript("trickplay-ok.sh", fixture),
            semaphore,
            320,
            180,
            4,
            4,
            10,
            4,
            1_000L,
            1L
        );

        VideoTrickplayPlan plan = generator.plan(
            source,
            new VideoTrickplayRequest(Set.of(TrickplayAssetKind.PREVIEW_STRIP), null, 400, null, 6, null)
        );
        byte[] bytes = generator.generate(source, plan.assets().getFirst());

        assertEquals(3_250L, plan.intervalMillis());
        assertEquals(400, plan.thumbnailWidth());
        assertEquals(200, plan.thumbnailHeight());
        assertArrayEquals(Files.readAllBytes(fixture), bytes);
        assertEquals(1, semaphore.availablePermits());
    }

    @Test
    void planRejectsInvalidRequestsAndMissingFiles() throws IOException {
        FfmpegVideoTrickplayGenerator generator = new FfmpegVideoTrickplayGenerator(fakeProber(60.0, 1920, 1080));
        Path source = createVideoPlaceholder();

        assertThrows(
            IllegalArgumentException.class,
            () -> generator.plan(tempDir.resolve("missing.mp4"), new VideoTrickplayRequest(Set.of(TrickplayAssetKind.STORYBOARD_SHEET), null, null, null, null, null))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> generator.plan(source, new VideoTrickplayRequest(Set.of(TrickplayAssetKind.STORYBOARD_SHEET), null, 0, null, null, null))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> generator.plan(source, new VideoTrickplayRequest(Set.of(TrickplayAssetKind.STORYBOARD_SHEET), null, null, 0, null, null))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> generator.plan(source, new VideoTrickplayRequest(Set.of(TrickplayAssetKind.STORYBOARD_SHEET), null, null, null, 0, null))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> generator.plan(source, new VideoTrickplayRequest(Set.of(TrickplayAssetKind.STORYBOARD_SHEET), null, null, null, null, 0))
        );
    }

    @Test
    void planWrapsProbeFailuresAndRejectsMissingDimensions() throws IOException {
        Path source = createVideoPlaceholder();

        FfmpegVideoTrickplayGenerator failingGenerator = new FfmpegVideoTrickplayGenerator(failingProber("probe exploded"));
        IllegalStateException probeFailure = assertThrows(
            IllegalStateException.class,
            () -> failingGenerator.plan(source, new VideoTrickplayRequest(Set.of(TrickplayAssetKind.STORYBOARD_SHEET), null, null, null, null, null))
        );
        assertTrue(probeFailure.getMessage().contains("Failed to probe trickplay source"));

        FfmpegVideoTrickplayGenerator missingDimensionsGenerator = new FfmpegVideoTrickplayGenerator(fakeProber(10.0, 0, 720));
        IllegalArgumentException dimensionsFailure = assertThrows(
            IllegalArgumentException.class,
            () -> missingDimensionsGenerator.plan(source, new VideoTrickplayRequest(Set.of(TrickplayAssetKind.STORYBOARD_SHEET), null, null, null, null, null))
        );
        assertTrue(dimensionsFailure.getMessage().contains("missing dimensions"));
    }

    @Test
    void generateFailsWhenFfmpegReturnsNonZero() throws IOException {
        Path source = createVideoPlaceholder();
        FfmpegVideoTrickplayGenerator generator = new FfmpegVideoTrickplayGenerator(
            fakeProber(60.0, 1280, 720),
            createScript("trickplay-fail.sh", "echo failed >&2\nexit 1\n")
        );

        VideoTrickplayAssetPlan asset = generator.plan(
            source,
            new VideoTrickplayRequest(Set.of(TrickplayAssetKind.STORYBOARD_SHEET), 10_000L, null, null, null, null)
        ).assets().getFirst();

        IOException failure = assertThrows(IOException.class, () -> generator.generate(source, asset));
        assertTrue(failure.getMessage().contains("Video trickplay generation failed"));
    }

    @Test
    void generateFailsWhenFfmpegDoesNotCreateOutputFile() throws IOException {
        Path source = createVideoPlaceholder();
        FfmpegVideoTrickplayGenerator generator = new FfmpegVideoTrickplayGenerator(
            fakeProber(60.0, 1280, 720),
            createScript(
                "trickplay-no-output.sh",
                "out=\"\"\n"
                    + "for arg in \"$@\"; do\n"
                    + "  out=\"$arg\"\n"
                    + "done\n"
                    + "rm -f \"$out\"\n"
                    + "exit 0\n"
            )
        );

        VideoTrickplayAssetPlan asset = generator.plan(
            source,
            new VideoTrickplayRequest(Set.of(TrickplayAssetKind.STORYBOARD_SHEET), 10_000L, null, null, null, null)
        ).assets().getFirst();

        IOException failure = assertThrows(IOException.class, () -> generator.generate(source, asset));
        assertTrue(failure.getMessage().contains("Video trickplay image was not created"));
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
        Files.write(source, new byte[512]);
        return source;
    }

    private Path createFixtureImage(String name) throws IOException {
        Path output = tempDir.resolve(name);
        BufferedImage image = new BufferedImage(64, 36, BufferedImage.TYPE_INT_RGB);
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
