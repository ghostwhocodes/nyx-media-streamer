package com.nyx.media;

import com.nyx.media.contracts.ImageTransformRequest;
import java.util.Objects;

public final class ImageTransformRequestSupport {
    private ImageTransformRequestSupport() {
    }

    public static ImageTransformRequest normalized(ImageTransformRequest request) {
        Objects.requireNonNull(request, "request");
        return new ImageTransformRequest(
            requirePositive(request.width(), "width"),
            requirePositive(request.height(), "height"),
            requirePositive(request.maxWidth(), "maxWidth"),
            requirePositive(request.maxHeight(), "maxHeight"),
            requireQuality(request.quality()),
            request.fit()
        );
    }

    public static boolean hasResizeConstraints(ImageTransformRequest request) {
        Objects.requireNonNull(request, "request");
        return request.width() != null
            || request.height() != null
            || request.maxWidth() != null
            || request.maxHeight() != null;
    }

    private static Integer requirePositive(Integer value, String name) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than 0");
        }
        return value;
    }

    private static Integer requireQuality(Integer value) {
        if (value != null && (value < 1 || value > 100)) {
            throw new IllegalArgumentException("quality must be between 1 and 100");
        }
        return value;
    }
}
