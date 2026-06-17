package com.nyx.transcode;

import static com.nyx.transcode.contracts.PlaybackRequestMapper.toPlaybackRequest;
import static com.nyx.transcode.contracts.PlaybackRequestMapper.toTranscodeRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.nyx.playback.contracts.AudioTrackSelectionMode;
import com.nyx.playback.contracts.HardwareAccelerationPreference;
import com.nyx.playback.contracts.PlaybackDecision;
import com.nyx.playback.contracts.PlaybackMode;
import com.nyx.playback.contracts.StreamDescriptor;
import com.nyx.stream.representation.contracts.StreamRepresentation;
import com.nyx.stream.representation.contracts.StreamingProtocol;
import com.nyx.playback.contracts.SubtitleSelectionMode;
import com.nyx.transcode.contracts.TranscodeExecutionMode;
import com.nyx.transcode.contracts.TranscodeRepresentation;
import com.nyx.transcode.contracts.TranscodeRequest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlaybackRequestMapperTest {
    @Test
    void transcodeRequestMapsToPlaybackRequest() {
        TranscodeRequest request = new TranscodeRequest(
            "/media/movie.mkv",
            12.5,
            "adaptive_h264",
            StreamRepresentation.HLS_DASH_FMP4,
            List.of(new TranscodeRepresentation(1280, 720, 3000)),
            "burn",
            2,
            "1,3",
            "vaapi",
            TranscodeExecutionMode.VIDEO_TRANSCODE,
            null
        );

        var playback = toPlaybackRequest(request);

        assertEquals("/media/movie.mkv", playback.source().path());
        assertEquals(12_500L, playback.startPositionMillis());
        assertEquals(Set.of(StreamingProtocol.HLS, StreamingProtocol.DASH), playback.output().allowedProtocols());
        assertEquals(SubtitleSelectionMode.BURN_IN, playback.selection().subtitles().mode());
        assertEquals(2, playback.selection().subtitles().trackIndex());
        assertEquals(AudioTrackSelectionMode.SPECIFIC, playback.selection().audio().mode());
        assertEquals(List.of(1, 3), playback.selection().audio().trackIndices());
        assertEquals(HardwareAccelerationPreference.REQUIRED, playback.transcode().hardwareAcceleration());
    }

    @Test
    void playbackRequestRoundTripsToTranscodeRequest() {
        TranscodeRequest original = new TranscodeRequest(
            "/media/movie.mkv",
            0.0,
            "h264_fast",
            StreamRepresentation.DASH_FMP4,
            null,
            "extract",
            null,
            "default",
            "none",
            TranscodeExecutionMode.VIDEO_TRANSCODE,
            null
        );

        TranscodeRequest roundTrip = toTranscodeRequest(toPlaybackRequest(original));

        assertEquals(original.inputPath(), roundTrip.inputPath());
        assertEquals(original.profile(), roundTrip.profile());
        assertEquals(original.representation(), roundTrip.representation());
        assertEquals(original.subtitleMode(), roundTrip.subtitleMode());
        assertEquals(original.audioTracks(), roundTrip.audioTracks());
        assertEquals(original.hwaccel(), roundTrip.hwaccel());
    }

    @Test
    void mpegTsHlsFormatRoundTripsThroughGenericPackagingStrategy() {
        TranscodeRequest original = new TranscodeRequest(
            "/media/movie.mkv",
            0.0,
            "h264_fast",
            StreamRepresentation.HLS_MPEG_TS,
            null,
            "extract",
            null,
            "all",
            "auto",
            TranscodeExecutionMode.VIDEO_TRANSCODE,
            null
        );

        var playback = toPlaybackRequest(original);
        TranscodeRequest roundTrip = toTranscodeRequest(playback, new PlaybackDecision(
            PlaybackMode.VIDEO_TRANSCODE,
            new StreamDescriptor(StreamingProtocol.HLS, null, true, StreamRepresentation.HLS_MPEG_TS)
        ));

        assertEquals(StreamRepresentation.HLS_MPEG_TS, playback.output().preferredRepresentation());
        assertEquals(StreamRepresentation.HLS_MPEG_TS, roundTrip.representation());
    }

    @Test
    void decisionAwarePlaybackMappingResolvesExecutionModeAndStripsAdaptiveLadderForRemux() {
        var playbackRequest = toPlaybackRequest(new TranscodeRequest(
            "/media/movie.mkv",
            null,
            "adaptive_h264",
            StreamRepresentation.HLS_FMP4,
            List.of(new TranscodeRepresentation(1280, 720, 3000)),
            "extract",
            null,
            "all",
            "auto",
            TranscodeExecutionMode.VIDEO_TRANSCODE,
            null
        ));

        TranscodeRequest resolved = toTranscodeRequest(
            playbackRequest,
            new PlaybackDecision(
                PlaybackMode.REMUX,
                new StreamDescriptor(StreamingProtocol.HLS, "fmp4", true)
            )
        );

        assertEquals(TranscodeExecutionMode.REMUX, resolved.executionMode());
        assertNull(resolved.representations());
    }
}
