package com.nyx.ffmpeg;

import com.nyx.ffmpeg.model.AudioCodec;
import com.nyx.ffmpeg.model.AudioTrackMode;
import com.nyx.ffmpeg.model.H264Preset;
import com.nyx.ffmpeg.model.H264Profile;
import com.nyx.ffmpeg.model.HwAccel;
import com.nyx.ffmpeg.model.OutputFormat;
import com.nyx.ffmpeg.model.SegmentDuration;
import com.nyx.ffmpeg.model.SubtitleMode;
import com.nyx.ffmpeg.model.VideoCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubtitleExtractorTest {
    @TempDir
    Path tempDir;

    @Test
    void burnInFilterForTextSubsUsesSubtitlesFilter() {
        String filter = SubtitleExtractor.buildBurnInFilter(Path.of("/media/test.mkv"), 0, false);

        assertTrue(filter.contains("subtitles="));
        assertTrue(filter.contains("si=0"));
    }

    @Test
    void burnInFilterForBitmapSubsUsesOverlayFilter() {
        String filter = SubtitleExtractor.buildBurnInFilter(Path.of("/media/test.mkv"), 1, true);

        assertTrue(filter.contains("overlay"));
        assertTrue(filter.contains("[0:s:1]"));
    }

    @Test
    void ffmpegCommandWithBurnInSubtitleIncludesVfFilter() {
        FFmpegCommand command = ffmpegCommand(
            Path.of("/media/test.mkv"),
            Path.of("/tmp/out/manifest.mpd"),
            new VideoCodec.H264(H264Preset.MEDIUM, 20, H264Profile.HIGH),
            aac(128_000),
            OutputFormat.Dash,
            burnIn(0),
            AudioTrackMode.All
        );

        List<String> args = command.toArgList();
        assertTrue(args.contains("-vf"));
        int vfIndex = args.indexOf("-vf");
        assertTrue(args.get(vfIndex + 1).contains("subtitles="));
        assertTrue(args.get(vfIndex + 1).contains("si=0"));
    }

    @Test
    void ffmpegCommandWithAllWithStereoDownmixMapsAudioAndAddsStereoDownmix() {
        FFmpegCommand command = ffmpegCommand(
            Path.of("/media/test.mkv"),
            Path.of("/tmp/out/manifest.mpd"),
            new VideoCodec.H264(H264Preset.MEDIUM, 20, H264Profile.HIGH),
            aac(128_000),
            OutputFormat.Dash,
            SubtitleMode.Extract,
            AudioTrackMode.AllWithStereoDownmix
        );

        List<String> args = command.toArgList();
        long mapCount = 0;
        for (int index = 0; index < args.size() - 1; index++) {
            if ("-map".equals(args.get(index)) && "0:a".equals(args.get(index + 1))) {
                mapCount++;
            }
        }
        assertEquals(2, mapCount);

        assertTrue(args.contains("-ac"));
        int acIndex = args.indexOf("-ac");
        assertEquals("2", args.get(acIndex + 1));
    }

    @Test
    void ffmpegCommandWithSingleAudioTrackMapsSpecificIndex() {
        FFmpegCommand command = ffmpegCommand(
            Path.of("/media/test.mkv"),
            Path.of("/tmp/out/manifest.mpd"),
            new VideoCodec.H264(H264Preset.MEDIUM, 20, H264Profile.HIGH),
            aac(128_000),
            OutputFormat.Dash,
            SubtitleMode.Extract,
            singleTrack(1)
        );

        List<String> args = command.toArgList();
        assertTrue(args.contains("0:a:1"));
    }

    @Test
    void extractWebVttSucceedsWhenFfmpegCreatesOutputFile() throws IOException {
        String scriptContent = "OUTPUT=\"${@: -1}\"\n"
            + "echo \"WEBVTT\" > \"$OUTPUT\"\n"
            + "echo \"\" >> \"$OUTPUT\"\n"
            + "echo \"00:00:01.000 --> 00:00:02.000\" >> \"$OUTPUT\"\n"
            + "echo \"Hello World\" >> \"$OUTPUT\"\n"
            + "exit 0";
        Path script = createScript("fake-ffmpeg.sh", scriptContent);

        SubtitleExtractor extractor = new SubtitleExtractor(script.toString());
        Path sourcePath = tempDir.resolve("video.mkv");
        Files.createFile(sourcePath);
        Path outputDir = Files.createDirectories(tempDir.resolve("output"));

        Path outputFile = extractor.extractWebVtt(sourcePath, 0, outputDir);

        assertTrue(Files.exists(outputFile));
        String content = Files.readString(outputFile);
        assertTrue(content.contains("WEBVTT"));
    }

    @Test
    void extractWebVttFailsWhenFfmpegReturnsNonZero() throws IOException {
        Path script = createScript("fail-ffmpeg.sh", "exit 1");

        SubtitleExtractor extractor = new SubtitleExtractor(script.toString());
        Path sourcePath = tempDir.resolve("video2.mkv");
        Files.createFile(sourcePath);
        Path outputDir = Files.createDirectories(tempDir.resolve("output2"));

        IOException failure = assertThrows(
            IOException.class,
            () -> extractor.extractWebVtt(sourcePath, 0, outputDir)
        );
        assertTrue(failure.getMessage().contains("Failed to extract subtitle"));
    }

    @Test
    void extractWebVttGeneratesCorrectOutputPath() throws IOException {
        String scriptContent = "OUTPUT=\"${@: -1}\"\n"
            + "echo \"WEBVTT\" > \"$OUTPUT\"\n"
            + "exit 0";
        Path script = createScript("path-ffmpeg.sh", scriptContent);

        SubtitleExtractor extractor = new SubtitleExtractor(script.toString());
        Path sourcePath = tempDir.resolve("video3.mkv");
        Files.createFile(sourcePath);
        Path outputDir = Files.createDirectories(tempDir.resolve("output3"));

        Path outputFile = extractor.extractWebVtt(sourcePath, 2, outputDir);

        assertEquals("subtitle_2.vtt", outputFile.getFileName().toString());
    }

    @Test
    void buildBurnInFilterForTextSubsWithNonZeroTrackIndex() {
        String filter = SubtitleExtractor.buildBurnInFilter(Path.of("/media/movie.mkv"), 3, false);

        assertTrue(filter.contains("si=3"));
        assertTrue(filter.contains("subtitles="));
    }

    @Test
    void buildBurnInFilterForBitmapSubsWithNonZeroTrackIndex() {
        String filter = SubtitleExtractor.buildBurnInFilter(Path.of("/media/movie.mkv"), 2, true);

        assertTrue(filter.contains("[0:s:2]"));
        assertTrue(filter.contains("overlay"));
    }

    private Path createScript(String name, String content) throws IOException {
        Path script = tempDir.resolve(name);
        Files.writeString(script, "#!/bin/bash\n" + content + "\n");
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

    private static FFmpegCommand ffmpegCommand(
        Path inputPath,
        Path outputPath,
        VideoCodec videoCodec,
        AudioCodec audioCodec,
        OutputFormat outputFormat,
        SubtitleMode subtitleMode,
        AudioTrackMode audioTrackMode
    ) {
        return new FFmpegCommand(
            inputPath,
            outputPath,
            videoCodec,
            audioCodec,
            outputFormat,
            subtitleMode,
            audioTrackMode,
            HwAccel.None,
            null,
            List.of(),
            SegmentDuration.ADAPTIVE,
            0L,
            24.0
        );
    }

    private static AudioCodec.AAC aac(int bitrate) {
        return new AudioCodec.AAC(bitrate);
    }

    private static SubtitleMode.BurnIn burnIn(int trackIndex) {
        return new SubtitleMode.BurnIn(trackIndex);
    }

    private static AudioTrackMode.Single singleTrack(int index) {
        return new AudioTrackMode.Single(index);
    }
}
