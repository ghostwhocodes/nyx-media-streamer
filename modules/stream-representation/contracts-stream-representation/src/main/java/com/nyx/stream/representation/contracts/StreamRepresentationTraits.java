package com.nyx.stream.representation.contracts;

import java.util.LinkedHashSet;
import java.util.Set;

public record StreamRepresentationTraits(
    Set<StreamingProtocol> protocols,
    StreamingProtocol primaryProtocol,
    StreamPackaging packaging,
    StreamSegmentContainer segmentContainer,
    boolean adaptive,
    StreamCommandOutput commandOutput,
    Set<StreamArtifactKind> artifactKinds
) {
    public StreamRepresentationTraits {
        protocols = immutableSet(protocols);
        artifactKinds = immutableSet(artifactKinds);
        if (primaryProtocol == null) {
            throw new IllegalArgumentException("primaryProtocol is required");
        }
        if (!protocols.contains(primaryProtocol)) {
            throw new IllegalArgumentException("primaryProtocol must be present in protocols");
        }
        packaging = packaging == null ? StreamPackaging.NONE : packaging;
        segmentContainer = segmentContainer == null ? StreamSegmentContainer.NONE : segmentContainer;
        if (commandOutput == null) {
            throw new IllegalArgumentException("commandOutput is required");
        }
    }

    public boolean supportsProtocol(StreamingProtocol protocol) {
        return protocols.contains(protocol);
    }

    public boolean supportsArtifact(StreamArtifactKind artifactKind) {
        return artifactKinds.contains(artifactKind);
    }

    public String segmentFileExtension() {
        return segmentContainer.fileExtension();
    }

    private static <T> Set<T> immutableSet(Set<T> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(new LinkedHashSet<>(values));
    }
}
