package com.nyx.media;

import com.nyx.playback.contracts.AudioClientIdentity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyAudioNegotiationRequestFactoryTest {
    @Test
    void blankAcceptHeaderKeepsDefaultCompatibilityRequest() {
        var request = LegacyAudioNegotiationRequestFactory.fromFileRequest("/media/example.flac", null);
        assertThat(request.source().path()).isEqualTo("/media/example.flac");
        assertThat(request.capabilities().supportedMimeTypes()).isEmpty();
        assertThat(request.capabilities().supportedContainers()).isEmpty();
        assertThat(request.capabilities().supportedAudioCodecs()).isEmpty();
        assertThat(request.output().preferredMimeTypes()).isEmpty();
        assertThat(request.output().preferredContainers()).isEmpty();
        assertThat(request.output().preferredAudioCodecs()).isEmpty();
        assertThat(request.client()).isNull();
    }

    @Test
    void wildcardAcceptHeaderStaysUnrestrictedForDirectCompatibility() {
        var request = LegacyAudioNegotiationRequestFactory.fromFileRequest("/media/example.flac", "audio/*, audio/aac;q=0.8");
        assertThat(request.capabilities().supportedMimeTypes()).isEmpty();
        assertThat(request.output().preferredMimeTypes()).isEmpty();
    }

    @Test
    void sourceMimeInAcceptHeaderKeepsLegacyDirectCompatibilityUnrestricted() {
        var request = LegacyAudioNegotiationRequestFactory.fromFileRequest("/media/example.mp3", "audio/mpeg, audio/aac;q=0.8", 0L, null, "audio/mpeg", java.util.Set.of("audio/aac", "audio/mpeg", "audio/opus"));
        assertThat(request.capabilities().supportedMimeTypes()).isEmpty();
        assertThat(request.capabilities().supportedContainers()).isEmpty();
        assertThat(request.output().preferredMimeTypes()).isEmpty();
        assertThat(request.output().preferredContainers()).isEmpty();
    }

    @Test
    void unsupportedAcceptHeaderKeepsLegacyDirectCompatibilityUnrestricted() {
        var request = LegacyAudioNegotiationRequestFactory.fromFileRequest("/media/example.flac", "audio/mp4, audio/x-custom;q=0.8", 0L, null, "audio/flac", java.util.Set.of("audio/aac", "audio/mpeg", "audio/opus"));
        assertThat(request.capabilities().supportedMimeTypes()).isEmpty();
        assertThat(request.capabilities().supportedContainers()).isEmpty();
        assertThat(request.capabilities().supportedAudioCodecs()).isEmpty();
        assertThat(request.output().preferredMimeTypes()).isEmpty();
        assertThat(request.output().preferredContainers()).isEmpty();
        assertThat(request.output().preferredAudioCodecs()).isEmpty();
    }

    @Test
    void explicitAcceptHeaderBecomesOrderedOutputPreferencesAndCapabilities() {
        var request = LegacyAudioNegotiationRequestFactory.fromFileRequest("/media/example.flac", "audio/aac;q=0.9, audio/mpeg;q=0.8, audio/aac");
        assertThat(request.output().preferredMimeTypes()).containsExactly("audio/aac", "audio/mpeg");
        assertThat(request.output().preferredContainers()).containsExactly("adts", "mp3");
        assertThat(request.output().preferredAudioCodecs()).containsExactly("aac", "mp3");
        assertThat(request.capabilities().supportedMimeTypes()).containsExactlyInAnyOrder("audio/aac", "audio/mpeg");
        assertThat(request.capabilities().supportedContainers()).containsExactlyInAnyOrder("adts", "mp3");
        assertThat(request.capabilities().supportedAudioCodecs()).containsExactlyInAnyOrder("aac", "mp3");
    }

    @Test
    void legacyMapperPreservesStartOffsetAndClientIdentity() {
        AudioClientIdentity client = new AudioClientIdentity("android", null, "pixel", null, "Nyx Android");
        var request = LegacyAudioNegotiationRequestFactory.fromFileRequest("/media/example.flac", "audio/opus", 12_345L, client, null, java.util.Set.of("audio/aac", "audio/mpeg", "audio/opus"));
        assertThat(request.startPositionMillis()).isEqualTo(12_345L);
        assertThat(request.client()).isEqualTo(client);
        assertThat(request.output().preferredMimeTypes()).containsExactly("audio/opus");
        assertThat(request.output().preferredContainers()).containsExactly("opus");
        assertThat(request.output().preferredAudioCodecs()).containsExactly("opus");
    }
}
