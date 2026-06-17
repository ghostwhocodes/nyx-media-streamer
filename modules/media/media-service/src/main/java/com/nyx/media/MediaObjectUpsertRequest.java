package com.nyx.media;

import com.nyx.common.MediaTypes;
import com.nyx.media.contracts.MediaKind;
import com.nyx.media.contracts.MediaObjectContracts;
import com.nyx.media.contracts.MediaObjectStatus;
import java.util.Objects;

public final class MediaObjectUpsertRequest {
    private final MediaKind mediaKind;
    private final String primaryPath;
    private final String mimeType;
    private final long sizeBytes;
    private final String modifiedAt;
    private final String displayName;
    private final Long durationMillis;
    private final Integer width;
    private final Integer height;
    private final Integer channels;
    private final String takenAt;
    private final String embeddedTitle;
    private final String embeddedArtist;
    private final String embeddedAlbum;
    private final String hashAlgorithm;
    private final String contentHash;
    private final MediaObjectStatus status;

    public MediaObjectUpsertRequest(
        MediaKind mediaKind,
        String primaryPath,
        long sizeBytes,
        String modifiedAt,
        String displayName
    ) {
        this(
            mediaKind,
            primaryPath,
            MediaTypes.APPLICATION_OCTET_STREAM,
            sizeBytes,
            modifiedAt,
            displayName,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            MediaObjectContracts.MEDIA_OBJECT_HASH_ALGORITHM_NONE,
            null,
            MediaObjectStatus.ACTIVE
        );
    }

    public MediaObjectUpsertRequest(
        MediaKind mediaKind,
        String primaryPath,
        String mimeType,
        long sizeBytes,
        String modifiedAt,
        String displayName,
        Long durationMillis,
        Integer width,
        Integer height,
        Integer channels,
        String takenAt,
        String embeddedTitle,
        String embeddedArtist,
        String embeddedAlbum,
        String hashAlgorithm,
        String contentHash,
        MediaObjectStatus status
    ) {
        this.mediaKind = Objects.requireNonNull(mediaKind, "mediaKind");
        this.primaryPath = Objects.requireNonNull(primaryPath, "primaryPath");
        this.mimeType = mimeType == null ? MediaTypes.APPLICATION_OCTET_STREAM : mimeType;
        this.sizeBytes = sizeBytes;
        this.modifiedAt = Objects.requireNonNull(modifiedAt, "modifiedAt");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.durationMillis = durationMillis;
        this.width = width;
        this.height = height;
        this.channels = channels;
        this.takenAt = takenAt;
        this.embeddedTitle = embeddedTitle;
        this.embeddedArtist = embeddedArtist;
        this.embeddedAlbum = embeddedAlbum;
        this.hashAlgorithm = hashAlgorithm == null || hashAlgorithm.isBlank()
            ? MediaObjectContracts.MEDIA_OBJECT_HASH_ALGORITHM_NONE
            : hashAlgorithm;
        this.contentHash = contentHash;
        this.status = status == null ? MediaObjectStatus.ACTIVE : status;
    }

    public MediaKind getMediaKind() {
        return mediaKind;
    }

    public String getPrimaryPath() {
        return primaryPath;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getModifiedAt() {
        return modifiedAt;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Long getDurationMillis() {
        return durationMillis;
    }

    public Integer getWidth() {
        return width;
    }

    public Integer getHeight() {
        return height;
    }

    public Integer getChannels() {
        return channels;
    }

    public String getTakenAt() {
        return takenAt;
    }

    public String getEmbeddedTitle() {
        return embeddedTitle;
    }

    public String getEmbeddedArtist() {
        return embeddedArtist;
    }

    public String getEmbeddedAlbum() {
        return embeddedAlbum;
    }

    public String getHashAlgorithm() {
        return hashAlgorithm;
    }

    public String getContentHash() {
        return contentHash;
    }

    public MediaObjectStatus getStatus() {
        return status;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MediaObjectUpsertRequest that)) {
            return false;
        }
        return sizeBytes == that.sizeBytes
            && mediaKind == that.mediaKind
            && Objects.equals(primaryPath, that.primaryPath)
            && Objects.equals(mimeType, that.mimeType)
            && Objects.equals(modifiedAt, that.modifiedAt)
            && Objects.equals(displayName, that.displayName)
            && Objects.equals(durationMillis, that.durationMillis)
            && Objects.equals(width, that.width)
            && Objects.equals(height, that.height)
            && Objects.equals(channels, that.channels)
            && Objects.equals(takenAt, that.takenAt)
            && Objects.equals(embeddedTitle, that.embeddedTitle)
            && Objects.equals(embeddedArtist, that.embeddedArtist)
            && Objects.equals(embeddedAlbum, that.embeddedAlbum)
            && Objects.equals(hashAlgorithm, that.hashAlgorithm)
            && Objects.equals(contentHash, that.contentHash)
            && status == that.status;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            mediaKind,
            primaryPath,
            mimeType,
            sizeBytes,
            modifiedAt,
            displayName,
            durationMillis,
            width,
            height,
            channels,
            takenAt,
            embeddedTitle,
            embeddedArtist,
            embeddedAlbum,
            hashAlgorithm,
            contentHash,
            status
        );
    }

    @Override
    public String toString() {
        return "MediaObjectUpsertRequest{"
            + "mediaKind=" + mediaKind
            + ", primaryPath='" + primaryPath + '\''
            + ", mimeType='" + mimeType + '\''
            + ", sizeBytes=" + sizeBytes
            + ", modifiedAt='" + modifiedAt + '\''
            + ", displayName='" + displayName + '\''
            + ", durationMillis=" + durationMillis
            + ", width=" + width
            + ", height=" + height
            + ", channels=" + channels
            + ", takenAt='" + takenAt + '\''
            + ", embeddedTitle='" + embeddedTitle + '\''
            + ", embeddedArtist='" + embeddedArtist + '\''
            + ", embeddedAlbum='" + embeddedAlbum + '\''
            + ", hashAlgorithm='" + hashAlgorithm + '\''
            + ", contentHash='" + contentHash + '\''
            + ", status=" + status
            + '}';
    }
}
