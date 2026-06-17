package com.nyx.media.contracts;

public record MediaThumbnailReference(
    String thumbnailId,
    MediaThumbnailKind kind,
    MediaThumbnailStatus status,
    String url,
    Integer width,
    Integer height,
    String format
) {
    public MediaThumbnailReference(
        String thumbnailId,
        MediaThumbnailKind kind,
        MediaThumbnailStatus status
    ) {
        this(thumbnailId, kind, status, null, null, null, null);
    }
}
