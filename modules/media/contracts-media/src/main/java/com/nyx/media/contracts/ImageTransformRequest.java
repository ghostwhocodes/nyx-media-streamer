package com.nyx.media.contracts;

public record ImageTransformRequest(
    Integer width,
    Integer height,
    Integer maxWidth,
    Integer maxHeight,
    Integer quality,
    ImageTransformFit fit
) {
    public ImageTransformRequest() {
        this(null, null, null, null, null, ImageTransformFit.CONTAIN);
    }

    public ImageTransformRequest {
        if (fit == null) {
            fit = ImageTransformFit.CONTAIN;
        }
    }
}
