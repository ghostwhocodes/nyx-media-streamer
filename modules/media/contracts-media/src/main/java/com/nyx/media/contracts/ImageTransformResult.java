package com.nyx.media.contracts;

public record ImageTransformResult(
    Integer width,
    Integer height,
    String mimeType,
    Integer quality,
    ImageTransformFit fit,
    Boolean cacheable,
    Boolean privacyStripped
) {
    public ImageTransformResult() {
        this(null, null, null, null, ImageTransformFit.CONTAIN, true, true);
    }

    public ImageTransformResult {
        if (fit == null) {
            fit = ImageTransformFit.CONTAIN;
        }
        if (cacheable == null) {
            cacheable = true;
        }
        if (privacyStripped == null) {
            privacyStripped = true;
        }
    }
}
