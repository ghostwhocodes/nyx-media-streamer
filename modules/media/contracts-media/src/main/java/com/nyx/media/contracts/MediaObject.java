package com.nyx.media.contracts;

public record MediaObject(
    String objectId,
    MediaKind mediaKind,
    String primaryPath,
    String pathKey,
    String mimeType,
    long sizeBytes,
    String modifiedAt,
    String hashAlgorithm,
    String contentHash,
    String displayName,
    Long durationMillis,
    Integer width,
    Integer height,
    Integer channels,
    String takenAt,
    String embeddedTitle,
    String embeddedArtist,
    String embeddedAlbum,
    String discoveredAt,
    String lastSeenAt,
    MediaObjectStatus status
) {
    public MediaObject(
        String objectId,
        MediaKind mediaKind,
        String primaryPath,
        String pathKey,
        String mimeType,
        long sizeBytes,
        String modifiedAt,
        String displayName,
        String discoveredAt,
        String lastSeenAt
    ) {
        this(
            objectId,
            mediaKind,
            primaryPath,
            pathKey,
            mimeType,
            sizeBytes,
            modifiedAt,
            MediaObjectContracts.MEDIA_OBJECT_HASH_ALGORITHM_NONE,
            null,
            displayName,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            discoveredAt,
            lastSeenAt,
            MediaObjectStatus.ACTIVE
        );
    }

    public MediaObject {
        if (hashAlgorithm == null || hashAlgorithm.isBlank()) {
            hashAlgorithm = MediaObjectContracts.MEDIA_OBJECT_HASH_ALGORITHM_NONE;
        }
        if (status == null) {
            status = MediaObjectStatus.ACTIVE;
        }
    }
}
