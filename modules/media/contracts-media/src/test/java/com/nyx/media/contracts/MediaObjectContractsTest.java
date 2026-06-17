package com.nyx.media.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.json.NyxJson;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaObjectContractsTest {
    private final ObjectMapper json = NyxJson.newMapper();

    @Test
    void mediaObjectDefaultsRemainAdditiveWhenOptionalFieldsAreOmitted() throws IOException {
        MediaObject decoded = json.readValue(
            """
            {
              "objectId":"obj-1",
              "mediaKind":"VIDEO",
              "primaryPath":"/srv/media/movie.mp4",
              "pathKey":"/srv/media/movie.mp4",
              "mimeType":"video/mp4",
              "sizeBytes":4096,
              "modifiedAt":"2026-04-10T12:00:00Z",
              "displayName":"movie.mp4",
              "discoveredAt":"2026-04-10T12:00:00Z",
              "lastSeenAt":"2026-04-10T12:00:00Z"
            }
            """,
            MediaObject.class
        );

        assertEquals(MediaObjectContracts.MEDIA_OBJECT_HASH_ALGORITHM_NONE, decoded.hashAlgorithm());
        assertNull(decoded.contentHash());
        assertNull(decoded.durationMillis());
        assertNull(decoded.width());
        assertEquals(MediaObjectStatus.ACTIVE, decoded.status());
    }

    @Test
    void mediaObjectPathDefaultsToPrimaryKindWhenLegacyPayloadOmitsIt() throws IOException {
        MediaObjectPath decoded = json.readValue(
            """
            {
              "objectId":"obj-1",
              "path":"/srv/media/movie.mp4",
              "firstSeenAt":"2026-04-10T12:00:00Z",
              "lastSeenAt":"2026-04-10T12:05:00Z"
            }
            """,
            MediaObjectPath.class
        );

        assertEquals(MediaObjectPathKind.PRIMARY, decoded.kind());
        assertEquals("/srv/media/movie.mp4", decoded.path());
    }

    @Test
    void thumbnailAndCorrelationPayloadContractsSerializeLowLevelIdentityState() throws IOException {
        MediaThumbnail thumbnail = new MediaThumbnail(
            "thumb-1",
            "obj-1",
            MediaThumbnailKind.THUMBNAIL,
            320,
            180,
            "jpeg",
            "thumbs/obj-1/320.jpg",
            15_000L,
            true,
            MediaThumbnailStatus.READY,
            "2026-04-10T12:00:00Z",
            "2026-04-10T12:00:01Z"
        );
        MediaObjectCorrelationPayload payload = new MediaObjectCorrelationPayload(
            "corr-1",
            json.createObjectNode()
                .put("origin", "import")
                .put("confidence", "reserved"),
            "2026-04-10T12:00:00Z",
            "2026-04-10T12:00:01Z"
        );

        String encodedThumbnail = json.writeValueAsString(thumbnail);
        String encodedPayload = json.writeValueAsString(payload);

        assertTrue(encodedThumbnail.contains("\"kind\":\"THUMBNAIL\""));
        assertTrue(encodedThumbnail.contains("\"status\":\"READY\""));
        assertTrue(encodedPayload.contains("\"origin\":\"import\""));
        assertTrue(encodedPayload.contains("\"confidence\":\"reserved\""));
    }

    @Test
    void thumbnailReferenceRemainsAdditiveForFileDerivedResponses() throws IOException {
        MediaItem.Image decoded = json.readValue(
            """
            {
              "type":"image",
              "name":"poster.jpg",
              "path":"movies/poster.jpg",
              "size":1024,
              "mimeType":"image/jpeg"
            }
            """,
            MediaItem.Image.class
        );
        String encoded = json.writeValueAsString(
            new MediaThumbnailReference(
                "thumb-1",
                MediaThumbnailKind.PLACEHOLDER,
                MediaThumbnailStatus.PENDING,
                "/api/v1/images/thumb?path=movies/poster.jpg&size=150",
                null,
                null,
                "jpeg"
            )
        );

        assertNull(decoded.primaryThumbnail());
        assertTrue(encoded.contains("\"status\":\"PENDING\""));
        assertTrue(encoded.contains("\"url\":\"/api/v1/images/thumb?path=movies/poster.jpg&size=150\""));
    }
}
