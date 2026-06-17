package com.nyx.ffmpeg;

import com.nyx.ffmpeg.model.AudioCodec;
import com.nyx.ffmpeg.model.AudioTrackMode;
import com.nyx.ffmpeg.model.HwAccel;
import com.nyx.ffmpeg.model.OutputFormat;
import com.nyx.ffmpeg.model.RepresentationConfig;
import com.nyx.ffmpeg.model.SegmentDuration;
import com.nyx.ffmpeg.model.SubtitleMode;
import com.nyx.ffmpeg.model.VideoCodec;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record FFmpegCommand(
    Path inputPath,
    Path outputPath,
    VideoCodec videoCodec,
    AudioCodec audioCodec,
    OutputFormat outputFormat,
    SubtitleMode subtitleMode,
    AudioTrackMode audioTrackMode,
    HwAccel hwAccel,
    Double seekTimeSecs,
    List<RepresentationConfig> representations,
    SegmentDuration segmentDuration,
    long sourceDurationUs,
    double sourceFps
) {
    public FFmpegCommand {
        inputPath = Objects.requireNonNull(inputPath, "inputPath");
        outputPath = Objects.requireNonNull(outputPath, "outputPath");
        videoCodec = Objects.requireNonNull(videoCodec, "videoCodec");
        audioCodec = Objects.requireNonNull(audioCodec, "audioCodec");
        outputFormat = outputFormat == null ? OutputFormat.Both : outputFormat;
        subtitleMode = subtitleMode == null ? SubtitleMode.Extract : subtitleMode;
        audioTrackMode = audioTrackMode == null ? AudioTrackMode.All : audioTrackMode;
        hwAccel = hwAccel == null ? HwAccel.None : hwAccel;
        representations = representations == null ? List.of() : List.copyOf(representations);
        segmentDuration = segmentDuration == null ? SegmentDuration.ADAPTIVE : segmentDuration;
    }

    public FFmpegCommand(
        Path inputPath,
        Path outputPath,
        VideoCodec videoCodec,
        AudioCodec audioCodec,
        OutputFormat outputFormat
    ) {
        this(
            inputPath,
            outputPath,
            videoCodec,
            audioCodec,
            outputFormat,
            SubtitleMode.Extract,
            AudioTrackMode.All,
            HwAccel.None,
            null,
            List.of(),
            SegmentDuration.ADAPTIVE,
            0L,
            24.0
        );
    }

    public Path getInputPath() {
        return inputPath;
    }

    public Path getOutputPath() {
        return outputPath;
    }

    public VideoCodec getVideoCodec() {
        return videoCodec;
    }

    public AudioCodec getAudioCodec() {
        return audioCodec;
    }

    public OutputFormat getOutputFormat() {
        return outputFormat;
    }

    public SubtitleMode getSubtitleMode() {
        return subtitleMode;
    }

    public AudioTrackMode getAudioTrackMode() {
        return audioTrackMode;
    }

    public HwAccel getHwAccel() {
        return hwAccel;
    }

    public Double getSeekTimeSecs() {
        return seekTimeSecs;
    }

    public List<RepresentationConfig> getRepresentations() {
        return representations;
    }

    public SegmentDuration getSegmentDuration() {
        return segmentDuration;
    }

    public long getSourceDurationUs() {
        return sourceDurationUs;
    }

    public double getSourceFps() {
        return sourceFps;
    }

    public void validate() {
        List<String> violations = new ArrayList<>();

        if (videoCodec instanceof VideoCodec.H264 h264) {
            if (h264.getCrf() < 0 || h264.getCrf() > 51) {
                violations.add("H264 CRF must be 0-51, got " + h264.getCrf());
            }
        } else if (videoCodec instanceof VideoCodec.H265 h265) {
            if (h265.getCrf() < 0 || h265.getCrf() > 51) {
                violations.add("H265 CRF must be 0-51, got " + h265.getCrf());
            }
        } else if (videoCodec instanceof VideoCodec.AV1Svt av1) {
            if (av1.getCrf() < 0 || av1.getCrf() > 63) {
                violations.add("AV1 CRF must be 0-63, got " + av1.getCrf());
            }
            if (av1.getPreset() < 0 || av1.getPreset() > 13) {
                violations.add("AV1 preset must be 0-13, got " + av1.getPreset());
            }
        } else if (videoCodec instanceof VideoCodec.Copy && !representations.isEmpty()) {
            violations.add("Video copy cannot be combined with multi-representation output");
        }

        for (RepresentationConfig representation : representations) {
            if (representation.getWidth() <= 0 || representation.getHeight() <= 0) {
                violations.add(
                    "Representation dimensions must be positive: "
                        + representation.getWidth() + "x" + representation.getHeight()
                );
            }
            if (representation.getBitrateKbps() <= 0) {
                violations.add("Representation bitrate must be positive: " + representation.getBitrateKbps());
            }
        }

        if (!violations.isEmpty()) {
            FFmpegCommand.<RuntimeException>throwUnchecked(new ValidationException(violations));
        }
    }

    public List<String> toArgList() {
        List<String> args = new ArrayList<>();
        args.add("-progress");
        args.add("pipe:1");
        args.add("-y");

        addHwAccelArgs(args, hwAccel);

        if (seekTimeSecs != null) {
            args.add("-ss");
            args.add(seekTimeSecs.toString());
        }

        args.add("-i");
        args.add(inputPath.toString());

        addVideoCodecArgs(args, videoCodec);
        addAudioCodecArgs(args, audioCodec, audioTrackMode);

        if (subtitleMode instanceof SubtitleMode.BurnIn burnIn) {
            args.add("-vf");
            args.add("subtitles=" + inputPath + ":si=" + burnIn.getTrackIndex());
        }

        if (!(videoCodec instanceof VideoCodec.Copy)) {
            int steadyStateSecs = segmentDuration.getSteadyStateSecs();
            double fps = sourceFps > 0.0 ? sourceFps : 24.0;
            int gopSize = (int) (steadyStateSecs * fps);
            args.add("-g");
            args.add(Integer.toString(gopSize));
            args.add("-keyint_min");
            args.add(Integer.toString(gopSize));
            args.add("-force_key_frames");
            args.add("expr:gte(t,n_forced*" + steadyStateSecs + ")");
        }

        if (!representations.isEmpty()) {
            addMultiRepresentationArgs(args, representations);
        }

        addOutputFormatArgs(args, outputFormat, segmentDuration, representations);
        args.add(outputPath.toString());

        return List.copyOf(args);
    }

    private static void addHwAccelArgs(List<String> args, HwAccel hwAccel) {
        if (hwAccel instanceof HwAccel.Auto) {
            args.add("-hwaccel");
            args.add("auto");
        } else if (hwAccel instanceof HwAccel.Vaapi vaapi) {
            args.add("-hwaccel");
            args.add("vaapi");
            args.add("-hwaccel_device");
            args.add(vaapi.getDevice());
        } else if (hwAccel instanceof HwAccel.Nvenc) {
            args.add("-hwaccel");
            args.add("cuda");
        } else if (hwAccel instanceof HwAccel.Qsv) {
            args.add("-hwaccel");
            args.add("qsv");
        }
    }

    private static void addVideoCodecArgs(List<String> args, VideoCodec codec) {
        if (codec instanceof VideoCodec.Copy) {
            args.add("-c:v");
            args.add("copy");
            return;
        }
        if (codec instanceof VideoCodec.H264 h264) {
            args.add("-c:v");
            args.add("libx264");
            args.add("-preset");
            args.add(h264.getPreset().getValue());
            args.add("-crf");
            args.add(Integer.toString(h264.getCrf()));
            args.add("-profile:v");
            args.add(h264.getProfile().getValue());
            if (h264.getMaxRate() != null) {
                args.add("-maxrate");
                args.add((h264.getMaxRate() / 1000) + "k");
                if (h264.getBufSize() != null) {
                    args.add("-bufsize");
                    args.add((h264.getBufSize() / 1000) + "k");
                }
            }
            return;
        }
        if (codec instanceof VideoCodec.H265 h265) {
            args.add("-c:v");
            args.add("libx265");
            args.add("-preset");
            args.add(h265.getPreset().getValue());
            args.add("-crf");
            args.add(Integer.toString(h265.getCrf()));
            return;
        }
        if (codec instanceof VideoCodec.AV1Svt av1) {
            args.add("-c:v");
            args.add("libsvtav1");
            args.add("-preset");
            args.add(Integer.toString(av1.getPreset()));
            args.add("-crf");
            args.add(Integer.toString(av1.getCrf()));
            return;
        }
        throw new IllegalStateException("Unsupported video codec: " + codec);
    }

    private static void addAudioCodecArgs(List<String> args, AudioCodec codec, AudioTrackMode trackMode) {
        if (trackMode instanceof AudioTrackMode.All) {
            args.add("-map");
            args.add("0:a");
        } else if (trackMode instanceof AudioTrackMode.AllWithStereoDownmix) {
            args.add("-map");
            args.add("0:a");
        } else if (trackMode instanceof AudioTrackMode.Single single) {
            args.add("-map");
            args.add("0:a:" + single.getIndex());
        }

        args.add("-map");
        args.add("0:v:0");

        if (codec instanceof AudioCodec.Copy) {
            args.add("-c:a");
            args.add("copy");
        } else if (codec instanceof AudioCodec.AAC aac) {
            args.add("-c:a");
            args.add("aac");
            args.add("-b:a");
            args.add((aac.getBitrate() / 1000) + "k");
            if (aac.getChannels() != null) {
                args.add("-ac");
                args.add(aac.getChannels().toString());
            }
        } else if (codec instanceof AudioCodec.Opus opus) {
            args.add("-c:a");
            args.add("libopus");
            args.add("-b:a");
            args.add((opus.getBitrate() / 1000) + "k");
            if (opus.getChannels() != null) {
                args.add("-ac");
                args.add(opus.getChannels().toString());
            }
        } else {
            throw new IllegalStateException("Unsupported audio codec: " + codec);
        }

        if (trackMode instanceof AudioTrackMode.AllWithStereoDownmix) {
            args.add("-map");
            args.add("0:a");
            args.add("-ac");
            args.add("2");
        }
    }

    private static void addMultiRepresentationArgs(List<String> args, List<RepresentationConfig> representations) {
        List<String> filterParts = new ArrayList<>(representations.size());
        for (int index = 0; index < representations.size(); index++) {
            RepresentationConfig representation = representations.get(index);
            filterParts.add("[0:v:0]scale=" + representation.getWidth() + ":" + representation.getHeight() + "[v" + index + "]");
        }
        args.add("-filter_complex");
        args.add(String.join(";", filterParts));

        for (int index = 0; index < representations.size(); index++) {
            RepresentationConfig representation = representations.get(index);
            args.add("-map");
            args.add("[v" + index + "]");
            args.add("-b:v:" + index);
            args.add(representation.getBitrateKbps() + "k");
        }
    }

    private static void addOutputFormatArgs(
        List<String> args,
        OutputFormat format,
        SegmentDuration segmentDuration,
        List<RepresentationConfig> representations
    ) {
        if (format instanceof OutputFormat.Dash || format instanceof OutputFormat.Both) {
            args.add("-f");
            args.add("dash");
            args.add("-seg_duration");
            args.add(Integer.toString(segmentDuration.getSteadyStateSecs()));
            args.add("-use_timeline");
            args.add("1");
            args.add("-use_template");
            args.add("1");
            args.add("-adaptation_sets");
            args.add(representations.isEmpty()
                ? "id=0,streams=v id=1,streams=a"
                : "id=0,streams=" + representationIndices(representations.size()) + " id=1,streams=a");
            return;
        }
        if (format instanceof OutputFormat.Hls) {
            args.add("-f");
            args.add("hls");
            args.add("-hls_time");
            args.add(Integer.toString(segmentDuration.getSteadyStateSecs()));
            args.add("-hls_playlist_type");
            args.add("event");
            args.add("-hls_segment_type");
            args.add("fmp4");
            args.add("-hls_fmp4_init_filename");
            args.add("init.mp4");
            return;
        }
        if (format instanceof OutputFormat.HlsMpegTs) {
            args.add("-f");
            args.add("hls");
            args.add("-hls_time");
            args.add(Integer.toString(segmentDuration.getSteadyStateSecs()));
            args.add("-hls_playlist_type");
            args.add("event");
            args.add("-hls_segment_type");
            args.add("mpegts");
            return;
        }
        if (format instanceof OutputFormat.Cmaf) {
            args.add("-f");
            args.add("dash");
            args.add("-seg_duration");
            args.add(Integer.toString(segmentDuration.getSteadyStateSecs()));
            args.add("-use_timeline");
            args.add("1");
            args.add("-use_template");
            args.add("1");
            args.add("-hls_playlist");
            args.add("1");
            args.add("-hls_master_name");
            args.add("master.m3u8");
            args.add("-init_seg_name");
            args.add("init_$RepresentationID$.mp4");
            args.add("-media_seg_name");
            args.add("chunk_$RepresentationID$_$Number$.m4s");
            args.add("-adaptation_sets");
            args.add(representations.isEmpty()
                ? "id=0,streams=v id=1,streams=a"
                : "id=0,streams=" + representationIndices(representations.size()) + " id=1,streams=a");
            return;
        }
        throw new IllegalStateException("Unsupported output format: " + format);
    }

    private static String representationIndices(int size) {
        List<String> indices = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            indices.add(Integer.toString(index));
        }
        return String.join(",", indices);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void throwUnchecked(Throwable throwable) throws E {
        throw (E) throwable;
    }
}
