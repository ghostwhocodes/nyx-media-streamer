package com.nyx.media;

import com.nyx.media.contracts.MediaObject;
import com.nyx.media.contracts.MediaThumbnail;
import com.nyx.media.contracts.MediaThumbnailKind;
import com.nyx.media.contracts.MediaThumbnailReference;

public interface MediaThumbnailPersistence {
    MediaThumbnail ensurePlaceholder(MediaObject mediaObject);

    default MediaThumbnailReference primaryThumbnailReference(String objectId) {
        return primaryThumbnailReference(objectId, null);
    }

    MediaThumbnailReference primaryThumbnailReference(String objectId, String url);

    default MediaThumbnail markReady(String objectId, String storageKey) {
        return markReady(objectId, storageKey, null, null, "jpeg", MediaThumbnailKind.THUMBNAIL, null);
    }

    default MediaThumbnail markReady(String objectId, String storageKey, Integer width) {
        return markReady(objectId, storageKey, width, null, "jpeg", MediaThumbnailKind.THUMBNAIL, null);
    }

    default MediaThumbnail markReady(String objectId, String storageKey, Integer width, Integer height) {
        return markReady(objectId, storageKey, width, height, "jpeg", MediaThumbnailKind.THUMBNAIL, null);
    }

    MediaThumbnail markReady(
        String objectId,
        String storageKey,
        Integer width,
        Integer height,
        String format,
        MediaThumbnailKind kind,
        Long sourcePositionMillis
    );

    default MediaThumbnail markFailed(String objectId, String storageKey) {
        return markFailed(objectId, storageKey, MediaThumbnailKind.THUMBNAIL, "jpeg");
    }

    MediaThumbnail markFailed(
        String objectId,
        String storageKey,
        MediaThumbnailKind kind,
        String format
    );
}
