package com.nyx.playback.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.json.NyxJson;
import com.nyx.media.contracts.MediaKind;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AudioNegotiationContractsTest {
    private final ObjectMapper json = NyxJson.newMapper();

    @Test
    void audioNegotiationRequestDefaultsRemainCompatibilityFriendly() {
        AudioNegotiationRequest request = new AudioNegotiationRequest(new MediaSourceRef("/media/example.flac"));

        assertThat(request.startPositionMillis()).isZero();
        assertThat(request.capabilities().supportedMimeTypes()).isEmpty();
        assertThat(request.capabilities().supportedContainers()).isEmpty();
        assertThat(request.capabilities().supportedAudioCodecs()).isEmpty();
        assertThat(request.capabilities().allowDirectPlay()).isTrue();
        assertThat(request.capabilities().allowTranscode()).isTrue();
        assertThat(request.output().preferredMimeTypes()).isEmpty();
        assertThat(request.output().preferredContainers()).isEmpty();
        assertThat(request.output().preferredAudioCodecs()).isEmpty();
        assertThat(request.client()).isNull();
    }

    @Test
    void nestedAudioCapabilitiesJsonKeepsAdditiveBooleanDefaults() throws Exception {
        String encoded = """
            {
              "source": { "path": "/media/example.flac" },
              "capabilities": {
                "supportedMimeTypes": ["audio/aac"],
                "supportedContainers": ["adts"],
                "supportedAudioCodecs": ["aac"]
              }
            }
            """;

        AudioNegotiationRequest request = json.readValue(encoded, AudioNegotiationRequest.class);

        assertThat(request.capabilities().supportedMimeTypes()).containsExactly("audio/aac");
        assertThat(request.capabilities().supportedContainers()).containsExactly("adts");
        assertThat(request.capabilities().supportedAudioCodecs()).containsExactly("aac");
        assertThat(request.capabilities().allowDirectPlay()).isTrue();
        assertThat(request.capabilities().allowTranscode()).isTrue();
    }

    @Test
    void audioNegotiationRequestSerializesExplicitNegotiationFields() throws Exception {
        AudioNegotiationRequest request = new AudioNegotiationRequest(
            new MediaSourceRef(
                "/media/example.flac",
                new PlaybackSourceCharacteristics(
                    "flac",
                    null,
                    null,
                    List.of(),
                    List.of(new PlaybackSourceAudioStream(0, "flac", 2, 932, 96_000, "eng", "Stereo")),
                    List.of()
                ),
                null,
                null
            ),
            30_000L,
            new AudioClientIdentity("ios-player", "Nyx iOS", "iphone-15", "Alice phone", "Nyx/1.0"),
            new AudioCapabilitySet(
                new LinkedHashSet<>(List.of("audio/aac", "audio/mpeg")),
                new LinkedHashSet<>(List.of("adts", "mp3")),
                new LinkedHashSet<>(List.of("aac", "mp3")),
                false,
                true
            ),
            new AudioConstraint(256, 2, 192, 48_000, 44_100),
            new AudioOutputPreferences(
                List.of("audio/aac", "audio/mpeg"),
                List.of("adts", "mp3"),
                List.of("aac", "mp3")
            )
        );

        String encoded = json.writeValueAsString(request);

        assertThat(encoded).contains("\"startPositionMillis\":30000");
        assertThat(encoded).contains("\"clientId\":\"ios-player\"");
        assertThat(encoded).contains("\"deviceId\":\"iphone-15\"");
        assertThat(encoded).contains("\"supportedMimeTypes\":[\"audio/aac\",\"audio/mpeg\"]");
        assertThat(encoded).contains("\"preferredAudioCodecs\":[\"aac\",\"mp3\"]");
        assertThat(encoded).contains("\"preferredBitrateKbps\":192");
        assertThat(encoded).contains("\"maxSampleRateHz\":48000");
        assertThat(encoded).contains("\"sampleRateHz\":96000");
    }

    @Test
    void audioNegotiationDecisionSerializesModeAndReasons() throws Exception {
        AudioNegotiationDecision decision = new AudioNegotiationDecision(
            AudioDeliveryMode.TRANSCODE,
            Set.of(PlaybackReason.AUDIO_CODEC_UNSUPPORTED, PlaybackReason.AUDIO_SAMPLE_RATE_TOO_HIGH),
            new AudioFormatDescriptor("flac", "flac", "audio/flac", 932, 2, 96_000),
            new AudioFormatDescriptor("adts", "aac", "audio/aac", 192, 2, 44_100)
        );

        String encoded = json.writeValueAsString(decision);

        assertThat(encoded).contains("\"mode\":\"TRANSCODE\"");
        assertThat(encoded).containsAnyOf(
            "\"reasons\":[\"AUDIO_CODEC_UNSUPPORTED\",\"AUDIO_SAMPLE_RATE_TOO_HIGH\"]",
            "\"reasons\":[\"AUDIO_SAMPLE_RATE_TOO_HIGH\",\"AUDIO_CODEC_UNSUPPORTED\"]"
        );
        assertThat(encoded).contains("\"mimeType\":\"audio/aac\"");
        assertThat(encoded).contains("\"container\":\"adts\"");
    }

    @Test
    void legacyPlaybackRequestJsonDecodesWithAdditiveAudioConstraintDefaults() throws Exception {
        String legacyJson = """
            {
              "source": { "path": "/media/example.mkv" },
              "constraints": {
                "audio": {
                  "maxBitrateKbps": 192,
                  "maxChannels": 2
                }
              }
            }
            """;

        PlaybackRequest decoded = json.readValue(legacyJson, PlaybackRequest.class);

        assertThat(decoded.constraints().audio().maxBitrateKbps()).isEqualTo(192);
        assertThat(decoded.constraints().audio().maxChannels()).isEqualTo(2);
        assertThat(decoded.constraints().audio().preferredBitrateKbps()).isNull();
        assertThat(decoded.constraints().audio().maxSampleRateHz()).isNull();
        assertThat(decoded.constraints().audio().preferredSampleRateHz()).isNull();
    }

    @Test
    void audioSessionSerializesNegotiatedLifecycleDetails() throws Exception {
        AudioSession session = new AudioSession(
            "audio-session-1",
            "object-audio-1",
            MediaKind.AUDIO,
            PlaybackSessionState.READY,
            new AudioNegotiationRequest(
                new MediaSourceRef("/media/example.flac", null, "object-audio-1", MediaKind.AUDIO),
                0L,
                null,
                new AudioCapabilitySet(),
                new AudioConstraint(192, 2, null, null, null),
                new AudioOutputPreferences()
            ),
            new AudioNegotiationDecision(
                AudioDeliveryMode.TRANSCODE,
                Set.of(PlaybackReason.AUDIO_CODEC_UNSUPPORTED),
                null,
                new AudioFormatDescriptor("adts", "aac", "audio/aac", 192, 2, null)
            ),
            new AudioSessionArtifacts(
                "/api/v1/audio/sessions/audio-session-1/content",
                "/api/v1/audio/sessions/audio-session-1/content"
            ),
            new MediaSessionTelemetry(
                "object-audio-1",
                MediaKind.AUDIO,
                MediaSessionPlaybackEvent.HEARTBEAT,
                "2026-04-10T12:05:00Z",
                60_000L,
                240_000L,
                25.0,
                null,
                "Kitchen Speaker",
                null
            ),
            null,
            null,
            "2026-04-10T12:00:00Z",
            new PlaybackSessionLifecycle(
                PlaybackLifecyclePhase.READY,
                "2026-04-10T12:00:00Z",
                "2026-04-10T12:00:00Z",
                null,
                25.0,
                true,
                null
            )
        );

        String encoded = json.writeValueAsString(session);

        assertThat(encoded).contains("\"sessionId\":\"audio-session-1\"");
        assertThat(encoded).contains("\"objectId\":\"object-audio-1\"");
        assertThat(encoded).contains("\"mediaKind\":\"AUDIO\"");
        assertThat(encoded).contains("\"mode\":\"TRANSCODE\"");
        assertThat(encoded).contains("\"contentUrl\":\"/api/v1/audio/sessions/audio-session-1/content\"");
        assertThat(encoded).contains("\"phase\":\"READY\"");
        assertThat(encoded).contains("\"maxBitrateKbps\":192");
        assertThat(encoded).contains("\"lastEvent\":\"HEARTBEAT\"");
        assertThat(encoded).contains("\"deviceName\":\"Kitchen Speaker\"");
        assertThat(encoded).contains("\"progressPercent\":25.0");
    }
}
