package com.nyx.media.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.json.NyxJson;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserMediaStateContractsTest {
    private final ObjectMapper json = NyxJson.newMapper();

    @Test
    void userMediaStateContractsSerializeObjectKeyedStateAndListings() throws IOException {
        UserMediaState state = new UserMediaState(
            "alice",
            "object-1",
            45_000L,
            false,
            null,
            true,
            8,
            2,
            "2026-04-10T12:00:00Z",
            "2026-04-10T12:05:00Z"
        );
        UserMediaStateListing listing = new UserMediaStateListing(
            List.of(
                new UserMediaStateEntry(
                    new UserMediaStateMediaSummary(
                        "object-1",
                        MediaKind.VIDEO,
                        "movies/example.mp4",
                        "example.mp4",
                        "video/mp4",
                        1_024,
                        "2026-04-10T11:59:00Z",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new MediaThumbnailReference(
                            "thumb-1",
                            MediaThumbnailKind.THUMBNAIL,
                            MediaThumbnailStatus.READY,
                            "/api/v1/images/thumb?path=movies/example.mp4&size=150",
                            null,
                            null,
                            null
                        ),
                        MediaObjectStatus.ACTIVE
                    ),
                    state
                )
            ),
            1,
            1,
            50
        );

        String encodedState = json.writeValueAsString(state);
        String encodedListing = json.writeValueAsString(listing);
        UserMediaState decodedState = json.readValue(encodedState, UserMediaState.class);

        assertTrue(encodedState.contains("\"objectId\":\"object-1\""));
        assertTrue(encodedState.contains("\"favorite\":true"));
        assertTrue(encodedListing.contains("\"mediaKind\":\"VIDEO\""));
        assertTrue(encodedListing.contains("\"path\":\"movies/example.mp4\""));
        assertEquals("alice", decodedState.userId());
        assertEquals(45_000L, decodedState.resumePositionMillis());
        assertEquals(8, decodedState.rating());
    }
}
