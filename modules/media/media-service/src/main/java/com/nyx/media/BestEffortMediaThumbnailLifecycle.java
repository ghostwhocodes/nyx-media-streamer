package com.nyx.media;

import com.nyx.media.contracts.MediaObject;
import com.nyx.media.contracts.MediaThumbnailKind;
import com.nyx.media.contracts.MediaThumbnailReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BestEffortMediaThumbnailLifecycle implements MediaThumbnailLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(BestEffortMediaThumbnailLifecycle.class);

    private final MediaThumbnailPersistence persistence;

    public BestEffortMediaThumbnailLifecycle() {
        this(null);
    }

    public BestEffortMediaThumbnailLifecycle(MediaThumbnailPersistence persistence) {
        this.persistence = persistence;
    }

    @Override
    public void bootstrapPrimaryThumbnail(MediaObject mediaObject) {
        if (persistence == null) {
            return;
        }
        try {
            persistence.ensurePlaceholder(mediaObject);
        } catch (Exception exception) {
            LOG.warn(
                "Failed to bootstrap primary thumbnail for media object {}: {}",
                mediaObject.objectId(),
                messageFor(exception)
            );
        }
    }

    @Override
    public MediaThumbnailReference primaryThumbnailReference(String objectId, String url) {
        if (persistence == null) {
            return null;
        }
        try {
            return persistence.primaryThumbnailReference(objectId, url);
        } catch (Exception exception) {
            LOG.warn(
                "Failed to load primary thumbnail reference for media object {}: {}",
                objectId,
                messageFor(exception)
            );
            return null;
        }
    }

    @Override
    public void markPrimaryThumbnailReady(
        String objectId,
        String storageKey,
        Integer width,
        Integer height,
        String format,
        Long sourcePositionMillis
    ) {
        if (persistence == null) {
            return;
        }
        try {
            persistence.markReady(
                objectId,
                storageKey,
                width,
                height,
                format,
                MediaThumbnailKind.THUMBNAIL,
                sourcePositionMillis
            );
        } catch (Exception exception) {
            LOG.warn(
                "Failed to mark primary thumbnail ready for media object {} and key {}: {}",
                objectId,
                storageKey,
                messageFor(exception)
            );
        }
    }

    @Override
    public void markPrimaryThumbnailFailed(String objectId, String storageKey, String format) {
        if (persistence == null) {
            return;
        }
        try {
            persistence.markFailed(objectId, storageKey, MediaThumbnailKind.THUMBNAIL, format);
        } catch (Exception exception) {
            LOG.warn(
                "Failed to mark primary thumbnail failed for media object {} and key {}: {}",
                objectId,
                storageKey,
                messageFor(exception)
            );
        }
    }

    private static String messageFor(Exception exception) {
        if (exception.getMessage() != null && !exception.getMessage().isBlank()) {
            return exception.getMessage();
        }
        return exception.getClass().getSimpleName();
    }
}
