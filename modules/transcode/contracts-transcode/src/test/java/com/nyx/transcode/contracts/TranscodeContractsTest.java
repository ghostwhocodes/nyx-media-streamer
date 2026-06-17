package com.nyx.transcode.contracts;

import org.junit.jupiter.api.Test;

import static com.nyx.stream.representation.contracts.StreamRepresentation.HLS_DASH_FMP4;
import static com.nyx.transcode.contracts.ContractFactories.buildTranscodeSpecKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TranscodeContractsTest {
    @Test
    void remuxSpecKeyIgnoresHwaccelPreference() {
        String auto = buildTranscodeSpecKey(
            "/media/movie.mkv",
            null,
            "h264_fast",
            HLS_DASH_FMP4,
            TranscodeExecutionMode.REMUX,
            null,
            "extract",
            null,
            "all",
            "auto"
        );

        String disabled = buildTranscodeSpecKey(
            "/media/movie.mkv",
            null,
            "h264_fast",
            HLS_DASH_FMP4,
            TranscodeExecutionMode.REMUX,
            null,
            "extract",
            null,
            "all",
            "none"
        );

        assertEquals(auto, disabled);
    }

    @Test
    void audioTranscodeSpecKeyIgnoresHwaccelPreference() {
        String auto = buildTranscodeSpecKey(
            "/media/movie.mkv",
            null,
            "aac_stereo",
            HLS_DASH_FMP4,
            TranscodeExecutionMode.AUDIO_TRANSCODE,
            null,
            "extract",
            null,
            "all",
            "auto"
        );

        String disabled = buildTranscodeSpecKey(
            "/media/movie.mkv",
            null,
            "aac_stereo",
            HLS_DASH_FMP4,
            TranscodeExecutionMode.AUDIO_TRANSCODE,
            null,
            "extract",
            null,
            "all",
            "none"
        );

        assertEquals(auto, disabled);
    }

    @Test
    void videoTranscodeSpecKeyStillDistinguishesHwaccelPreference() {
        String auto = buildTranscodeSpecKey(
            "/media/movie.mkv",
            null,
            "h264_fast",
            HLS_DASH_FMP4,
            TranscodeExecutionMode.VIDEO_TRANSCODE,
            null,
            "extract",
            null,
            "all",
            "auto"
        );

        String disabled = buildTranscodeSpecKey(
            "/media/movie.mkv",
            null,
            "h264_fast",
            HLS_DASH_FMP4,
            TranscodeExecutionMode.VIDEO_TRANSCODE,
            null,
            "extract",
            null,
            "all",
            "none"
        );

        assertNotEquals(auto, disabled);
    }
}
