package com.nyx.media.contracts;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = MediaItem.Folder.class, name = "folder"),
    @JsonSubTypes.Type(value = MediaItem.Video.class, name = "video"),
    @JsonSubTypes.Type(value = MediaItem.Image.class, name = "image"),
    @JsonSubTypes.Type(value = MediaItem.Music.class, name = "music")
})
public sealed interface MediaItem permits MediaItem.Folder, MediaItem.Video, MediaItem.Image, MediaItem.Music {
    String name();

    String path();

    long size();

    Long modifiedAt();

    record Folder(
        String name,
        String path,
        long size,
        Long modifiedAt
    ) implements MediaItem {
        public Folder(String name, String path) {
            this(name, path, 0L, null);
        }
    }

    record Video(
        String name,
        String path,
        long size,
        String objectId,
        MediaKind mediaKind,
        MediaThumbnailReference primaryThumbnail,
        String mimeType,
        VideoViewingMetadata viewing,
        Long modifiedAt,
        List<Integer> thumbnailSizes,
        MediaCapabilityHints capabilities
    ) implements MediaItem {
        public Video(String name, String path, long size, String mimeType) {
            this(name, path, size, null, MediaKind.VIDEO, null, mimeType, null, null, List.of(), null);
        }

        public Video(
            String name,
            String path,
            long size,
            String objectId,
            MediaKind mediaKind,
            MediaThumbnailReference primaryThumbnail,
            String mimeType,
            VideoViewingMetadata viewing,
            Long modifiedAt
        ) {
            this(name, path, size, objectId, mediaKind, primaryThumbnail, mimeType, viewing, modifiedAt, List.of(), null);
        }

        public Video {
            if (mediaKind == null) {
                mediaKind = MediaKind.VIDEO;
            }
            thumbnailSizes = ContractCollections.immutableList(thumbnailSizes);
            if (capabilities == null) {
                capabilities = new MediaCapabilityHints();
            }
        }
    }

    record Image(
        String name,
        String path,
        long size,
        String objectId,
        MediaKind mediaKind,
        MediaThumbnailReference primaryThumbnail,
        String mimeType,
        Integer width,
        Integer height,
        String takenAt,
        List<Integer> thumbnailSizes,
        ImageViewingMetadata viewing,
        Long modifiedAt,
        MediaCapabilityHints capabilities
    ) implements MediaItem {
        public Image(String name, String path, long size, String mimeType) {
            this(name, path, size, null, MediaKind.IMAGE, null, mimeType, null, null, null, List.of(), null, null, null);
        }

        public Image(
            String name,
            String path,
            long size,
            String objectId,
            MediaKind mediaKind,
            MediaThumbnailReference primaryThumbnail,
            String mimeType,
            Integer width,
            Integer height,
            String takenAt,
            List<Integer> thumbnailSizes,
            ImageViewingMetadata viewing,
            Long modifiedAt
        ) {
            this(
                name,
                path,
                size,
                objectId,
                mediaKind,
                primaryThumbnail,
                mimeType,
                width,
                height,
                takenAt,
                thumbnailSizes,
                viewing,
                modifiedAt,
                null
            );
        }

        public Image {
            if (mediaKind == null) {
                mediaKind = MediaKind.IMAGE;
            }
            thumbnailSizes = ContractCollections.immutableList(thumbnailSizes);
            if (capabilities == null) {
                capabilities = new MediaCapabilityHints();
            }
        }
    }

    record Music(
        String name,
        String path,
        long size,
        String objectId,
        MediaKind mediaKind,
        MediaThumbnailReference primaryThumbnail,
        String mimeType,
        Double duration,
        Long bitrate,
        Integer channels,
        String artist,
        String album,
        String title,
        Long modifiedAt,
        MediaCapabilityHints capabilities
    ) implements MediaItem {
        public Music(String name, String path, long size, String mimeType) {
            this(name, path, size, null, MediaKind.AUDIO, null, mimeType, null, null, null, null, null, null, null, null);
        }

        public Music(
            String name,
            String path,
            long size,
            String objectId,
            MediaKind mediaKind,
            MediaThumbnailReference primaryThumbnail,
            String mimeType,
            Double duration,
            Long bitrate,
            Integer channels,
            String artist,
            String album,
            String title,
            Long modifiedAt
        ) {
            this(
                name,
                path,
                size,
                objectId,
                mediaKind,
                primaryThumbnail,
                mimeType,
                duration,
                bitrate,
                channels,
                artist,
                album,
                title,
                modifiedAt,
                null
            );
        }

        public Music {
            if (mediaKind == null) {
                mediaKind = MediaKind.AUDIO;
            }
            if (capabilities == null) {
                capabilities = new MediaCapabilityHints();
            }
        }
    }
}
