package com.nyx.ffmpeg.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.json.NyxJson;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscodeProfileDeepTest {
    private final ObjectMapper json = NyxJson.newMapper();

    @Test
    void presetEnumsExposeExpectedValuesAndRoundTripByName() {
        Map<H264Preset, String> h264Values = Map.of(
            H264Preset.ULTRAFAST, "ultrafast",
            H264Preset.SUPERFAST, "superfast",
            H264Preset.VERYFAST, "veryfast",
            H264Preset.FASTER, "faster",
            H264Preset.FAST, "fast",
            H264Preset.MEDIUM, "medium",
            H264Preset.SLOW, "slow",
            H264Preset.SLOWER, "slower",
            H264Preset.VERYSLOW, "veryslow"
        );
        assertEquals(9, H264Preset.values().length);
        h264Values.forEach((preset, value) -> {
            assertEquals(value, preset.getValue());
            assertEquals(preset, H264Preset.valueOf(preset.name()));
        });

        Map<H264Profile, String> h264Profiles = Map.of(
            H264Profile.BASELINE, "baseline",
            H264Profile.MAIN, "main",
            H264Profile.HIGH, "high"
        );
        assertEquals(3, H264Profile.values().length);
        h264Profiles.forEach((profile, value) -> {
            assertEquals(value, profile.getValue());
            assertEquals(profile, H264Profile.valueOf(profile.name()));
        });

        Map<H265Preset, String> h265Values = Map.of(
            H265Preset.ULTRAFAST, "ultrafast",
            H265Preset.SUPERFAST, "superfast",
            H265Preset.VERYFAST, "veryfast",
            H265Preset.FASTER, "faster",
            H265Preset.FAST, "fast",
            H265Preset.MEDIUM, "medium",
            H265Preset.SLOW, "slow",
            H265Preset.SLOWER, "slower",
            H265Preset.VERYSLOW, "veryslow"
        );
        assertEquals(9, H265Preset.values().length);
        h265Values.forEach((preset, value) -> {
            assertEquals(value, preset.getValue());
            assertEquals(preset, H265Preset.valueOf(preset.name()));
        });
    }

    @Test
    void videoCodecRecordsExposeExpectedPropertiesAndEquality() {
        var h264 = new VideoCodec.H264(H264Preset.FAST, 23, H264Profile.HIGH, 5_000_000L, 10_000_000L);
        assertEquals(H264Preset.FAST, h264.preset());
        assertEquals(23, h264.crf());
        assertEquals(H264Profile.HIGH, h264.profile());
        assertEquals(5_000_000L, h264.maxRate());
        assertEquals(10_000_000L, h264.bufSize());
        assertEquals(new VideoCodec.H264(H264Preset.FAST, 23, H264Profile.HIGH), new VideoCodec.H264(H264Preset.FAST, 23, H264Profile.HIGH));

        var h265 = new VideoCodec.H265(H265Preset.SLOW, 22);
        assertEquals(H265Preset.SLOW, h265.preset());
        assertEquals(22, h265.crf());

        var av1 = new VideoCodec.AV1Svt(6, 30);
        assertEquals(6, av1.preset());
        assertEquals(30, av1.crf());

        List<VideoCodec> codecs = List.of(h264, h265, av1);
        assertEquals(3, codecs.size());
        assertInstanceOf(VideoCodec.H264.class, codecs.get(0));
        assertInstanceOf(VideoCodec.H265.class, codecs.get(1));
        assertInstanceOf(VideoCodec.AV1Svt.class, codecs.get(2));
    }

    @Test
    void audioCodecRecordsExposeExpectedProperties() {
        var aac = new AudioCodec.AAC(128_000, 2);
        assertEquals(128_000, aac.bitrate());
        assertEquals(2, aac.channels());

        var aacDefault = new AudioCodec.AAC(192_000);
        assertNull(aacDefault.channels());

        var opus = new AudioCodec.Opus(128_000, 2);
        assertEquals(128_000, opus.bitrate());
        assertEquals(2, opus.channels());

        var opusDefault = new AudioCodec.Opus(64_000);
        assertNull(opusDefault.channels());

        List<AudioCodec> codecs = List.of(aac, opus);
        assertInstanceOf(AudioCodec.AAC.class, codecs.get(0));
        assertInstanceOf(AudioCodec.Opus.class, codecs.get(1));
    }

    @Test
    void singletonSealedTypesExposeExpectedInstances() {
        assertSame(OutputFormat.Dash, OutputFormat.Dash);
        assertSame(OutputFormat.Hls, OutputFormat.Hls);
        assertSame(OutputFormat.Both, OutputFormat.Both);
        assertSame(SubtitleMode.Extract, SubtitleMode.Extract);
        assertEquals(3, new SubtitleMode.BurnIn(3).trackIndex());
        assertSame(AudioTrackMode.All, AudioTrackMode.All);
        assertSame(AudioTrackMode.AllWithStereoDownmix, AudioTrackMode.AllWithStereoDownmix);
        assertEquals(2, new AudioTrackMode.Single(2).index());
        assertSame(HwAccel.Auto, HwAccel.Auto);
        assertSame(HwAccel.None, HwAccel.None);
        assertSame(HwAccel.Nvenc, HwAccel.Nvenc);
        assertSame(HwAccel.Qsv, HwAccel.Qsv);
        assertEquals("/dev/dri/renderD128", new HwAccel.Vaapi("/dev/dri/renderD128").device());
        assertEquals(new HwAccel.Vaapi("/dev/dri/renderD128"), new HwAccel.Vaapi("/dev/dri/renderD128"));
        assertFalse(new HwAccel.Vaapi("/dev/dri/renderD128").equals(new HwAccel.Vaapi("/dev/dri/renderD129")));
    }

    @Test
    void representationConfigSerializesRoundTrip() throws Exception {
        RepresentationConfig config = new RepresentationConfig(1280, 720, 3000);
        String jsonText = json.writeValueAsString(config);
        RepresentationConfig decoded = json.readValue(jsonText, RepresentationConfig.class);
        assertEquals(config, decoded);
        assertTrue(jsonText.contains("\"width\":1280"));
        assertTrue(jsonText.contains("\"height\":720"));
        assertTrue(jsonText.contains("\"bitrateKbps\":3000"));

        List<RepresentationConfig> configs = List.of(
            new RepresentationConfig(854, 480, 1500),
            new RepresentationConfig(1280, 720, 3000),
            new RepresentationConfig(1920, 1080, 6000)
        );
        String listJson = json.writeValueAsString(configs);
        List<RepresentationConfig> decodedList = json.readValue(listJson, new TypeReference<List<RepresentationConfig>>() {});
        assertEquals(configs, decodedList);
    }

    @Test
    void segmentDurationConstantsAndCustomValuesBehaveAsExpected() {
        assertEquals(2, SegmentDuration.ADAPTIVE.initialSecs());
        assertEquals(6, SegmentDuration.ADAPTIVE.steadyStateSecs());
        assertEquals(4, SegmentDuration.ADAPTIVE.initialSegmentCount());

        assertEquals(4, SegmentDuration.FIXED_4S.initialSecs());
        assertEquals(4, SegmentDuration.FIXED_4S.steadyStateSecs());
        assertEquals(0, SegmentDuration.FIXED_4S.initialSegmentCount());

        SegmentDuration custom = new SegmentDuration(1, 10, 2);
        assertEquals(1, custom.initialSecs());
        assertEquals(10, custom.steadyStateSecs());
        assertEquals(2, custom.initialSegmentCount());

        assertEquals(new SegmentDuration(2, 6, 4), SegmentDuration.ADAPTIVE);
        SegmentDuration modified = new SegmentDuration(SegmentDuration.ADAPTIVE.initialSecs(), 8, SegmentDuration.ADAPTIVE.initialSegmentCount());
        assertEquals(8, modified.steadyStateSecs());
        assertEquals(2, modified.initialSecs());
    }

    @Test
    void transcodeAndAdaptiveProfilesExposeExpectedProperties() {
        TranscodeProfile profile = new TranscodeProfile(
            "test_profile",
            new VideoCodec.H264(H264Preset.FAST, 23, H264Profile.HIGH),
            new AudioCodec.AAC(128_000),
            SegmentDuration.ADAPTIVE
        );
        assertEquals("test_profile", profile.name());
        assertInstanceOf(VideoCodec.H264.class, profile.videoCodec());
        assertInstanceOf(AudioCodec.AAC.class, profile.audioCodec());
        assertEquals(SegmentDuration.ADAPTIVE, profile.segmentDuration());

        AdaptiveProfile adaptive = new AdaptiveProfile(
            "test_adaptive",
            List.of(new RepresentationConfig(854, 480, 1500), new RepresentationConfig(1280, 720, 3000)),
            new VideoCodec.H264(H264Preset.MEDIUM, 20, H264Profile.HIGH),
            new AudioCodec.AAC(128_000),
            SegmentDuration.ADAPTIVE
        );
        assertEquals("test_adaptive", adaptive.name());
        assertEquals(2, adaptive.representations().size());
    }

    @Test
    void builtInTranscodeProfilesExposeExpectedConfigurations() {
        var h264Fast = TranscodeProfiles.H264_FAST;
        assertEquals("h264_fast", h264Fast.name());
        var h264FastVideo = assertInstanceOf(VideoCodec.H264.class, h264Fast.videoCodec());
        assertEquals(H264Preset.VERYFAST, h264FastVideo.preset());
        assertEquals(23, h264FastVideo.crf());
        assertEquals(H264Profile.HIGH, h264FastVideo.profile());
        assertEquals(128_000, assertInstanceOf(AudioCodec.AAC.class, h264Fast.audioCodec()).bitrate());
        assertEquals(SegmentDuration.ADAPTIVE, h264Fast.segmentDuration());

        var h264Balanced = TranscodeProfiles.H264_BALANCED;
        assertEquals("h264_balanced", h264Balanced.name());
        assertEquals(H264Preset.MEDIUM, assertInstanceOf(VideoCodec.H264.class, h264Balanced.videoCodec()).preset());
        assertEquals(192_000, assertInstanceOf(AudioCodec.AAC.class, h264Balanced.audioCodec()).bitrate());

        var h265Quality = TranscodeProfiles.H265_QUALITY;
        assertEquals("h265_quality", h265Quality.name());
        assertEquals(H265Preset.SLOW, assertInstanceOf(VideoCodec.H265.class, h265Quality.videoCodec()).preset());

        var av1Balanced = TranscodeProfiles.AV1_BALANCED;
        assertEquals("av1_balanced", av1Balanced.name());
        assertEquals(6, assertInstanceOf(VideoCodec.AV1Svt.class, av1Balanced.videoCodec()).preset());
        assertEquals(128_000, assertInstanceOf(AudioCodec.Opus.class, av1Balanced.audioCodec()).bitrate());

        var adaptiveH264 = TranscodeProfiles.ADAPTIVE_H264;
        assertEquals("adaptive_h264", adaptiveH264.name());
        assertEquals(3, adaptiveH264.representations().size());
        assertEquals(new RepresentationConfig(854, 480, 1500), adaptiveH264.representations().get(0));
        assertEquals(new RepresentationConfig(1280, 720, 3000), adaptiveH264.representations().get(1));
        assertEquals(new RepresentationConfig(1920, 1080, 6000), adaptiveH264.representations().get(2));
        assertEquals(H264Preset.MEDIUM, assertInstanceOf(VideoCodec.H264.class, adaptiveH264.videoCodec()).preset());
        assertEquals(128_000, assertInstanceOf(AudioCodec.AAC.class, adaptiveH264.audioCodec()).bitrate());
    }

    @Test
    void findByNameAndAllNamesExposeExpectedProfileCatalog() {
        assertEquals(TranscodeProfiles.H264_FAST, TranscodeProfiles.findByName("h264_fast"));
        assertEquals(TranscodeProfiles.H264_BALANCED, TranscodeProfiles.findByName("h264_balanced"));
        assertEquals(TranscodeProfiles.H265_QUALITY, TranscodeProfiles.findByName("h265_quality"));
        assertEquals(TranscodeProfiles.AV1_BALANCED, TranscodeProfiles.findByName("av1_balanced"));
        assertEquals(TranscodeProfiles.ADAPTIVE_H264, TranscodeProfiles.findByName("adaptive_h264"));
        assertNull(TranscodeProfiles.findByName("nonexistent"));
        assertNull(TranscodeProfiles.findByName(""));
        assertNull(TranscodeProfiles.findByName("H264_FAST"));
        assertNull(TranscodeProfiles.findByName("H264_fast"));

        var names = TranscodeProfiles.allNames();
        assertTrue(names.contains("h264_fast"));
        assertTrue(names.contains("h265_quality"));
        assertTrue(names.contains("adaptive_h264"));
    }
}
