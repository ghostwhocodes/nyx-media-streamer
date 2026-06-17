package com.nyx.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.media.contracts.ImageTransformFit;
import com.nyx.media.contracts.ImageTransformRequest;
import org.junit.jupiter.api.Test;

class ImageTransformRequestSupportTest {
    @Test
    void normalizedKeepsValidTransformRequestStable() {
        ImageTransformRequest request = new ImageTransformRequest(
            1280,
            null,
            null,
            720,
            85,
            ImageTransformFit.COVER
        );

        ImageTransformRequest normalized = ImageTransformRequestSupport.normalized(request);

        assertEquals(request, normalized);
        assertTrue(ImageTransformRequestSupport.hasResizeConstraints(normalized));
    }

    @Test
    void hasResizeConstraintsIsFalseWhenRequestOnlyChangesQuality() {
        ImageTransformRequest request = new ImageTransformRequest(null, null, null, null, 75, ImageTransformFit.CONTAIN);

        assertFalse(ImageTransformRequestSupport.hasResizeConstraints(request));
    }

    @Test
    void normalizedRejectsNonPositiveDimensions() {
        ImageTransformRequest request = new ImageTransformRequest(0, null, null, null, null, ImageTransformFit.CONTAIN);

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> ImageTransformRequestSupport.normalized(request)
        );

        assertEquals("width must be greater than 0", error.getMessage());
    }

    @Test
    void normalizedRejectsQualityOutsideJpegRange() {
        ImageTransformRequest request = new ImageTransformRequest(null, null, null, null, 101, ImageTransformFit.CONTAIN);

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> ImageTransformRequestSupport.normalized(request)
        );

        assertEquals("quality must be between 1 and 100", error.getMessage());
    }
}
