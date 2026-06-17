package com.nyx.media.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.json.NyxJson;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageViewingContractsTest {
    private final ObjectMapper json = NyxJson.newMapper();

    @Test
    void imageTransformRequestDefaultsToContainFitWithOptionalBounds() {
        ImageTransformRequest request = new ImageTransformRequest();

        assertNull(request.width());
        assertNull(request.height());
        assertNull(request.maxWidth());
        assertNull(request.maxHeight());
        assertNull(request.quality());
        assertEquals(ImageTransformFit.CONTAIN, request.fit());
    }

    @Test
    void imageTransformContractsSerializeExplicitTransformState() throws IOException {
        ImageTransformRequest request = new ImageTransformRequest(1600, 900, 1920, 1080, 82, ImageTransformFit.COVER);
        ImageTransformResult result = new ImageTransformResult(1280, 720, "image/jpeg", 82, ImageTransformFit.COVER, true, true);

        String encodedRequest = json.writeValueAsString(request);
        String encodedResult = json.writeValueAsString(result);

        assertTrue(encodedRequest.contains("\"width\":1600"));
        assertTrue(encodedRequest.contains("\"maxWidth\":1920"));
        assertTrue(encodedRequest.contains("\"quality\":82"));
        assertTrue(encodedRequest.contains("\"fit\":\"COVER\""));
        assertTrue(encodedResult.contains("\"mimeType\":\"image/jpeg\""));
        assertTrue(encodedResult.contains("\"privacyStripped\":true"));
    }

    @Test
    void imageViewingMetadataRemainsDecodableWhenNewFieldsAreOmitted() throws IOException {
        ImageViewingMetadata decoded = json.readValue("{}", ImageViewingMetadata.class);

        assertEquals(ImageTransformFit.CONTAIN, decoded.defaultTransform().fit());
        assertTrue(decoded.capabilities().supportedFits().contains(ImageTransformFit.COVER));
        assertTrue(decoded.capabilities().privacyStrippedByDefault());
    }

    @Test
    void legacyImageItemsDecodeWhenViewingMetadataIsAbsent() throws IOException {
        MediaItem.Image decoded = json.readValue(
            """
            {
              "type":"image",
              "name":"vacation.jpg",
              "path":"photos/vacation.jpg",
              "size":1234,
              "mimeType":"image/jpeg",
              "width":1920,
              "height":1080,
              "takenAt":"2026-04-10T12:00:00Z",
              "thumbnails":{"300":"/api/v1/images/thumb?path=photos/vacation.jpg&size=300"}
            }
            """,
            MediaItem.Image.class
        );

        assertNull(decoded.viewing());
        assertEquals("vacation.jpg", decoded.name());
        assertEquals("photos/vacation.jpg", decoded.path());
        assertTrue(decoded.thumbnailSizes().isEmpty());
    }
}
