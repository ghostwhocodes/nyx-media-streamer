package com.nyx.media;

import com.nyx.config.AudioConfig;
import com.nyx.playback.contracts.AudioCapabilitySet;
import com.nyx.playback.contracts.AudioConstraint;
import com.nyx.playback.contracts.AudioDeliveryMode;
import com.nyx.playback.contracts.AudioFormatDescriptor;
import com.nyx.playback.contracts.AudioNegotiationRequest;
import com.nyx.playback.contracts.MediaSourceRef;
import com.nyx.playback.contracts.PlaybackReason;
import com.nyx.playback.contracts.PlaybackSourceAudioStream;
import com.nyx.playback.contracts.PlaybackSourceCharacteristics;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LocalAudioNegotiationServiceTest {
    private final AudioTranscoder transcoder = new AudioTranscoder(
        "ffmpeg",
        null,
        null,
        new AudioConfig("256k", "128k", "192k", 60L)
    );
    private final LocalAudioNegotiationService service = new LocalAudioNegotiationService(transcoder);

    @Test
    void decideKeepsDirectPlayWhenSourceFormatIsAllowed() {
        var decision = service.decide(new AudioNegotiationRequest(
            source("/media/example.flac", "flac", "flac", 2, 900),
            0L,
            null,
            new AudioCapabilitySet(java.util.Set.of("audio/flac"), java.util.Set.of("flac"), java.util.Set.of("flac"), true, true),
            new AudioConstraint(1_000, 2, null, null, null),
            null
        ));

        assertThat(decision.mode()).isEqualTo(AudioDeliveryMode.DIRECT_PLAY);
        assertThat(decision.output().mimeType()).isEqualTo("audio/flac");
        assertThat(decision.output().container()).isEqualTo("flac");
        assertThat(decision.output().codec()).isEqualTo("flac");
        assertThat(decision.reasons()).isEmpty();
    }

    @Test
    void decideTranscodesUnsupportedSourceUsingPreferredLegacyTargetOrdering() {
        AudioNegotiationRequest compatibilityRequest = LegacyAudioNegotiationRequestFactory.fromFileRequest(
            "/media/example.flac",
            "audio/aac;q=0.9, audio/mpeg;q=0.8"
        );
        AudioNegotiationRequest request = new AudioNegotiationRequest(
            new MediaSourceRef(
                compatibilityRequest.source().path(),
                source(
                    compatibilityRequest.source().path(),
                    "flac",
                    "flac",
                    2,
                    932
                ).characteristics(),
                compatibilityRequest.source().objectId(),
                compatibilityRequest.source().mediaKind()
            ),
            compatibilityRequest.startPositionMillis(),
            compatibilityRequest.client(),
            compatibilityRequest.capabilities(),
            compatibilityRequest.constraints(),
            compatibilityRequest.output()
        );

        var decision = service.decide(request);

        assertThat(decision.mode()).isEqualTo(AudioDeliveryMode.TRANSCODE);
        assertThat(decision.output().mimeType()).isEqualTo("audio/aac");
        assertThat(decision.output().container()).isEqualTo("adts");
        assertThat(decision.output().codec()).isEqualTo("aac");
        assertThat(decision.reasons()).contains(PlaybackReason.CONTAINER_UNSUPPORTED, PlaybackReason.AUDIO_CODEC_UNSUPPORTED, PlaybackReason.CLIENT_CAPABILITY_LIMIT);
    }

    @Test
    void decideTranscodesWhenSourceExceedsBitrateAndChannelCeilings() {
        var decision = service.decide(new AudioNegotiationRequest(
            source("/media/live.flac", "flac", "flac", 6, 640),
            0L,
            null,
            new AudioCapabilitySet(java.util.Set.of("audio/opus"), java.util.Set.of("opus"), java.util.Set.of("opus"), true, true),
            new AudioConstraint(192, 2, 160, null, null),
            null
        ));

        assertThat(decision.mode()).isEqualTo(AudioDeliveryMode.TRANSCODE);
        assertThat(decision.output().mimeType()).isEqualTo("audio/opus");
        assertThat(decision.output().bitrateKbps()).isEqualTo(160);
        assertThat(decision.output().channels()).isEqualTo(2);
        assertThat(decision.reasons()).contains(PlaybackReason.AUDIO_BITRATE_TOO_HIGH, PlaybackReason.AUDIO_CHANNELS_TOO_HIGH);
    }

    @Test
    void decideTranscodesWhenAStartOffsetIsRequested() {
        var decision = service.decide(new AudioNegotiationRequest(
            source("/media/example.flac", "flac", "flac", 2, 900),
            30_000L,
            null,
            new AudioCapabilitySet(java.util.Set.of("audio/flac", "audio/aac"), java.util.Set.of("flac", "adts"), java.util.Set.of("flac", "aac"), true, true),
            new AudioConstraint(),
            null
        ));

        assertThat(decision.mode()).isEqualTo(AudioDeliveryMode.TRANSCODE);
        assertThat(decision.reasons()).contains(PlaybackReason.EXPLICIT_TRANSCODE_REQUEST);
    }

    @Test
    void resolvedTranscodeTargetsExposeInspectableFfmpegCommandPlanning() {
        AudioTranscoder.TranscodeTarget target = transcoder.resolveTranscodeTarget(
            new AudioFormatDescriptor("opus", "opus", "audio/opus", 160, 2, 48_000)
        );

        assertThat(target).isNotNull();
        List<String> command = transcoder.buildTranscodeCommand(Path.of("/media/example.flac"), target, 12_345L);

        assertThat(command.subList(0, 3)).containsExactly("ffmpeg", "-ss", "12.345");
        assertThat(command).contains("-c:a", "libopus", "-ac", "2", "-ar", "48000", "-b:a", "160k", "-f", "opus");
    }

    private MediaSourceRef source(String path, String container, String codec, int channels, int bitrateKbps) {
        return new MediaSourceRef(
            path,
            new PlaybackSourceCharacteristics(
                container,
                null,
                null,
                List.of(),
                List.of(new PlaybackSourceAudioStream(0, codec, channels, bitrateKbps, null, null, null)),
                List.of()
            ),
            null,
            null
        );
    }
}
