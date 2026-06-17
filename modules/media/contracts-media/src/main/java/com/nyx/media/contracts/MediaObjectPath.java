package com.nyx.media.contracts;

public record MediaObjectPath(
    String objectId,
    String path,
    MediaObjectPathKind kind,
    String firstSeenAt,
    String lastSeenAt
) {
    public MediaObjectPath(String objectId, String path, String firstSeenAt, String lastSeenAt) {
        this(objectId, path, MediaObjectPathKind.PRIMARY, firstSeenAt, lastSeenAt);
    }

    public MediaObjectPath {
        if (kind == null) {
            kind = MediaObjectPathKind.PRIMARY;
        }
    }
}
