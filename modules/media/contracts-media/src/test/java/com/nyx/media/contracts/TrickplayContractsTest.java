package com.nyx.media.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.json.NyxJson;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrickplayContractsTest {
    private final ObjectMapper json = NyxJson.newMapper();

    @Test
    void trickplayRequestDefaultsRemainAdditive() throws IOException {
        TrickplayRequest decoded = json.readValue("{}", TrickplayRequest.class);

        assertEquals(Set.of(TrickplayAssetKind.STORYBOARD_SHEET, TrickplayAssetKind.PREVIEW_STRIP), decoded.assetKinds());
        assertNull(decoded.intervalMillis());
        assertNull(decoded.thumbnailWidth());
        assertNull(decoded.thumbnailHeight());
        assertNull(decoded.tileLayout().columns());
        assertNull(decoded.tileLayout().rows());
    }

    @Test
    void manifestSerializationPreservesGeometryAndTimelineMetadata() throws IOException {
        TrickplayManifest manifest = new TrickplayManifest(
            540_000L,
            new TrickplayRequest(
                null,
                10_000L,
                320,
                180,
                new TrickplayTileLayoutRequest(4, 4)
            ),
            10_000L,
            List.of(
                new TrickplayAsset(
                    TrickplayAssetKind.STORYBOARD_SHEET,
                    "/api/v1/videos/trickplay/sheets/0.jpg",
                    "image/jpeg",
                    new ImageDimensions(1280, 720),
                    new ImageDimensions(320, 180),
                    10_000L,
                    0L,
                    150_000L,
                    16,
                    new TrickplayTileLayout(4, 4),
                    true
                ),
                new TrickplayAsset(
                    TrickplayAssetKind.PREVIEW_STRIP,
                    "/api/v1/videos/trickplay/strips/0.jpg",
                    "image/jpeg",
                    new ImageDimensions(1920, 180),
                    new ImageDimensions(320, 180),
                    10_000L,
                    0L,
                    50_000L,
                    6,
                    new TrickplayTileLayout(6, 1),
                    true
                )
            ),
            List.of(
                new TrickplayTimelineEntry(
                    0L,
                    TrickplayAssetKind.STORYBOARD_SHEET,
                    0,
                    0,
                    0
                ),
                new TrickplayTimelineEntry(
                    10_000L,
                    TrickplayAssetKind.STORYBOARD_SHEET,
                    0,
                    1,
                    0
                )
            ),
            true
        );

        String encoded = json.writeValueAsString(manifest);

        assertTrue(encoded.contains("\"kind\":\"STORYBOARD_SHEET\""));
        assertTrue(encoded.contains("\"kind\":\"PREVIEW_STRIP\""));
        assertTrue(encoded.contains("\"intervalMillis\":10000"));
        assertTrue(encoded.contains("\"tileLayout\":{\"columns\":4,\"rows\":4}"));
        assertTrue(encoded.contains("\"assetIndex\":0"));
    }

    @Test
    void legacyVideoItemsDecodeWhenViewingMetadataIsAbsent() throws IOException {
        MediaItem.Video decoded = json.readValue(
            """
            {
              "type":"video",
              "name":"movie.mp4",
              "path":"Movies/movie.mp4",
              "size":4096,
              "mimeType":"video/mp4",
              "modifiedAt":1712745600000
            }
            """,
            MediaItem.Video.class
        );

        assertEquals("movie.mp4", decoded.name());
        assertNull(decoded.viewing());
    }

    @Test
    void videoViewingMetadataIgnoresLegacyRouteDiscoveryFields() throws IOException {
        VideoViewingMetadata decoded = json.readValue(
            """
            {
              "trickplay": {
                "manifestUrl": "/api/v1/videos/trickplay?path=Movies/movie.mp4"
              }
            }
            """,
            VideoViewingMetadata.class
        );

        assertNotNull(decoded.trickplay());
        assertTrue(decoded.trickplay().defaultRequest().assetKinds().contains(TrickplayAssetKind.STORYBOARD_SHEET));
        assertTrue(decoded.trickplay().cacheableByDefault());
    }
}
