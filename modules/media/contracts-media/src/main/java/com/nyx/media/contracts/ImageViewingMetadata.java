package com.nyx.media.contracts;

public record ImageViewingMetadata(
    ImageTransformRequest defaultTransform,
    ImageTransformCapabilities capabilities
) {
    public ImageViewingMetadata() {
        this(new ImageTransformRequest(), new ImageTransformCapabilities());
    }

    public ImageViewingMetadata {
        if (defaultTransform == null) {
            defaultTransform = new ImageTransformRequest();
        }
        if (capabilities == null) {
            capabilities = new ImageTransformCapabilities();
        }
    }
}
