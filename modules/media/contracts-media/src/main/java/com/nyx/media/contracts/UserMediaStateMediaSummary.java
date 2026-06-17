package com.nyx.media.contracts;

public record UserMediaStateMediaSummary(
    String objectId,
    MediaKind mediaKind,
    String path,
    String displayName,
    String mimeType,
    long sizeBytes,
    String modifiedAt,
    Long durationMillis,
    Integer width,
    Integer height,
    Integer channels,
    String takenAt,
    String embeddedTitle,
    String embeddedArtist,
    String embeddedAlbum,
    MediaThumbnailReference primaryThumbnail,
    MediaObjectStatus status
) {
    public UserMediaStateMediaSummary {
        if (status == null) {
            status = MediaObjectStatus.ACTIVE;
        }
    }
}
