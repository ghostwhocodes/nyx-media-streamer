package com.nyx.ffmpeg;

import com.nyx.ffmpeg.model.AudioCodec;
import com.nyx.ffmpeg.model.AudioTrackMode;
import com.nyx.ffmpeg.model.H264Preset;
import com.nyx.ffmpeg.model.H264Profile;
import com.nyx.ffmpeg.model.H265Preset;
import com.nyx.ffmpeg.model.HwAccel;
import com.nyx.ffmpeg.model.OutputFormat;
import com.nyx.ffmpeg.model.RepresentationConfig;
import com.nyx.ffmpeg.model.SegmentDuration;
import com.nyx.ffmpeg.model.SubtitleMode;
import com.nyx.ffmpeg.model.TranscodeProfiles;
import com.nyx.ffmpeg.model.VideoCodec;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FFmpegCommandBuilderTest {
    private final Path inputPath = Path.of("/media/movies/test.mkv");
    private final Path outputPath = Path.of("/tmp/transcode/manifest.mpd");

    @Test
    void profileAndFormatArgsAreRenderedCorrectly() {
        var h264Args = baseCommand(TranscodeProfiles.H264_FAST.videoCodec(), TranscodeProfiles.H264_FAST.audioCodec(), OutputFormat.Dash).toArgList();
        assertTrue(h264Args.containsAll(List.of("-progress", "pipe:1", "-y", "-i", inputPath.toString(), "libx264", "veryfast", "23", "high", "aac", "128k")));
        assertTrue(h264Args.contains("dash"));
        assertTrue(h264Args.contains("-adaptation_sets"));

        var h265Args = baseCommand(new VideoCodec.H265(H265Preset.SLOW, 22), new AudioCodec.AAC(192_000), OutputFormat.Dash).toArgList();
        assertTrue(h265Args.containsAll(List.of("libx265", "slow", "22")));

        var av1Args = baseCommand(new VideoCodec.AV1Svt(6, 30), new AudioCodec.Opus(128_000), OutputFormat.Dash).toArgList();
        assertTrue(av1Args.containsAll(List.of("libsvtav1", "6", "30", "libopus")));

        var hlsArgs = baseCommand(defaultVideoCodec(), defaultAudioCodec(), OutputFormat.Hls).toArgList();
        assertTrue(hlsArgs.contains("hls"));
        assertTrue(hlsArgs.contains("-hls_time"));

        var hlsMpegTsArgs = baseCommand(defaultVideoCodec(), defaultAudioCodec(), OutputFormat.HlsMpegTs).toArgList();
        assertTrue(hlsMpegTsArgs.contains("hls"));
        assertEquals("mpegts", hlsMpegTsArgs.get(hlsMpegTsArgs.indexOf("-hls_segment_type") + 1));
        assertFalse(hlsMpegTsArgs.contains("-hls_fmp4_init_filename"));
    }

    @Test
    void copyCodecsPreserveRemuxBehavior() {
        FFmpegCommand remux = baseCommand(VideoCodec.Copy, AudioCodec.Copy, OutputFormat.Dash);
        List<String> remuxArgs = remux.toArgList();

        assertEquals("copy", remuxArgs.get(remuxArgs.indexOf("-c:v") + 1));
        assertEquals("copy", remuxArgs.get(remuxArgs.indexOf("-c:a") + 1));
        assertFalse(remuxArgs.contains("-force_key_frames"));
        assertFalse(remuxArgs.contains("libx264"));
        assertFalse(remuxArgs.contains("aac"));

        FFmpegCommand copyVideo = baseCommand(VideoCodec.Copy, new AudioCodec.AAC(192_000), OutputFormat.Hls);
        List<String> mixedArgs = copyVideo.toArgList();
        assertEquals("copy", mixedArgs.get(mixedArgs.indexOf("-c:v") + 1));
        assertEquals("aac", mixedArgs.get(mixedArgs.indexOf("-c:a") + 1));
        assertTrue(mixedArgs.contains("192k"));
        assertFalse(mixedArgs.contains("-force_key_frames"));
    }

    @Test
    void validateRejectsInvalidCodecsAndRepresentations() {
        var h264Failure = assertValidationFails(baseCommand(new VideoCodec.H264(H264Preset.MEDIUM, 52, H264Profile.HIGH), defaultAudioCodec(), OutputFormat.Dash));
        assertTrue(h264Failure.getViolations().stream().anyMatch(message -> message.contains("H264 CRF")));

        var av1Failure = assertValidationFails(baseCommand(new VideoCodec.AV1Svt(14, 64), defaultAudioCodec(), OutputFormat.Dash));
        assertTrue(av1Failure.getViolations().stream().anyMatch(message -> message.contains("AV1 CRF")));
        assertTrue(av1Failure.getViolations().stream().anyMatch(message -> message.contains("AV1 preset")));

        var copyFailure = assertValidationFails(with(baseCommand(VideoCodec.Copy, AudioCodec.Copy, OutputFormat.Dash), null, null, null, null, null, null, null, List.of(new RepresentationConfig(1280, 720, 3000)), null, null, null));
        assertTrue(copyFailure.getViolations().stream().anyMatch(message -> message.contains("multi-representation")));

        var representationFailure = assertValidationFails(with(baseCommand(), null, null, null, null, null, null, null, List.of(new RepresentationConfig(0, 0, -1)), null, null, null));
        assertTrue(representationFailure.getViolations().size() >= 2);
    }

    @Test
    void validateAcceptsValidCommand() {
        assertDoesNotThrow(() -> baseCommand().validate());
    }

    @Test
    void builderConstructsEquivalentCommandAndPreservesOptionalFields() {
        FFmpegCommandBuilder builder = new FFmpegCommandBuilder();
        builder.setInputPath(inputPath);
        builder.setOutputPath(outputPath);
        builder.setVideoCodec(defaultVideoCodec());
        builder.setAudioCodec(defaultAudioCodec());

        FFmpegCommand built = builder.build();
        assertEquals(OutputFormat.Both, built.outputFormat());
        assertEquals(defaultVideoCodec(), built.videoCodec());
        assertEquals(defaultAudioCodec(), built.audioCodec());

        builder.setOutputFormat(OutputFormat.Hls);
        builder.setSubtitleMode(new SubtitleMode.BurnIn(1));
        builder.setAudioTrackMode(new AudioTrackMode.Single(2));
        builder.setHwAccel(HwAccel.Nvenc);
        builder.setSeekTimeSecs(30.0);
        builder.setSegmentDuration(SegmentDuration.ADAPTIVE);
        builder.setSourceDurationUs(1_000_000L);
        builder.setSourceFps(30.0);

        FFmpegCommand command = builder.build();
        assertEquals(OutputFormat.Hls, command.outputFormat());
        assertEquals(new SubtitleMode.BurnIn(1), command.subtitleMode());
        assertEquals(new AudioTrackMode.Single(2), command.audioTrackMode());
        assertEquals(HwAccel.Nvenc, command.hwAccel());
        assertEquals(30.0, command.seekTimeSecs());
        assertEquals(1_000_000L, command.sourceDurationUs());
        assertEquals(30.0, command.sourceFps());
    }

    @Test
    void builderRequiresMandatoryFields() {
        FFmpegCommandBuilder builder = new FFmpegCommandBuilder();
        builder.setOutputPath(outputPath);
        builder.setVideoCodec(defaultVideoCodec());
        builder.setAudioCodec(defaultAudioCodec());
        assertThrows(IllegalArgumentException.class, builder::build);

        builder = new FFmpegCommandBuilder();
        builder.setInputPath(inputPath);
        builder.setVideoCodec(defaultVideoCodec());
        builder.setAudioCodec(defaultAudioCodec());
        assertThrows(IllegalArgumentException.class, builder::build);

        builder = new FFmpegCommandBuilder();
        builder.setInputPath(inputPath);
        builder.setOutputPath(outputPath);
        builder.setAudioCodec(defaultAudioCodec());
        assertThrows(IllegalArgumentException.class, builder::build);

        builder = new FFmpegCommandBuilder();
        builder.setInputPath(inputPath);
        builder.setOutputPath(outputPath);
        builder.setVideoCodec(defaultVideoCodec());
        assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test
    void multiRepresentationOutputAddsFiltersAndDashAdaptationSets() {
        FFmpegCommand command = with(
            baseCommand(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(
                new RepresentationConfig(854, 480, 1500),
                new RepresentationConfig(1280, 720, 3000),
                new RepresentationConfig(1920, 1080, 6000)
            ),
            null,
            null,
            null
        );
        List<String> args = command.toArgList();

        String filterArg = args.get(args.indexOf("-filter_complex") + 1);
        assertTrue(filterArg.contains("scale=854:480"));
        assertTrue(filterArg.contains("scale=1280:720"));
        assertTrue(filterArg.contains("scale=1920:1080"));
        assertTrue(args.containsAll(List.of("1500k", "3000k", "6000k")));

        String adaptationSets = args.get(args.indexOf("-adaptation_sets") + 1);
        assertTrue(adaptationSets.contains("id=0,streams=0,1,2"));
    }

    @Test
    void hardwareAccelerationSeekAndGopConfigurationAreRenderedCorrectly() {
        List<String> vaapiArgs = with(baseCommand(), null, null, null, null, null, new HwAccel.Vaapi("/dev/dri/renderD128"), null, null, null, null, null).toArgList();
        assertTrue(vaapiArgs.containsAll(List.of("-hwaccel", "vaapi", "-hwaccel_device", "/dev/dri/renderD128")));

        List<String> nvencArgs = with(baseCommand(), null, null, null, null, null, HwAccel.Nvenc, null, null, null, null, null).toArgList();
        assertEquals("cuda", nvencArgs.get(nvencArgs.indexOf("-hwaccel") + 1));

        List<String> qsvArgs = with(baseCommand(), null, null, null, null, null, HwAccel.Qsv, null, null, null, null, null).toArgList();
        assertEquals("qsv", qsvArgs.get(qsvArgs.indexOf("-hwaccel") + 1));

        List<String> autoArgs = with(baseCommand(), null, null, null, null, null, HwAccel.Auto, null, null, null, null, null).toArgList();
        assertEquals("auto", autoArgs.get(autoArgs.indexOf("-hwaccel") + 1));

        List<String> seekArgs = with(baseCommand(), null, null, null, null, null, null, 120.5, null, null, null, null).toArgList();
        assertTrue(seekArgs.containsAll(List.of("-ss", "120.5")));

        List<String> fpsArgs = with(baseCommand(), null, null, null, null, null, null, null, null, null, null, 60.0).toArgList();
        assertEquals("360", fpsArgs.get(fpsArgs.indexOf("-g") + 1));

        List<String> defaultFpsArgs = with(baseCommand(), null, null, null, null, null, null, null, null, null, null, 0.0).toArgList();
        assertEquals("144", defaultFpsArgs.get(defaultFpsArgs.indexOf("-g") + 1));
    }

    @Test
    void audioAndSubtitleModesAffectArguments() {
        FFmpegCommand opusStereo = baseCommand(defaultVideoCodec(), new AudioCodec.Opus(128_000, 2), OutputFormat.Dash);
        List<String> opusArgs = opusStereo.toArgList();
        assertTrue(opusArgs.contains("libopus"));
        assertEquals("2", opusArgs.get(opusArgs.indexOf("-ac") + 1));

        FFmpegCommand aacSurround = baseCommand(defaultVideoCodec(), new AudioCodec.AAC(192_000, 6), OutputFormat.Dash);
        List<String> aacArgs = aacSurround.toArgList();
        assertEquals("6", aacArgs.get(aacArgs.indexOf("-ac") + 1));

        List<String> burnInArgs = with(baseCommand(), null, null, null, new SubtitleMode.BurnIn(0), null, null, null, null, null, null, null).toArgList();
        assertTrue(burnInArgs.contains("-vf"));
        assertTrue(burnInArgs.stream().anyMatch(arg -> arg.contains("subtitles=")));

        List<String> downmixArgs = with(baseCommand(), null, null, null, null, AudioTrackMode.AllWithStereoDownmix, null, null, null, null, null, null).toArgList();
        assertTrue(downmixArgs.stream().filter("-ac"::equals).count() > 0);

        List<String> singleTrackArgs = with(baseCommand(), null, null, null, null, new AudioTrackMode.Single(1), null, null, null, null, null, null).toArgList();
        assertTrue(singleTrackArgs.contains("0:a:1"));
    }

    @Test
    void h264RateControlAndCmafArgsAreRenderedCorrectly() {
        FFmpegCommand rateLimited = baseCommand(new VideoCodec.H264(H264Preset.MEDIUM, 23, H264Profile.HIGH, 4_000_000L, 8_000_000L), defaultAudioCodec(), OutputFormat.Dash);
        List<String> rateArgs = rateLimited.toArgList();
        assertEquals("4000k", rateArgs.get(rateArgs.indexOf("-maxrate") + 1));
        assertEquals("8000k", rateArgs.get(rateArgs.indexOf("-bufsize") + 1));

        FFmpegCommand maxOnly = baseCommand(new VideoCodec.H264(H264Preset.MEDIUM, 23, H264Profile.HIGH, 4_000_000L, null), defaultAudioCodec(), OutputFormat.Dash);
        assertTrue(maxOnly.toArgList().contains("-maxrate"));
        assertFalse(maxOnly.toArgList().contains("-bufsize"));

        List<String> cmafArgs = baseCommand(defaultVideoCodec(), defaultAudioCodec(), OutputFormat.Cmaf).toArgList();
        assertTrue(cmafArgs.containsAll(List.of("-hls_playlist", "1", "master.m3u8", "init_$RepresentationID$.mp4", "chunk_$RepresentationID$_$Number$.m4s")));
        assertEquals("dash", cmafArgs.get(cmafArgs.indexOf("-f") + 1));
        assertFalse(cmafArgs.contains("-hls_segment_type"));
        assertFalse(cmafArgs.contains("fmp4"));

        List<String> cmafWithRepresentations = with(
            baseCommand(defaultVideoCodec(), defaultAudioCodec(), OutputFormat.Cmaf),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(new RepresentationConfig(1920, 1080, 5000), new RepresentationConfig(1280, 720, 3000)),
            null,
            null,
            null
        ).toArgList();
        assertTrue(cmafWithRepresentations.contains("-filter_complex"));
        assertTrue(cmafWithRepresentations.stream().anyMatch(arg -> arg.contains("0,1")));
    }

    @Test
    void validationExceptionMessageIncludesAllViolations() {
        ValidationException exception = new ValidationException(List.of("error1", "error2"));
        assertTrue(exception.getMessage().contains("error1"));
        assertTrue(exception.getMessage().contains("error2"));
    }

    private VideoCodec.H264 defaultVideoCodec() {
        return new VideoCodec.H264(H264Preset.VERYFAST, 23, H264Profile.HIGH);
    }

    private AudioCodec.AAC defaultAudioCodec() {
        return new AudioCodec.AAC(128_000);
    }

    private FFmpegCommand baseCommand() {
        return baseCommand(defaultVideoCodec(), defaultAudioCodec(), OutputFormat.Dash);
    }

    private FFmpegCommand baseCommand(VideoCodec videoCodec, AudioCodec audioCodec, OutputFormat outputFormat) {
        return new FFmpegCommand(inputPath, outputPath, videoCodec, audioCodec, outputFormat);
    }

    private ValidationException assertValidationFails(FFmpegCommand command) {
        return assertThrows(ValidationException.class, command::validate);
    }

    private FFmpegCommand with(
        FFmpegCommand command,
        VideoCodec videoCodec,
        AudioCodec audioCodec,
        OutputFormat outputFormat,
        SubtitleMode subtitleMode,
        AudioTrackMode audioTrackMode,
        HwAccel hwAccel,
        Double seekTimeSecs,
        List<RepresentationConfig> representations,
        SegmentDuration segmentDuration,
        Long sourceDurationUs,
        Double sourceFps
    ) {
        return new FFmpegCommand(
            command.inputPath(),
            command.outputPath(),
            videoCodec != null ? videoCodec : command.videoCodec(),
            audioCodec != null ? audioCodec : command.audioCodec(),
            outputFormat != null ? outputFormat : command.outputFormat(),
            subtitleMode != null ? subtitleMode : command.subtitleMode(),
            audioTrackMode != null ? audioTrackMode : command.audioTrackMode(),
            hwAccel != null ? hwAccel : command.hwAccel(),
            seekTimeSecs != null ? seekTimeSecs : command.seekTimeSecs(),
            representations != null ? representations : command.representations(),
            segmentDuration != null ? segmentDuration : command.segmentDuration(),
            sourceDurationUs != null ? sourceDurationUs : command.sourceDurationUs(),
            sourceFps != null ? sourceFps : command.sourceFps()
        );
    }
}
