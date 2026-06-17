package com.nyx.media.contracts;

public record MediaThumbnail(
    String thumbnailId,
    String objectId,
    MediaThumbnailKind kind,
    Integer width,
    Integer height,
    String format,
    String storageKey,
    Long sourcePositionMillis,
    Boolean isPrimary,
    MediaThumbnailStatus status,
    String createdAt,
    String updatedAt
) {
    public MediaThumbnail(
        String thumbnailId,
        String objectId,
        MediaThumbnailKind kind,
        String format,
        String storageKey,
        String createdAt,
        String updatedAt
    ) {
        this(thumbnailId, objectId, kind, null, null, format, storageKey, null, false, MediaThumbnailStatus.PENDING, createdAt, updatedAt);
    }

    public MediaThumbnail {
        if (isPrimary == null) {
            isPrimary = false;
        }
        if (status == null) {
            status = MediaThumbnailStatus.PENDING;
        }
    }
}
