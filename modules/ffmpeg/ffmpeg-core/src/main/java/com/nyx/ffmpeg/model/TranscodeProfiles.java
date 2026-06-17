package com.nyx.ffmpeg.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class TranscodeProfiles {
    public static final TranscodeProfile H264_FAST = new TranscodeProfile(
        "h264_fast",
        new VideoCodec.H264(H264Preset.VERYFAST, 23, H264Profile.HIGH),
        new AudioCodec.AAC(128_000),
        SegmentDuration.ADAPTIVE
    );

    public static final TranscodeProfile H264_BALANCED = new TranscodeProfile(
        "h264_balanced",
        new VideoCodec.H264(H264Preset.MEDIUM, 20, H264Profile.HIGH),
        new AudioCodec.AAC(192_000),
        SegmentDuration.ADAPTIVE
    );

    public static final TranscodeProfile H265_QUALITY = new TranscodeProfile(
        "h265_quality",
        new VideoCodec.H265(H265Preset.SLOW, 22),
        new AudioCodec.AAC(192_000),
        SegmentDuration.ADAPTIVE
    );

    public static final TranscodeProfile AV1_BALANCED = new TranscodeProfile(
        "av1_balanced",
        new VideoCodec.AV1Svt(6, 30),
        new AudioCodec.Opus(128_000),
        SegmentDuration.ADAPTIVE
    );

    public static final CmafProfile CMAF_H264_FAST = new CmafProfile(
        "cmaf_h264_fast",
        new VideoCodec.H264(H264Preset.VERYFAST, 23, H264Profile.HIGH),
        new AudioCodec.AAC(128_000),
        SegmentDuration.ADAPTIVE
    );

    public static final CmafProfile CMAF_H265_QUALITY = new CmafProfile(
        "cmaf_h265_quality",
        new VideoCodec.H265(H265Preset.SLOW, 22),
        new AudioCodec.AAC(192_000),
        SegmentDuration.ADAPTIVE
    );

    public static final AdaptiveProfile ADAPTIVE_H264 = new AdaptiveProfile(
        "adaptive_h264",
        java.util.List.of(
            new RepresentationConfig(854, 480, 1500),
            new RepresentationConfig(1280, 720, 3000),
            new RepresentationConfig(1920, 1080, 6000)
        ),
        new VideoCodec.H264(H264Preset.MEDIUM, 20, H264Profile.HIGH),
        new AudioCodec.AAC(128_000),
        SegmentDuration.ADAPTIVE
    );

    private static final Map<String, Profile> PROFILES_BY_NAME = Map.copyOf(new LinkedHashMap<>(Map.of(
        H264_FAST.getName(), H264_FAST,
        H264_BALANCED.getName(), H264_BALANCED,
        H265_QUALITY.getName(), H265_QUALITY,
        AV1_BALANCED.getName(), AV1_BALANCED,
        ADAPTIVE_H264.getName(), ADAPTIVE_H264,
        CMAF_H264_FAST.getName(), CMAF_H264_FAST,
        CMAF_H265_QUALITY.getName(), CMAF_H265_QUALITY
    )));

    private TranscodeProfiles() {
    }

    public static Profile findByName(String name) {
        return PROFILES_BY_NAME.get(name);
    }

    public static Set<String> allNames() {
        return PROFILES_BY_NAME.keySet();
    }
}
