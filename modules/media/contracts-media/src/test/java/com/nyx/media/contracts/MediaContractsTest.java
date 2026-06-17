package com.nyx.media.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.json.NyxJson;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaContractsTest {
    private final ObjectMapper json = NyxJson.newMapper();

    @Test
    void mediaItemVariantsSerializeThroughTheSharedContractModule() throws IOException {
        BrowseListing listing = new BrowseListing(
            List.of(
                new MediaItem.Folder("Movies", "Movies"),
                new MediaItem.Image(
                    "poster.jpg",
                    "Movies/poster.jpg",
                    1024,
                    null,
                    MediaKind.IMAGE,
                    null,
                    "image/jpeg",
                    600,
                    900,
                    null,
                    List.of(150, 300),
                    null,
                    null
                )
            ),
            2,
            1,
            50
        );

        String encoded = json.writeValueAsString(listing);

        assertTrue(encoded.contains("\"type\":\"folder\""));
        assertTrue(encoded.contains("\"type\":\"image\""));
        assertTrue(encoded.contains("\"thumbnailSizes\":[150,300]"));
    }
}
