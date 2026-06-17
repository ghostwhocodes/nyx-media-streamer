package com.nyx.media;

import com.nyx.media.contracts.ImageViewingMetadata;
import com.nyx.media.contracts.TrickplayDiscoveryMetadata;
import com.nyx.media.contracts.VideoViewingMetadata;

public final class ImageViewingMetadataFactory {
    private ImageViewingMetadataFactory() {
    }

    public static ImageViewingMetadata buildImageViewingMetadata() {
        return new ImageViewingMetadata();
    }

    public static VideoViewingMetadata buildVideoViewingMetadata() {
        return new VideoViewingMetadata(new TrickplayDiscoveryMetadata());
    }
}
