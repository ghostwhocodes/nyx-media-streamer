package com.nyx.media;

import com.nyx.media.contracts.MediaObject;
import com.nyx.media.contracts.MediaThumbnailReference;

public interface MediaThumbnailLifecycle {
    void bootstrapPrimaryThumbnail(MediaObject mediaObject);

    default MediaThumbnailReference primaryThumbnailReference(String objectId) {
        return primaryThumbnailReference(objectId, null);
    }

    MediaThumbnailReference primaryThumbnailReference(String objectId, String url);

    default void markPrimaryThumbnailReady(String objectId, String storageKey) {
        markPrimaryThumbnailReady(objectId, storageKey, null, null, "jpeg", null);
    }

    default void markPrimaryThumbnailReady(String objectId, String storageKey, Integer width) {
        markPrimaryThumbnailReady(objectId, storageKey, width, null, "jpeg", null);
    }

    default void markPrimaryThumbnailReady(String objectId, String storageKey, Integer width, Integer height) {
        markPrimaryThumbnailReady(objectId, storageKey, width, height, "jpeg", null);
    }

    void markPrimaryThumbnailReady(
        String objectId,
        String storageKey,
        Integer width,
        Integer height,
        String format,
        Long sourcePositionMillis
    );

    default void markPrimaryThumbnailFailed(String objectId, String storageKey) {
        markPrimaryThumbnailFailed(objectId, storageKey, "jpeg");
    }

    void markPrimaryThumbnailFailed(
        String objectId,
        String storageKey,
        String format
    );
}
