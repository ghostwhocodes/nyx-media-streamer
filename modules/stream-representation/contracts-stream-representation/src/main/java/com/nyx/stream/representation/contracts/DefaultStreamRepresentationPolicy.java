package com.nyx.stream.representation.contracts;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public enum DefaultStreamRepresentationPolicy implements StreamRepresentationPolicy {
    INSTANCE;

    private static final String MPEG_TS_REPRESENTATION_MESSAGE = "Adaptive HLS MPEG-TS output is not supported";

    private static final Map<StreamRepresentation, StreamRepresentationStorageToken> STORAGE_TOKENS =
        storageTokens();
    private static final Map<String, StreamRepresentation> STORAGE_LOOKUP = storageLookup();
    private static final Map<StreamRepresentation, String> CANONICAL_EXTERNAL_NAMES = canonicalExternalNames();
    private static final Map<String, StreamRepresentation> EXTERNAL_ALIASES = externalAliases();
    private static final Map<StreamRepresentation, StreamRepresentationTraits> TRAITS = traits();
    private static final Map<StreamRepresentation, Set<StreamRepresentationConstraint>> CONSTRAINTS = constraints();

    @Override
    public StreamRepresentation normalizeExternalName(String externalName) {
        String normalized = normalize(externalName);
        if (normalized.isEmpty()) {
            return StreamRepresentation.HLS_DASH_FMP4;
        }
        StreamRepresentation representation = EXTERNAL_ALIASES.get(normalized);
        if (representation == null) {
            return StreamRepresentation.HLS_DASH_FMP4;
        }
        return representation;
    }

    @Override
    public StreamRepresentation fromStorageToken(String storageToken) {
        String normalized = normalizeStorageToken(storageToken);
        StreamRepresentation representation = STORAGE_LOOKUP.get(normalized);
        if (representation == null) {
            throw new IllegalArgumentException("Unknown stream representation storage token: " + storageToken);
        }
        return representation;
    }

    @Override
    public StreamRepresentationStorageToken storageToken(StreamRepresentation representation) {
        StreamRepresentationStorageToken token = STORAGE_TOKENS.get(requireRepresentation(representation));
        if (token == null) {
            throw new IllegalArgumentException("No stream representation storage token for " + representation);
        }
        return token;
    }

    @Override
    public String canonicalExternalName(StreamRepresentation representation) {
        String externalName = CANONICAL_EXTERNAL_NAMES.get(requireRepresentation(representation));
        if (externalName == null) {
            throw new IllegalArgumentException("No canonical external name for " + representation);
        }
        return externalName;
    }

    @Override
    public StreamRepresentationTraits traits(StreamRepresentation representation) {
        StreamRepresentationTraits traits = TRAITS.get(requireRepresentation(representation));
        if (traits == null) {
            throw new IllegalArgumentException("No stream representation traits for " + representation);
        }
        return traits;
    }

    @Override
    public Set<StreamArtifactKind> artifactKinds(StreamRepresentation representation) {
        return traits(representation).artifactKinds();
    }

    @Override
    public Set<StreamRepresentationConstraint> constraints(StreamRepresentation representation) {
        return CONSTRAINTS.getOrDefault(requireRepresentation(representation), Set.of());
    }

    @Override
    public StreamRepresentation defaultFor(StreamingProtocol protocol, String container, boolean adaptive) {
        if (protocol == null) {
            return null;
        }
        return switch (protocol) {
            case FILE -> StreamRepresentation.DIRECT_FILE;
            case DASH -> StreamRepresentation.DASH_FMP4;
            case HLS -> isMpegTs(container) ? StreamRepresentation.HLS_MPEG_TS : StreamRepresentation.HLS_FMP4;
        };
    }

    private static StreamRepresentation requireRepresentation(StreamRepresentation representation) {
        if (representation == null) {
            throw new IllegalArgumentException("stream representation is required");
        }
        return representation;
    }

    private static boolean isMpegTs(String container) {
        String normalized = normalize(container).replace("_", "");
        return "mpegts".equals(normalized) || "ts".equals(normalized);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replace("-", "_");
    }

    private static String normalizeStorageToken(String value) {
        return normalize(value);
    }

    private static Map<StreamRepresentation, StreamRepresentationStorageToken> storageTokens() {
        Map<StreamRepresentation, StreamRepresentationStorageToken> tokens =
            new EnumMap<>(StreamRepresentation.class);
        tokens.put(StreamRepresentation.DIRECT_FILE, new StreamRepresentationStorageToken("sr_direct_file"));
        tokens.put(StreamRepresentation.HLS_FMP4, new StreamRepresentationStorageToken("sr_hls_fmp4"));
        tokens.put(StreamRepresentation.HLS_MPEG_TS, new StreamRepresentationStorageToken("sr_hls_mpeg_ts"));
        tokens.put(StreamRepresentation.DASH_FMP4, new StreamRepresentationStorageToken("sr_dash_fmp4"));
        tokens.put(StreamRepresentation.HLS_DASH_FMP4, new StreamRepresentationStorageToken("sr_hls_dash_fmp4"));
        tokens.put(StreamRepresentation.CMAF, new StreamRepresentationStorageToken("sr_cmaf"));
        return Map.copyOf(tokens);
    }

    private static Map<String, StreamRepresentation> storageLookup() {
        Map<String, StreamRepresentation> lookup = new java.util.HashMap<>();
        STORAGE_TOKENS.forEach((representation, token) -> lookup.put(token.value(), representation));
        return Map.copyOf(lookup);
    }

    private static Map<StreamRepresentation, String> canonicalExternalNames() {
        Map<StreamRepresentation, String> names = new EnumMap<>(StreamRepresentation.class);
        names.put(StreamRepresentation.DIRECT_FILE, "file");
        names.put(StreamRepresentation.HLS_FMP4, "hls");
        names.put(StreamRepresentation.HLS_MPEG_TS, "hls_ts");
        names.put(StreamRepresentation.DASH_FMP4, "dash");
        names.put(StreamRepresentation.HLS_DASH_FMP4, "both");
        names.put(StreamRepresentation.CMAF, "cmaf");
        return Map.copyOf(names);
    }

    private static Map<String, StreamRepresentation> externalAliases() {
        Map<String, StreamRepresentation> aliases = new java.util.HashMap<>();
        aliases.put("file", StreamRepresentation.DIRECT_FILE);
        aliases.put("direct", StreamRepresentation.DIRECT_FILE);
        aliases.put("direct_file", StreamRepresentation.DIRECT_FILE);
        aliases.put("hls", StreamRepresentation.HLS_FMP4);
        aliases.put("hls_fmp4", StreamRepresentation.HLS_FMP4);
        aliases.put("hls_ts", StreamRepresentation.HLS_MPEG_TS);
        aliases.put("hls_mpegts", StreamRepresentation.HLS_MPEG_TS);
        aliases.put("hls_mpeg_ts", StreamRepresentation.HLS_MPEG_TS);
        aliases.put("dash", StreamRepresentation.DASH_FMP4);
        aliases.put("dash_fmp4", StreamRepresentation.DASH_FMP4);
        aliases.put("both", StreamRepresentation.HLS_DASH_FMP4);
        aliases.put("hls_dash", StreamRepresentation.HLS_DASH_FMP4);
        aliases.put("hls_dash_fmp4", StreamRepresentation.HLS_DASH_FMP4);
        aliases.put("cmaf", StreamRepresentation.CMAF);
        return Map.copyOf(aliases);
    }

    private static Map<StreamRepresentation, StreamRepresentationTraits> traits() {
        Map<StreamRepresentation, StreamRepresentationTraits> traits = new EnumMap<>(StreamRepresentation.class);
        traits.put(
            StreamRepresentation.DIRECT_FILE,
            new StreamRepresentationTraits(
                Set.of(StreamingProtocol.FILE),
                StreamingProtocol.FILE,
                StreamPackaging.NONE,
                StreamSegmentContainer.NONE,
                false,
                StreamCommandOutput.DIRECT_FILE,
                Set.of()
            )
        );
        traits.put(
            StreamRepresentation.HLS_FMP4,
            new StreamRepresentationTraits(
                Set.of(StreamingProtocol.HLS),
                StreamingProtocol.HLS,
                StreamPackaging.FMP4,
                StreamSegmentContainer.FMP4,
                true,
                StreamCommandOutput.HLS_FMP4,
                Set.of(
                    StreamArtifactKind.HLS_MASTER_PLAYLIST,
                    StreamArtifactKind.HLS_MEDIA_PLAYLIST,
                    StreamArtifactKind.FMP4_INIT_SEGMENT,
                    StreamArtifactKind.FMP4_MEDIA_SEGMENT
                )
            )
        );
        traits.put(
            StreamRepresentation.HLS_MPEG_TS,
            new StreamRepresentationTraits(
                Set.of(StreamingProtocol.HLS),
                StreamingProtocol.HLS,
                StreamPackaging.MPEG_TS,
                StreamSegmentContainer.MPEG_TS,
                true,
                StreamCommandOutput.HLS_MPEG_TS,
                Set.of(
                    StreamArtifactKind.HLS_MASTER_PLAYLIST,
                    StreamArtifactKind.HLS_MEDIA_PLAYLIST,
                    StreamArtifactKind.MPEG_TS_SEGMENT
                )
            )
        );
        traits.put(
            StreamRepresentation.DASH_FMP4,
            new StreamRepresentationTraits(
                Set.of(StreamingProtocol.DASH),
                StreamingProtocol.DASH,
                StreamPackaging.FMP4,
                StreamSegmentContainer.FMP4,
                true,
                StreamCommandOutput.DASH_FMP4,
                Set.of(
                    StreamArtifactKind.DASH_MANIFEST,
                    StreamArtifactKind.FMP4_INIT_SEGMENT,
                    StreamArtifactKind.FMP4_MEDIA_SEGMENT
                )
            )
        );
        traits.put(
            StreamRepresentation.HLS_DASH_FMP4,
            new StreamRepresentationTraits(
                Set.of(StreamingProtocol.HLS, StreamingProtocol.DASH),
                StreamingProtocol.HLS,
                StreamPackaging.FMP4,
                StreamSegmentContainer.FMP4,
                true,
                StreamCommandOutput.HLS_DASH_FMP4,
                Set.of(
                    StreamArtifactKind.DASH_MANIFEST,
                    StreamArtifactKind.HLS_MASTER_PLAYLIST,
                    StreamArtifactKind.HLS_MEDIA_PLAYLIST,
                    StreamArtifactKind.FMP4_INIT_SEGMENT,
                    StreamArtifactKind.FMP4_MEDIA_SEGMENT
                )
            )
        );
        traits.put(
            StreamRepresentation.CMAF,
            new StreamRepresentationTraits(
                Set.of(StreamingProtocol.HLS, StreamingProtocol.DASH),
                StreamingProtocol.HLS,
                StreamPackaging.FMP4,
                StreamSegmentContainer.FMP4,
                true,
                StreamCommandOutput.CMAF,
                Set.of(
                    StreamArtifactKind.DASH_MANIFEST,
                    StreamArtifactKind.HLS_MASTER_PLAYLIST,
                    StreamArtifactKind.HLS_MEDIA_PLAYLIST,
                    StreamArtifactKind.FMP4_INIT_SEGMENT,
                    StreamArtifactKind.FMP4_MEDIA_SEGMENT
                )
            )
        );
        return Map.copyOf(traits);
    }

    private static Map<StreamRepresentation, Set<StreamRepresentationConstraint>> constraints() {
        Map<StreamRepresentation, Set<StreamRepresentationConstraint>> constraints =
            new EnumMap<>(StreamRepresentation.class);
        constraints.put(
            StreamRepresentation.HLS_MPEG_TS,
            Set.of(StreamRepresentationConstraint.maxVideoRepresentations(1, MPEG_TS_REPRESENTATION_MESSAGE))
        );
        return Map.copyOf(constraints);
    }
}
