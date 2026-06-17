package com.nyx.stream.representation.contracts;

public record StreamRepresentationConstraint(
    StreamRepresentationConstraintKind kind,
    int maxRepresentations,
    String violationMessage
) {
    public StreamRepresentationConstraint {
        if (kind == null) {
            throw new IllegalArgumentException("constraint kind is required");
        }
        if (maxRepresentations < 1) {
            throw new IllegalArgumentException("maxRepresentations must be positive");
        }
        if (violationMessage == null || violationMessage.isBlank()) {
            throw new IllegalArgumentException("violationMessage is required");
        }
    }

    public static StreamRepresentationConstraint maxVideoRepresentations(
        int maxRepresentations,
        String violationMessage
    ) {
        return new StreamRepresentationConstraint(
            StreamRepresentationConstraintKind.MAX_VIDEO_REPRESENTATIONS,
            maxRepresentations,
            violationMessage
        );
    }
}
