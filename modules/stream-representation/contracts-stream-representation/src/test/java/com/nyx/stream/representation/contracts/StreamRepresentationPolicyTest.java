package com.nyx.stream.representation.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class StreamRepresentationPolicyTest {
    private final StreamRepresentationPolicy policy = StreamRepresentationPolicy.defaultPolicy();

    @Test
    void normalizesCurrentExternalAliasesThroughOnePolicyMethod() {
        assertThat(policy.normalizeExternalName(null)).isEqualTo(StreamRepresentation.HLS_DASH_FMP4);
        assertThat(policy.normalizeExternalName("")).isEqualTo(StreamRepresentation.HLS_DASH_FMP4);
        assertThat(policy.normalizeExternalName("hls")).isEqualTo(StreamRepresentation.HLS_FMP4);
        assertThat(policy.normalizeExternalName("hls_fmp4")).isEqualTo(StreamRepresentation.HLS_FMP4);
        assertThat(policy.normalizeExternalName("hls_ts")).isEqualTo(StreamRepresentation.HLS_MPEG_TS);
        assertThat(policy.normalizeExternalName("hls_mpegts")).isEqualTo(StreamRepresentation.HLS_MPEG_TS);
        assertThat(policy.normalizeExternalName("hls-mpegts")).isEqualTo(StreamRepresentation.HLS_MPEG_TS);
        assertThat(policy.normalizeExternalName("hls_mpeg_ts")).isEqualTo(StreamRepresentation.HLS_MPEG_TS);
        assertThat(policy.normalizeExternalName("dash")).isEqualTo(StreamRepresentation.DASH_FMP4);
        assertThat(policy.normalizeExternalName("both")).isEqualTo(StreamRepresentation.HLS_DASH_FMP4);
        assertThat(policy.normalizeExternalName("direct-file")).isEqualTo(StreamRepresentation.DIRECT_FILE);
        assertThat(policy.normalizeExternalName("file")).isEqualTo(StreamRepresentation.DIRECT_FILE);
        assertThat(policy.normalizeExternalName("cmaf")).isEqualTo(StreamRepresentation.CMAF);
    }

    @Test
    void preservesUnknownExternalAliasFallbackAtTheIncomingSeam() {
        assertThat(policy.normalizeExternalName("mystery"))
            .isEqualTo(StreamRepresentation.HLS_DASH_FMP4);
    }

    @Test
    void mapsStableStorageTokensWithoutUsingEnumNamesOrExternalAliases() {
        for (StreamRepresentation representation : StreamRepresentation.values()) {
            StreamRepresentationStorageToken token = policy.storageToken(representation);

            assertThat(token.value()).isNotBlank();
            assertThat(token.value()).isNotEqualTo(representation.name());
            assertThat(token.value()).isNotEqualTo(policy.canonicalExternalName(representation));
            assertThat(policy.fromStorageToken(token.value())).isEqualTo(representation);
        }

        assertThat(policy.storageToken(StreamRepresentation.HLS_MPEG_TS).value()).isEqualTo("sr_hls_mpeg_ts");
        assertThat(policy.fromStorageToken("sr_hls_mpeg_ts")).isEqualTo(StreamRepresentation.HLS_MPEG_TS);
        assertThatThrownBy(() -> policy.fromStorageToken("hls_ts"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown stream representation storage token");
    }

    @Test
    void exposesLowLevelRepresentationTraitsAndCommandOutputMapping() {
        StreamRepresentationTraits direct = policy.traits(StreamRepresentation.DIRECT_FILE);
        assertThat(direct.protocols()).containsExactly(StreamingProtocol.FILE);
        assertThat(direct.packaging()).isEqualTo(StreamPackaging.NONE);
        assertThat(direct.segmentContainer()).isEqualTo(StreamSegmentContainer.NONE);
        assertThat(direct.adaptive()).isFalse();
        assertThat(direct.artifactKinds()).isEmpty();
        assertThat(direct.commandOutput()).isEqualTo(StreamCommandOutput.DIRECT_FILE);

        StreamRepresentationTraits hlsMpegTs = policy.traits(StreamRepresentation.HLS_MPEG_TS);
        assertThat(hlsMpegTs.protocols()).containsExactly(StreamingProtocol.HLS);
        assertThat(hlsMpegTs.packaging()).isEqualTo(StreamPackaging.MPEG_TS);
        assertThat(hlsMpegTs.segmentContainer()).isEqualTo(StreamSegmentContainer.MPEG_TS);
        assertThat(hlsMpegTs.segmentFileExtension()).isEqualTo("ts");
        assertThat(hlsMpegTs.commandOutput()).isEqualTo(StreamCommandOutput.HLS_MPEG_TS);

        StreamRepresentationTraits both = policy.traits(StreamRepresentation.HLS_DASH_FMP4);
        assertThat(both.protocols()).containsExactlyInAnyOrder(StreamingProtocol.HLS, StreamingProtocol.DASH);
        assertThat(both.primaryProtocol()).isEqualTo(StreamingProtocol.HLS);
        assertThat(both.packaging()).isEqualTo(StreamPackaging.FMP4);
        assertThat(both.commandOutput()).isEqualTo(StreamCommandOutput.HLS_DASH_FMP4);
    }

    @Test
    void exposesArtifactSupportAsArtifactKindSets() {
        assertThat(policy.artifactKinds(StreamRepresentation.DIRECT_FILE)).isEmpty();

        assertThat(policy.artifactKinds(StreamRepresentation.HLS_MPEG_TS))
            .containsExactlyInAnyOrder(
                StreamArtifactKind.HLS_MASTER_PLAYLIST,
                StreamArtifactKind.HLS_MEDIA_PLAYLIST,
                StreamArtifactKind.MPEG_TS_SEGMENT
            )
            .doesNotContain(StreamArtifactKind.DASH_MANIFEST, StreamArtifactKind.FMP4_INIT_SEGMENT);

        assertThat(policy.supportsArtifact(StreamRepresentation.HLS_FMP4, StreamArtifactKind.HLS_MASTER_PLAYLIST))
            .isTrue();
        assertThat(policy.supportsArtifact(StreamRepresentation.DASH_FMP4, StreamArtifactKind.HLS_MASTER_PLAYLIST))
            .isFalse();
        assertThat(policy.supportsArtifact(StreamRepresentation.HLS_DASH_FMP4, StreamArtifactKind.DASH_MANIFEST))
            .isTrue();
        assertThat(policy.supportsArtifact(StreamRepresentation.CMAF, StreamArtifactKind.HLS_MEDIA_PLAYLIST))
            .isTrue();
    }

    @Test
    void declaresHlsMpegTsSingleVideoRepresentationConstraint() {
        assertThat(policy.constraints(StreamRepresentation.HLS_FMP4)).isEmpty();

        Set<StreamRepresentationConstraint> constraints = policy.constraints(StreamRepresentation.HLS_MPEG_TS);

        assertThat(constraints).singleElement().satisfies(constraint -> {
            assertThat(constraint.kind()).isEqualTo(StreamRepresentationConstraintKind.MAX_VIDEO_REPRESENTATIONS);
            assertThat(constraint.maxRepresentations()).isEqualTo(1);
            assertThat(constraint.violationMessage()).isEqualTo("Adaptive HLS MPEG-TS output is not supported");
        });
    }

    @Test
    void resolvesDefaultRepresentationsFromProtocolAndContainerTraits() {
        assertThat(policy.defaultFor(StreamingProtocol.FILE, "mp4", false))
            .isEqualTo(StreamRepresentation.DIRECT_FILE);
        assertThat(policy.defaultFor(StreamingProtocol.HLS, null, true))
            .isEqualTo(StreamRepresentation.HLS_FMP4);
        assertThat(policy.defaultFor(StreamingProtocol.HLS, "mpeg-ts", true))
            .isEqualTo(StreamRepresentation.HLS_MPEG_TS);
        assertThat(policy.defaultFor(StreamingProtocol.HLS, "ts", true))
            .isEqualTo(StreamRepresentation.HLS_MPEG_TS);
        assertThat(policy.defaultFor(StreamingProtocol.DASH, "fmp4", true))
            .isEqualTo(StreamRepresentation.DASH_FMP4);
        assertThat(policy.defaultFor(null, null, false)).isNull();
    }
}
