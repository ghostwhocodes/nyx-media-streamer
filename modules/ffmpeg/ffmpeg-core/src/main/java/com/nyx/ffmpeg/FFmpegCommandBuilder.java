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
import java.util.List;

public final class FFmpegCommandBuilder {
    private Path inputPath;
    private Path outputPath;
    private VideoCodec videoCodec;
    private AudioCodec audioCodec;
    private OutputFormat outputFormat = OutputFormat.Both;
    private SubtitleMode subtitleMode = SubtitleMode.Extract;
    private AudioTrackMode audioTrackMode = AudioTrackMode.All;
    private HwAccel hwAccel = HwAccel.None;
    private Double seekTimeSecs;
    private List<RepresentationConfig> representations = List.of();
    private SegmentDuration segmentDuration = SegmentDuration.ADAPTIVE;
    private long sourceDurationUs;
    private double sourceFps = 24.0;

    public Path getInputPath() {
        return inputPath;
    }

    public void setInputPath(Path inputPath) {
        this.inputPath = inputPath;
    }

    public Path getOutputPath() {
        return outputPath;
    }

    public void setOutputPath(Path outputPath) {
        this.outputPath = outputPath;
    }

    public VideoCodec getVideoCodec() {
        return videoCodec;
    }

    public void setVideoCodec(VideoCodec videoCodec) {
        this.videoCodec = videoCodec;
    }

    public AudioCodec getAudioCodec() {
        return audioCodec;
    }

    public void setAudioCodec(AudioCodec audioCodec) {
        this.audioCodec = audioCodec;
    }

    public OutputFormat getOutputFormat() {
        return outputFormat;
    }

    public void setOutputFormat(OutputFormat outputFormat) {
        this.outputFormat = outputFormat;
    }

    public SubtitleMode getSubtitleMode() {
        return subtitleMode;
    }

    public void setSubtitleMode(SubtitleMode subtitleMode) {
        this.subtitleMode = subtitleMode;
    }

    public AudioTrackMode getAudioTrackMode() {
        return audioTrackMode;
    }

    public void setAudioTrackMode(AudioTrackMode audioTrackMode) {
        this.audioTrackMode = audioTrackMode;
    }

    public HwAccel getHwAccel() {
        return hwAccel;
    }

    public void setHwAccel(HwAccel hwAccel) {
        this.hwAccel = hwAccel;
    }

    public Double getSeekTimeSecs() {
        return seekTimeSecs;
    }

    public void setSeekTimeSecs(Double seekTimeSecs) {
        this.seekTimeSecs = seekTimeSecs;
    }

    public List<RepresentationConfig> getRepresentations() {
        return representations;
    }

    public void setRepresentations(List<RepresentationConfig> representations) {
        this.representations = representations;
    }

    public SegmentDuration getSegmentDuration() {
        return segmentDuration;
    }

    public void setSegmentDuration(SegmentDuration segmentDuration) {
        this.segmentDuration = segmentDuration;
    }

    public long getSourceDurationUs() {
        return sourceDurationUs;
    }

    public void setSourceDurationUs(long sourceDurationUs) {
        this.sourceDurationUs = sourceDurationUs;
    }

    public double getSourceFps() {
        return sourceFps;
    }

    public void setSourceFps(double sourceFps) {
        this.sourceFps = sourceFps;
    }

    public FFmpegCommand build() {
        return new FFmpegCommand(
            requireField(inputPath, "inputPath is required"),
            requireField(outputPath, "outputPath is required"),
            requireField(videoCodec, "videoCodec is required"),
            requireField(audioCodec, "audioCodec is required"),
            outputFormat,
            subtitleMode,
            audioTrackMode,
            hwAccel,
            seekTimeSecs,
            representations,
            segmentDuration,
            sourceDurationUs,
            sourceFps
        );
    }

    private static <T> T requireField(T value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
