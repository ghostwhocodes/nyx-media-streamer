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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmpegVideoTrickplayGeneratorTest {
    @TempDir
    Path tempDir;

    @Test
    void planSelectsBoundedDefaultIntervalAndBuildsStoryboardTimelineFromProbeMetadata() throws IOException {
        Path source = createVideoPlaceholder();
        FfmpegVideoTrickplayGenerator generator = new FfmpegVideoTrickplayGenerator(
            fakeProber(1_800.0, 1920, 1080),
            createCopyScript(createFixtureImage("storyboard-default.jpg"), null)
        );

        VideoTrickplayPlan plan = generator.plan(
            source,
            new VideoTrickplayRequest(Set.of(TrickplayAssetKind.STORYBOARD_SHEET), null, null, null, null, null)
        );

        assertEquals(15_000L, plan.intervalMillis());
        assertEquals(320, plan.thumbnailWidth());
        assertEquals(180, plan.thumbnailHeight());
        assertEquals(120, plan.getTotalFrames());
        assertTrue(plan.assets().stream().allMatch(asset -> asset.getKind() == TrickplayAssetKind.STORYBOARD_SHEET));
        assertEquals(8, plan.assets().size());
        assertEquals(120, plan.timeline().size());
    }

    @Test
    void planPreservesAspectRatioAndSupportsStripOutputs() throws IOException {
        Path source = createVideoPlaceholder();
        FfmpegVideoTrickplayGenerator generator = new FfmpegVideoTrickplayGenerator(
            fakeProber(120.0, 1000, 500),
            createCopyScript(createFixtureImage("preview-strip.jpg"), null)
        );

        VideoTrickplayPlan plan = generator.plan(
            source,
            new VideoTrickplayRequest(Set.of(TrickplayAssetKind.PREVIEW_STRIP), 10_000L, 400, 300, 6, null)
        );

        VideoTrickplayAssetPlan firstAsset = plan.assets().getFirst();
        assertEquals(400, plan.thumbnailWidth());
        assertEquals(200, plan.thumbnailHeight());
        assertEquals(6, firstAsset.getTileColumns());
        assertEquals(1, firstAsset.getTileRows());
        assertEquals(2_400, firstAsset.getOutputWidth());
        assertEquals(200, firstAsset.getOutputHeight());
    }

    @Test
    void generateWritesTiledFfmpegOutputAndIncludesTimelineFilters() throws IOException {
        Path source = createVideoPlaceholder();
        Path fixture = createFixtureImage("trickplay-generate.jpg");
        Path commandLog = tempDir.resolve("ffmpeg-command.log");
        FfmpegVideoTrickplayGenerator generator = new FfmpegVideoTrickplayGenerator(
            fakeProber(600.0, 1920, 1080),
            createCopyScript(fixture, commandLog)
        );

        VideoTrickplayAssetPlan asset = generator.plan(
            source,
            new VideoTrickplayRequest(Set.of(TrickplayAssetKind.STORYBOARD_SHEET), 10_000L, 320, 180, 4, 4)
        ).assets().getFirst();

        byte[] bytes = generator.generate(source, asset);

        assertArrayEquals(Files.readAllBytes(fixture), bytes);
        String command = Files.readString(commandLog);
        assertTrue(command.contains("-ss 0.000"));
        assertTrue(command.contains("fps=1/10.000"));
        assertTrue(command.contains("pad=320:180"));
        assertTrue(command.contains("tile=4x4:nb_frames=16"));
    }

    @Test
    void planRejectsInvalidTrickplayRequests() throws IOException {
        Path source = createVideoPlaceholder();
        FfmpegVideoTrickplayGenerator generator = new FfmpegVideoTrickplayGenerator(
            fakeProber(120.0, 1280, 720),
            createCopyScript(createFixtureImage("invalid-request.jpg"), null)
        );

        IllegalArgumentException emptyKinds = assertThrows(
            IllegalArgumentException.class,
            () -> generator.plan(source, new VideoTrickplayRequest(Set.of(), null, null, null, null, null))
        );
        assertTrue(emptyKinds.getMessage().contains("assetKinds"));

        IllegalArgumentException badInterval = assertThrows(
            IllegalArgumentException.class,
            () -> generator.plan(
                source,
                new VideoTrickplayRequest(Set.of(TrickplayAssetKind.STORYBOARD_SHEET), 500L, null, null, null, null)
            )
        );
        assertTrue(badInterval.getMessage().contains("intervalMillis"));
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

    private String createCopyScript(Path fixture, Path commandLog) throws IOException {
        Path script = tempDir.resolve("fake-ffmpeg-trickplay-" + fixture.getFileName() + ".sh");
        String logLine = commandLog == null
            ? "true"
            : "printf '%s\\n' \"$*\" > \"" + commandLog.toAbsolutePath() + "\"";
        Files.writeString(
            script,
            "#!/bin/sh\n"
                + logLine + "\n"
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
