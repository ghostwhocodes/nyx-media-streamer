package com.nyx.media.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.json.NyxJson;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryItemContractsTest {
    private final ObjectMapper json = NyxJson.newMapper();

    @Test
    void libraryItemContractsRoundTripThroughJson() throws IOException {
        LibraryItemListing listing = new LibraryItemListing(
            List.of(
                new LibraryItem(
                    "item-1",
                    "library-1",
                    "parent-1",
                    "entry-1",
                    "object-1",
                    LibraryItemType.EPISODE,
                    "Pilot",
                    0,
                    MediaKind.VIDEO,
                    "/srv/media/shows/show-a/pilot.mp4",
                    null,
                    "Pilot",
                    "Show A - 001",
                    "Episode overview",
                    List.of("Drama", "Sci-Fi"),
                    List.of(
                        new LibraryArtwork(
                            LibraryArtworkKind.THUMBNAIL,
                            LibraryArtworkSource.FOLDER,
                            "/srv/media/shows/show-a/thumb.jpg",
                            "image/jpeg"
                        )
                    ),
                    List.of(new LibraryCollectionSummary("collection-1", "Favorites")),
                    1,
                    1,
                    null,
                    "2026-04-15T12:00:00Z",
                    "2026-04-15T12:00:00Z"
                ),
                new LibraryItem(
                    "item-2",
                    "library-1",
                    null,
                    null,
                    null,
                    LibraryItemType.UNMATCHED,
                    "Unknown Clip",
                    0,
                    null,
                    null,
                    "season or episode pattern missing",
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    null,
                    null,
                    null,
                    "2026-04-15T12:00:00Z",
                    "2026-04-15T12:00:00Z"
                )
            ),
            2
        );

        String encoded = json.writeValueAsString(listing);
        LibraryItemListing decoded = json.readValue(encoded, LibraryItemListing.class);

        assertEquals(listing, decoded);
        assertTrue(encoded.contains("\"unmatchedReason\""));
        assertTrue(encoded.contains("\"childCount\""));
        assertTrue(encoded.contains("\"artwork\""));
        assertTrue(encoded.contains("\"collections\""));
    }

    @Test
    void collectionAndWriteRequestContractsRoundTripThroughJson() throws IOException {
        LibraryCollection collection = new LibraryCollection(
            "collection-1",
            "library-1",
            "Weekend Movies",
            "Weekend",
            List.of("item-1", "item-2"),
            null,
            "2026-04-15T12:00:00Z",
            "2026-04-15T12:00:00Z"
        );
        ReplaceLibraryItemMetadataRequest metadataRequest =
            new ReplaceLibraryItemMetadataRequest("Manual Title", "Manual Sort", "Manual overview", List.of("Favorite"));
        ReplaceLibraryItemArtworkRequest artworkRequest = new ReplaceLibraryItemArtworkRequest(
            "/srv/media/movies/example/poster.jpg",
            "/srv/media/movies/example/fanart.jpg",
            "/srv/media/movies/example/thumb.jpg"
        );
        LibraryItemUserStateListing stateListing = new LibraryItemUserStateListing(
            List.of(
                new LibraryItemUserStateEntry(
                    new LibraryItem(
                        "item-1",
                        "library-1",
                        LibraryItemType.MOVIE,
                        "Movie",
                        "2026-04-15T12:00:00Z",
                        "2026-04-15T12:00:00Z"
                    ),
                    new LibraryItemUserState(
                        false,
                        true,
                        42_000L,
                        0,
                        null,
                        null,
                        1,
                        0,
                        1,
                        1,
                        null,
                        null
                    )
                )
            ),
            1,
            1,
            50
        );

        assertEquals(collection, json.readValue(json.writeValueAsString(collection), LibraryCollection.class));
        assertEquals(
            metadataRequest,
            json.readValue(json.writeValueAsString(metadataRequest), ReplaceLibraryItemMetadataRequest.class)
        );
        assertEquals(
            artworkRequest,
            json.readValue(json.writeValueAsString(artworkRequest), ReplaceLibraryItemArtworkRequest.class)
        );
        assertEquals(
            stateListing,
            json.readValue(json.writeValueAsString(stateListing), LibraryItemUserStateListing.class)
        );
    }
}
