package com.nyx.stream.representation.contracts;

import java.util.Set;

public interface StreamRepresentationPolicy {
    static StreamRepresentationPolicy defaultPolicy() {
        return DefaultStreamRepresentationPolicy.INSTANCE;
    }

    StreamRepresentation normalizeExternalName(String externalName);

    StreamRepresentation fromStorageToken(String storageToken);

    StreamRepresentationStorageToken storageToken(StreamRepresentation representation);

    String canonicalExternalName(StreamRepresentation representation);

    StreamRepresentationTraits traits(StreamRepresentation representation);

    Set<StreamArtifactKind> artifactKinds(StreamRepresentation representation);

    default boolean supportsArtifact(StreamRepresentation representation, StreamArtifactKind artifactKind) {
        return artifactKinds(representation).contains(artifactKind);
    }

    Set<StreamRepresentationConstraint> constraints(StreamRepresentation representation);

    default StreamCommandOutput commandOutput(StreamRepresentation representation) {
        return traits(representation).commandOutput();
    }

    StreamRepresentation defaultFor(
        StreamingProtocol protocol,
        String container,
        boolean adaptive
    );
}
