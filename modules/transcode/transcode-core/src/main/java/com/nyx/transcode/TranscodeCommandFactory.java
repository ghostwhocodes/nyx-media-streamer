package com.nyx.transcode;

import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.ffmpeg.FFmpegCommand;
import com.nyx.ffmpeg.model.AdaptiveProfile;
import com.nyx.ffmpeg.model.AudioCodec;
import com.nyx.ffmpeg.model.AudioTrackMode;
import com.nyx.ffmpeg.model.H264Preset;
import com.nyx.ffmpeg.model.H264Profile;
import com.nyx.ffmpeg.model.HwAccel;
import com.nyx.ffmpeg.model.OutputFormat;
import com.nyx.ffmpeg.model.ProbeResult;
import com.nyx.ffmpeg.model.Profile;
import com.nyx.ffmpeg.model.RepresentationConfig;
import com.nyx.ffmpeg.model.SegmentDuration;
import com.nyx.ffmpeg.model.SubtitleMode;
import com.nyx.ffmpeg.model.TranscodeProfiles;
import com.nyx.ffmpeg.model.VideoCodec;
import com.nyx.stream.representation.contracts.StreamCommandOutput;
import com.nyx.stream.representation.contracts.StreamRepresentation;
import com.nyx.stream.representation.contracts.StreamRepresentationPolicy;
import com.nyx.transcode.contracts.TranscodeJob;
import com.nyx.transcode.contracts.TranscodeRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public class TranscodeCommandFactory {
    private static final StreamRepresentationPolicy REPRESENTATION_POLICY = StreamRepresentationPolicy.defaultPolicy();

    public FFmpegCommand buildPrimaryCommand(
        TranscodeJob job,
        Profile profile,
        ProbeResult probeResult,
        Path outputDir,
        TranscodeRequest request
    ) {
        OutputFormat outputFormat = resolveOutputFormat(job.getRepresentation(), profile.getDefaultOutputFormat());
        HwAccel hwAccel = request != null ? parseHwAccel(request.getHwaccel()) : HwAccel.Auto;
        SubtitleMode subtitleMode = request != null
            ? parseSubtitleMode(request.getSubtitleMode(), request.getBurnSubtitleTrack())
            : SubtitleMode.Extract;
        AudioTrackMode audioTrackMode = request != null
            ? parseAudioTrackMode(request.getAudioTracks())
            : AudioTrackMode.All;
        Double seekTimeSecs = request == null ? null : request.getStartTimeSecs();
        double sourceFps = probeResult.getStreams().getVideo().stream()
            .findFirst()
            .map(it -> it.getFps())
            .orElse(24.0);

        return new FFmpegCommand(
            Path.of(job.getInputPath()),
            outputDir.resolve("manifest.mpd"),
            profile.getVideoCodec(),
            profile.getAudioCodec(),
            outputFormat,
            subtitleMode,
            audioTrackMode,
            hwAccel,
            seekTimeSecs,
            job.getRepresentations().stream().map(RepresentationMappings::toFfmpegRepresentation).toList(),
            profile.getSegmentDuration(),
            (long) (probeResult.getDurationSecs() * 1_000_000L),
            sourceFps
        );
    }

    public FFmpegCommand buildPrimaryCommand(
        TranscodeJob job,
        Profile profile,
        ProbeResult probeResult,
        Path outputDir
    ) {
        return buildPrimaryCommand(job, profile, probeResult, outputDir, null);
    }

    public FFmpegCommand buildRemuxCommand(
        TranscodeJob job,
        ProbeResult probeResult,
        Path outputDir,
        TranscodeRequest request
    ) {
        return buildCopyVideoCommand(job, probeResult, outputDir, AudioCodec.Copy, request);
    }

    public FFmpegCommand buildRemuxCommand(
        TranscodeJob job,
        ProbeResult probeResult,
        Path outputDir
    ) {
        return buildRemuxCommand(job, probeResult, outputDir, null);
    }

    public FFmpegCommand buildAudioTranscodeCommand(
        TranscodeJob job,
        Profile profile,
        ProbeResult probeResult,
        Path outputDir,
        TranscodeRequest request
    ) {
        return buildCopyVideoCommand(job, probeResult, outputDir, profile.getAudioCodec(), request);
    }

    public FFmpegCommand buildAudioTranscodeCommand(
        TranscodeJob job,
        Profile profile,
        ProbeResult probeResult,
        Path outputDir
    ) {
        return buildAudioTranscodeCommand(job, profile, probeResult, outputDir, null);
    }

    private FFmpegCommand buildCopyVideoCommand(
        TranscodeJob job,
        ProbeResult probeResult,
        Path outputDir,
        AudioCodec audioCodec,
        TranscodeRequest request
    ) {
        SubtitleMode subtitleMode = request != null
            ? parseSubtitleMode(request.getSubtitleMode(), request.getBurnSubtitleTrack())
            : SubtitleMode.Extract;
        AudioTrackMode audioTrackMode = request != null
            ? parseAudioTrackMode(request.getAudioTracks())
            : AudioTrackMode.All;
        Double seekTimeSecs = request == null ? null : request.getStartTimeSecs();
        double sourceFps = probeResult.getStreams().getVideo().stream()
            .findFirst()
            .map(it -> it.getFps())
            .orElse(24.0);

        return new FFmpegCommand(
            Path.of(job.getInputPath()),
            outputDir.resolve("manifest.mpd"),
            VideoCodec.Copy,
            audioCodec,
            resolveOutputFormat(job.getRepresentation(), OutputFormat.Both),
            subtitleMode,
            audioTrackMode,
            HwAccel.None,
            seekTimeSecs,
            List.of(),
            SegmentDuration.ADAPTIVE,
            (long) (probeResult.getDurationSecs() * 1_000_000L),
            sourceFps
        );
    }

    public FFmpegCommand buildFallbackCommand(
        TranscodeJob job,
        ProbeResult probeResult,
        Path outputDir
    ) {
        OutputFormat outputFormat = resolveOutputFormat(job.getRepresentation(), OutputFormat.Both);

        double sourceFps = probeResult.getStreams().getVideo().stream()
            .findFirst()
            .map(it -> it.getFps())
            .orElse(24.0);

        return new FFmpegCommand(
            Path.of(job.getInputPath()),
            outputDir.resolve("manifest.mpd"),
            new VideoCodec.H264(H264Preset.VERYFAST, 23, H264Profile.HIGH),
            new AudioCodec.AAC(128_000),
            outputFormat,
            SubtitleMode.Extract,
            AudioTrackMode.All,
            HwAccel.None,
            null,
            List.of(),
            SegmentDuration.ADAPTIVE,
            (long) (probeResult.getDurationSecs() * 1_000_000L),
            sourceFps
        );
    }

    public Profile resolveProfile(String profileName) throws NyxException {
        Profile profile = TranscodeProfiles.findByName(profileName);
        if (profile == null) {
            throw new NyxException(ErrorCode.INVALID_REQUEST, "Unknown profile: " + profileName);
        }
        return profile;
    }

    public HwAccel parseHwAccel(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "none" -> HwAccel.None;
            case "vaapi" -> new HwAccel.Vaapi("/dev/dri/renderD128");
            case "nvenc" -> HwAccel.Nvenc;
            case "qsv" -> HwAccel.Qsv;
            default -> HwAccel.Auto;
        };
    }

    public SubtitleMode parseSubtitleMode(String mode, Integer burnTrack) {
        String normalized = mode == null ? "" : mode.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "burn" -> new SubtitleMode.BurnIn(burnTrack == null ? 0 : burnTrack);
            default -> SubtitleMode.Extract;
        };
    }

    public AudioTrackMode parseAudioTrackMode(String tracks) {
        String normalized = tracks == null ? "" : tracks.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "all" -> AudioTrackMode.All;
            case "all_stereo" -> AudioTrackMode.AllWithStereoDownmix;
            default -> {
                Integer parsed = parseInteger(tracks);
                yield parsed == null ? AudioTrackMode.All : new AudioTrackMode.Single(parsed);
            }
        };
    }

    private OutputFormat resolveOutputFormat(StreamRepresentation representation, OutputFormat fallback) {
        StreamCommandOutput output = REPRESENTATION_POLICY.commandOutput(representation);
        return switch (output) {
            case DASH_FMP4 -> OutputFormat.Dash;
            case HLS_FMP4 -> OutputFormat.Hls;
            case HLS_MPEG_TS -> OutputFormat.HlsMpegTs;
            case HLS_DASH_FMP4 -> OutputFormat.Both;
            case CMAF -> OutputFormat.Cmaf;
            case DIRECT_FILE -> throw new IllegalArgumentException(
                "Direct file representation is only valid for direct-play playback delivery"
            );
        };
    }

    private static Integer parseInteger(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
