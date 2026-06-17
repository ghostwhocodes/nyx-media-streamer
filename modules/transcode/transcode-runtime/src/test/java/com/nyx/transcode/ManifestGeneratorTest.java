package com.nyx.transcode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.ffmpeg.model.SegmentDuration;
import com.nyx.transcode.contracts.TranscodeRepresentation;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ManifestGeneratorTest {
    private final ManifestGenerator generator = new ManifestGenerator();
    private final List<SegmentInfo> sampleSegments = IntStream
        .rangeClosed(0, 4)
        .mapToObj(index -> segmentInfo("segment_%03d.m4s".formatted(index), "video", index < 2 ? 2.0 : 6.0, index))
        .toList();

    @Test
    void mpdDynamicModeDuringTranscoding() {
        String mpd = generateDashMpd(sampleSegments, false, 300.0);

        assertTrue(mpd.contains("type=\"dynamic\""));
        assertTrue(mpd.contains("minimumUpdatePeriod=\"PT2S\""));
        assertFalse(mpd.contains("type=\"static\""));
        assertTrue(mpd.contains("segment_000.m4s"));
        assertTrue(mpd.contains("segment_004.m4s"));
    }

    @Test
    void mpdStaticModeWhenComplete() {
        String mpd = generateDashMpd(sampleSegments, true, 300.0);

        assertTrue(mpd.contains("type=\"static\""));
        assertTrue(mpd.contains("mediaPresentationDuration="));
        assertFalse(mpd.contains("minimumUpdatePeriod="));
    }

    @Test
    void mpdMultiRepresentationHasAllResolutions() {
        List<TranscodeRepresentation> representations = List.of(
            new TranscodeRepresentation(854, 480, 1500),
            new TranscodeRepresentation(1280, 720, 3000)
        );
        List<SegmentInfo> segments = List.of(
            new SegmentInfo("seg_480_001.m4s", "854x480", 4.0, 0),
            new SegmentInfo("seg_720_001.m4s", "1280x720", 4.0, 0)
        );

        String mpd = generateDashMpd(
            segments,
            List.of(),
            List.of(),
            true,
            60.0,
            SegmentDuration.ADAPTIVE,
            representations,
            "",
            128_000
        );

        assertTrue(mpd.contains("width=\"854\""));
        assertTrue(mpd.contains("width=\"1280\""));
        assertTrue(mpd.contains("id=\"854x480\""));
        assertTrue(mpd.contains("id=\"1280x720\""));
    }

    @Test
    void hlsMasterPlaylistWithVariants() {
        List<TranscodeRepresentation> representations = List.of(
            new TranscodeRepresentation(854, 480, 1500),
            new TranscodeRepresentation(1280, 720, 3000)
        );

        String playlist = generateHlsMaster(
            representations.stream().map(rep -> variant(rep.width(), rep.height(), rep.bitrateKbps())).toList(),
            List.of(),
            List.of(),
            "test-job"
        );

        assertTrue(playlist.contains("#EXTM3U"));
        assertTrue(playlist.contains("BANDWIDTH=1500000"));
        assertTrue(playlist.contains("RESOLUTION=854x480"));
        assertTrue(playlist.contains("854x480.m3u8"));
        assertTrue(playlist.contains("BANDWIDTH=3000000"));
        assertTrue(playlist.contains("1280x720.m3u8"));
    }

    @Test
    void hlsMediaPlaylistDuringTranscodingHasNoEndList() {
        String playlist = generateHlsMediaPlaylist(sampleSegments, false, 6);

        assertTrue(playlist.contains("#EXTM3U"));
        assertTrue(playlist.contains("#EXT-X-TARGETDURATION:6"));
        assertTrue(playlist.contains("#EXTINF:"));
        assertTrue(playlist.contains("segment_000.m4s"));
        assertFalse(playlist.contains("#EXT-X-ENDLIST"));
    }

    @Test
    void hlsMediaPlaylistWhenCompleteHasEndList() {
        String playlist = generateHlsMediaPlaylist(sampleSegments, true, 6);
        assertTrue(playlist.contains("#EXT-X-ENDLIST"));
    }

    @Test
    void mpdWithAudioAndSubtitleTracks() {
        List<AudioTrackInfo> audioTracks = List.of(audioTrack(0, "eng", "English 5.1", 6, "aac", true, 128));
        List<SubtitleTrackInfo> subtitleTracks = List.of(subtitleTrack(0, "eng", "English"));

        String mpd = generateDashMpd(sampleSegments, audioTracks, subtitleTracks, true, 60.0);

        assertTrue(mpd.contains("lang=\"eng\""));
        assertTrue(mpd.contains("label=\"English 5.1\""));
        assertTrue(mpd.contains("id=\"audio_0\""));
        assertTrue(mpd.contains("id=\"audio_0_stereo\""));
        assertTrue(mpd.contains("mimeType=\"text/vtt\""));
        assertTrue(mpd.contains("label=\"English\""));
    }

    @Test
    void hlsMasterWithAudioAndSubtitleMediaEntries() {
        List<AudioTrackInfo> audioTracks = List.of(new AudioTrackInfo(0, "eng", "English", 2, "aac", false, 128));
        List<SubtitleTrackInfo> subtitleTracks = List.of(subtitleTrack(0, "eng", "English"));

        String playlist = generateHlsMaster(
            List.of(variant(1920, 1080, 5000, "audio", "subs")),
            audioTracks,
            subtitleTracks,
            "test-job"
        );

        assertTrue(playlist.contains("TYPE=AUDIO"));
        assertTrue(playlist.contains("NAME=\"English\""));
        assertTrue(playlist.contains("TYPE=SUBTITLES"));
    }

    @Test
    void mpdAudioRepresentationUsesTrackBitrateKbps() {
        List<AudioTrackInfo> audioTracks = List.of(audioTrack(0, "eng", "English", 2, "aac", false, 192));

        String mpd = generateDashMpd(sampleSegments, audioTracks, List.of(), true, 60.0);

        assertTrue(mpd.contains("bandwidth=\"192000\""));
        assertFalse(mpd.contains("bandwidth=\"128000\""));
    }

    @Test
    void adaptiveSegmentDurationReflectedInPlaylist() {
        List<SegmentInfo> adaptiveSegments = List.of(
            new SegmentInfo("seg_000.m4s", "video", 2.0, 0),
            new SegmentInfo("seg_001.m4s", "video", 2.0, 1),
            new SegmentInfo("seg_002.m4s", "video", 6.0, 2),
            new SegmentInfo("seg_003.m4s", "video", 6.0, 3)
        );

        String playlist = generateHlsMediaPlaylist(adaptiveSegments, false, 6);

        assertTrue(playlist.contains("#EXTINF:2.0,"));
        assertTrue(playlist.contains("#EXTINF:6.0,"));
    }

    @Test
    void hlsMasterWithoutRepresentationsUsesDefaultVideoStream() {
        String playlist = generateHlsMaster(List.of(singleVariant()), List.of(), List.of(), "test-job");

        assertTrue(playlist.contains("#EXTM3U"));
        assertTrue(playlist.contains("#EXT-X-STREAM-INF:BANDWIDTH=0"));
        assertTrue(playlist.contains("video.m3u8"));
    }

    @Test
    void hlsSingleVariantKeepsSubtitleGroupLinkage() {
        List<SubtitleTrackInfo> subtitleTracks = List.of(subtitleTrack(0, "eng", "English"));

        String playlist = generateHlsMaster(
            List.of(singleVariant(null, "subs")),
            List.of(),
            subtitleTracks,
            "test-job"
        );

        assertTrue(playlist.contains("TYPE=SUBTITLES"));
        assertTrue(playlist.contains("SUBTITLES=\"subs\""));
        assertTrue(playlist.contains("video.m3u8"));
    }

    @Test
    void hlsMasterWithAudioDownmixEntry() {
        List<AudioTrackInfo> audioTracks = List.of(audioTrack(0, "eng", "English 5.1", 6, "aac", true, 128));

        String playlist = generateHlsMaster(
            List.of(variant(1920, 1080, 5000, "audio", null)),
            audioTracks,
            List.of(),
            "test-job"
        );

        assertTrue(playlist.contains("NAME=\"English 5.1\""));
        assertTrue(playlist.contains("NAME=\"English 5.1 (Stereo)\""));
        assertTrue(playlist.contains("audio_0.m3u8"));
        assertTrue(playlist.contains("audio_0_stereo.m3u8"));
    }

    @Test
    void hlsMasterWithRepresentationsIncludesAudioAndSubtitleGroups() {
        List<AudioTrackInfo> audioTracks = List.of(new AudioTrackInfo(0, "eng", "English", 2, "aac", false, 128));
        List<SubtitleTrackInfo> subtitleTracks = List.of(subtitleTrack(0, "eng", "English"));

        String playlist = generateHlsMaster(
            List.of(variant(1920, 1080, 5000, "audio", "subs")),
            audioTracks,
            subtitleTracks,
            "test-job"
        );

        assertTrue(playlist.contains("AUDIO=\"audio\""));
        assertTrue(playlist.contains("SUBTITLES=\"subs\""));
    }

    @Test
    void mpdWithoutAudioTracksUsesDefaultAudioAdaptationSet() {
        List<SegmentInfo> segments = List.of(new SegmentInfo("audio_seg.m4s", "audio", 6.0, 0));

        String mpd = generateDashMpd(segments, true, 60.0);

        assertTrue(mpd.contains("id=\"audio\""));
        assertTrue(mpd.contains("audio_seg.m4s"));
    }

    @Test
    void mpdAudioTrackDefaultsToUndLanguageAndNumberedTitle() {
        List<AudioTrackInfo> audioTracks = List.of(new AudioTrackInfo(0, null, null, 2, "aac", false, 128));

        String mpd = generateDashMpd(List.of(), audioTracks, List.of(), true, 60.0);

        assertTrue(mpd.contains("lang=\"und\""));
        assertTrue(mpd.contains("label=\"Track 0\""));
    }

    @Test
    void mpdSubtitleTrackDefaultsToUndLanguageAndNumberedTitle() {
        List<SubtitleTrackInfo> subtitleTracks = List.of(new SubtitleTrackInfo(0, null, null));

        String mpd = generateDashMpd(List.of(), List.of(), subtitleTracks, true, 60.0);

        assertTrue(mpd.contains("lang=\"und\""));
        assertTrue(mpd.contains("label=\"Track 0\""));
        assertTrue(mpd.contains("mimeType=\"text/vtt\""));
    }

    @Test
    void hlsAudioTrackDefaultsToUndAndNumberedName() {
        List<AudioTrackInfo> audioTracks = List.of(new AudioTrackInfo(0, null, null, 2, "aac", false, 128));

        String playlist = generateHlsMaster(
            List.of(variant(1920, 1080, 5000, "audio", null)),
            audioTracks,
            List.of(),
            "test-job"
        );

        assertTrue(playlist.contains("LANGUAGE=\"und\""));
        assertTrue(playlist.contains("NAME=\"Track 0\""));
    }

    @Test
    void hlsSubtitleTrackDefaultsToUndAndNumberedName() {
        List<SubtitleTrackInfo> subtitleTracks = List.of(new SubtitleTrackInfo(0, null, null));

        String playlist = generateHlsMaster(
            List.of(variant(1920, 1080, 5000, null, "subs")),
            List.of(),
            subtitleTracks,
            "test-job"
        );

        assertTrue(playlist.contains("TYPE=SUBTITLES"));
        assertTrue(playlist.contains("LANGUAGE=\"und\""));
        assertTrue(playlist.contains("NAME=\"Track 0\""));
    }

    @Test
    void mpdAudioTrackWithHasDownmixGeneratesStereoRepresentation() {
        List<AudioTrackInfo> audioTracks = List.of(audioTrack(0, "eng", "English 5.1", 6, "aac", true, 128));

        String mpd = generateDashMpd(List.of(), audioTracks, List.of(), true, 60.0);

        assertTrue(mpd.contains("id=\"audio_0\""));
        assertTrue(mpd.contains("id=\"audio_0_stereo\""));
    }

    @Test
    void dashMpdSubtitleTrackIncludesBaseUrlWithJobEndpoint() {
        List<SubtitleTrackInfo> subtitleTracks = List.of(
            subtitleTrack(0, "eng", "English"),
            subtitleTrack(1, "fra", "French")
        );

        String mpd = generateDashMpd(
            List.of(),
            List.of(),
            subtitleTracks,
            true,
            60.0,
            SegmentDuration.ADAPTIVE,
            List.of(),
            "job-abc",
            128_000
        );

        assertTrue(mpd.contains("contentType=\"text\""));
        assertTrue(mpd.contains("<BaseURL>/api/v1/transcode/jobs/job-abc/subtitles/0</BaseURL>"));
        assertTrue(mpd.contains("<BaseURL>/api/v1/transcode/jobs/job-abc/subtitles/1</BaseURL>"));
    }

    @Test
    void hlsMasterPlaylistSubtitleUriPointsToVttEndpoint() {
        List<SubtitleTrackInfo> subtitleTracks = List.of(
            subtitleTrack(0, "eng", "English"),
            subtitleTrack(2, "jpn", "Japanese")
        );

        String playlist = generateHlsMaster(
            List.of(variant(1920, 1080, 5000, null, "subs")),
            List.of(),
            subtitleTracks,
            "job-xyz"
        );

        assertTrue(playlist.contains("TYPE=SUBTITLES"));
        assertTrue(playlist.contains("URI=\"/api/v1/transcode/jobs/job-xyz/subtitles/0.m3u8\""));
        assertTrue(playlist.contains("URI=\"/api/v1/transcode/jobs/job-xyz/subtitles/2.m3u8\""));
        assertFalse(playlist.contains("URI=\"/api/v1/transcode/jobs/job-xyz/subtitles/0\""));
        assertFalse(playlist.contains("sub_0.m3u8"));
        assertFalse(playlist.contains("sub_2.m3u8"));
    }

    @Test
    void generateHlsSubtitleMediaPlaylistReturnsValidHlsMediaPlaylist() {
        String playlist = generator.generateHlsSubtitleMediaPlaylist("job-abc", 2);
        assertTrue(playlist.contains("#EXTM3U"));
        assertTrue(playlist.contains("#EXT-X-VERSION:3"));
        assertTrue(playlist.contains("#EXT-X-TARGETDURATION:99999"));
        assertTrue(playlist.contains("#EXT-X-MEDIA-SEQUENCE:0"));
        assertTrue(playlist.contains("#EXTINF:99999.0,"));
        assertTrue(playlist.contains("/api/v1/transcode/jobs/job-abc/subtitles/2"));
        assertTrue(playlist.contains("#EXT-X-ENDLIST"));
    }

    @Test
    void generateHlsSubtitleMediaPlaylistEncodesJobIdAndTrackIndexCorrectly() {
        String playlist = generator.generateHlsSubtitleMediaPlaylist("xyz-99", 0);
        assertTrue(playlist.contains("/api/v1/transcode/jobs/xyz-99/subtitles/0"));
        assertFalse(playlist.contains("/api/v1/transcode/jobs/xyz-99/subtitles/1"));
    }

    @Test
    void generateHlsMediaPlaylistUsesDefaultInitMp4AndNoPrefix() {
        String playlist = generateHlsMediaPlaylist(sampleSegments, false, 6);
        assertTrue(playlist.contains("#EXT-X-MAP:URI=\"init.mp4\""));
        assertTrue(playlist.contains("segment_000.m4s"));
        assertFalse(playlist.contains("segments/"));
    }

    @Test
    void generateHlsMediaPlaylistWithCustomInitSegmentNameAndPrefix() {
        String playlist = generateHlsMediaPlaylist(sampleSegments, false, 6, "init_0.mp4", "segments/");
        assertTrue(playlist.contains("#EXT-X-MAP:URI=\"segments/init_0.mp4\""));
        assertTrue(playlist.contains("segments/segment_000.m4s"));
        assertTrue(playlist.contains("segments/segment_004.m4s"));
    }

    @Test
    void generateDashMpdIncludesDownmixRepresentationForAudioTracks() {
        List<SegmentInfo> segments = List.of(
            new SegmentInfo("audio_0_seg0.m4s", "audio_0", 4.0, 0),
            new SegmentInfo("audio_0_stereo_seg0.m4s", "audio_0_stereo", 4.0, 0)
        );
        List<AudioTrackInfo> audioTracks = List.of(audioTrack(0, "eng", "English 5.1", 6, "aac", true, 256));

        String mpd = generateDashMpd(
            segments,
            audioTracks,
            List.of(),
            true,
            60.0,
            SegmentDuration.ADAPTIVE,
            List.of(),
            "job1",
            128_000
        );

        assertTrue(mpd.contains("audio_0_stereo"));
        assertTrue(mpd.contains("audio_0"));
        assertTrue(mpd.contains("lang=\"eng\""));
    }

    @Test
    void generateDashMpdWithSubtitleTracksOnly() {
        List<SubtitleTrackInfo> subtitleTracks = List.of(
            subtitleTrack(0, "eng", "English"),
            subtitleTrack(1, null, null)
        );

        String mpd = generateDashMpd(
            List.of(),
            List.of(),
            subtitleTracks,
            true,
            120.0,
            SegmentDuration.ADAPTIVE,
            List.of(),
            "j1",
            128_000
        );

        assertTrue(mpd.contains("sub_0"));
        assertTrue(mpd.contains("sub_1"));
        assertTrue(mpd.contains("/api/v1/transcode/jobs/j1/subtitles/0"));
        assertTrue(mpd.contains("lang=\"und\""));
    }

    @Test
    void generateDashMpdWithRepresentationsFromSmallGaps() {
        List<TranscodeRepresentation> representations = List.of(
            new TranscodeRepresentation(1920, 1080, 5000),
            new TranscodeRepresentation(1280, 720, 3000)
        );
        List<SegmentInfo> segments = List.of(
            new SegmentInfo("seg0.m4s", "1920x1080", 4.0, 0),
            new SegmentInfo("seg0.m4s", "1280x720", 4.0, 0)
        );

        String mpd = generateDashMpd(
            segments,
            List.of(),
            List.of(),
            true,
            60.0,
            SegmentDuration.ADAPTIVE,
            representations,
            "",
            128_000
        );

        assertTrue(mpd.contains("1920x1080"));
        assertTrue(mpd.contains("1280x720"));
    }

    private String generateDashMpd(List<SegmentInfo> segments, boolean isComplete, double totalDurationSecs) {
        return generateDashMpd(
            segments,
            List.of(),
            List.of(),
            isComplete,
            totalDurationSecs,
            SegmentDuration.ADAPTIVE,
            List.of(),
            "",
            128_000
        );
    }

    private String generateDashMpd(
        List<SegmentInfo> segments,
        List<AudioTrackInfo> audioTracks,
        List<SubtitleTrackInfo> subtitleTracks,
        boolean isComplete,
        double totalDurationSecs
    ) {
        return generateDashMpd(
            segments,
            audioTracks,
            subtitleTracks,
            isComplete,
            totalDurationSecs,
            SegmentDuration.ADAPTIVE,
            List.of(),
            "",
            128_000
        );
    }

    private String generateDashMpd(
        List<SegmentInfo> segments,
        List<AudioTrackInfo> audioTracks,
        List<SubtitleTrackInfo> subtitleTracks,
        boolean isComplete,
        double totalDurationSecs,
        SegmentDuration segmentDuration,
        List<TranscodeRepresentation> representations,
        String jobId,
        int audioBandwidth
    ) {
        return generator.generateDashMpd(
            segments,
            audioTracks,
            subtitleTracks,
            isComplete,
            totalDurationSecs,
            segmentDuration,
            representations,
            jobId,
            audioBandwidth
        );
    }

    private String generateHlsMaster(
        List<HlsVariantStream> variantStreams,
        List<AudioTrackInfo> audioTracks,
        List<SubtitleTrackInfo> subtitleTracks,
        String jobId
    ) {
        return generator.generateHlsMaster(variantStreams, audioTracks, subtitleTracks, jobId);
    }

    private String generateHlsMediaPlaylist(List<SegmentInfo> segments, boolean isComplete, int targetDuration) {
        return generateHlsMediaPlaylist(segments, isComplete, targetDuration, "init.mp4", "");
    }

    private String generateHlsMediaPlaylist(
        List<SegmentInfo> segments,
        boolean isComplete,
        int targetDuration,
        String initSegmentName,
        String segmentPathPrefix
    ) {
        return generator.generateHlsMediaPlaylist(
            segments,
            isComplete,
            targetDuration,
            initSegmentName,
            segmentPathPrefix
        );
    }

    private SegmentInfo segmentInfo(String name, String representationId, double durationSecs, int index) {
        return new SegmentInfo(name, representationId, durationSecs, index);
    }

    private AudioTrackInfo audioTrack(
        int trackIndex,
        String language,
        String title,
        int channels,
        String codec,
        boolean hasDownmix,
        int bitrateKbps
    ) {
        return new AudioTrackInfo(trackIndex, language, title, channels, codec, hasDownmix, bitrateKbps);
    }

    private SubtitleTrackInfo subtitleTrack(int trackIndex, String language, String title) {
        return new SubtitleTrackInfo(trackIndex, language, title);
    }

    private HlsVariantStream variant(int width, int height, int bitrateKbps) {
        return variant(width, height, bitrateKbps, null, null);
    }

    private HlsVariantStream variant(
        int width,
        int height,
        int bitrateKbps,
        String audioGroupId,
        String subtitleGroupId
    ) {
        return new HlsVariantStream(
            bitrateKbps * 1000,
            width + "x" + height + ".m3u8",
            width,
            height,
            audioGroupId,
            subtitleGroupId
        );
    }

    private HlsVariantStream singleVariant() {
        return singleVariant(null, null);
    }

    private HlsVariantStream singleVariant(String audioGroupId, String subtitleGroupId) {
        return new HlsVariantStream(0, "video.m3u8", null, null, audioGroupId, subtitleGroupId);
    }
}
