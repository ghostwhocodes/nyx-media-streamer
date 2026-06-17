package com.nyx.media.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.json.NyxJson;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryContractsTest {
    private final ObjectMapper json = NyxJson.newMapper();

    @Test
    void libraryContractsRoundTripThroughJson() throws IOException {
        Library library = new Library(
            "library-1",
            "Movies",
            "Local movie files",
            LibraryType.MOVIE,
            List.of(
                new LibrarySourceRoot(
                    "root-1",
                    "/srv/media/movies",
                    "Movies",
                    0,
                    "2026-04-15T10:00:00Z",
                    "2026-04-15T10:00:00Z"
                )
            ),
            new LibraryScanState(
                LibraryScanStatus.FAILED,
                "2026-04-15T10:10:00Z",
                null,
                "2026-04-15T10:11:00Z",
                "Missing source root"
            ),
            "2026-04-15T10:00:00Z",
            "2026-04-15T10:12:00Z"
        );
        CreateLibraryRequest createRequest = new CreateLibraryRequest(
            "Music",
            "",
            LibraryType.MUSIC,
            List.of(new LibrarySourceRootWriteRequest("/srv/media/music"))
        );
        UpdateLibraryRequest updateRequest = new UpdateLibraryRequest(
            null,
            "",
            null,
            List.of(new LibrarySourceRootWriteRequest("/srv/media/music", "Music"))
        );

        String encodedLibrary = json.writeValueAsString(library);
        String encodedCreate = json.writeValueAsString(createRequest);
        String encodedUpdate = json.writeValueAsString(updateRequest);

        Library decodedLibrary = json.readValue(encodedLibrary, Library.class);
        CreateLibraryRequest decodedCreate = json.readValue(encodedCreate, CreateLibraryRequest.class);
        UpdateLibraryRequest decodedUpdate = json.readValue(encodedUpdate, UpdateLibraryRequest.class);

        assertEquals(library, decodedLibrary);
        assertEquals(createRequest, decodedCreate);
        assertEquals(updateRequest, decodedUpdate);
        assertTrue(encodedLibrary.contains("\"scanState\""));
        assertTrue(encodedCreate.contains("\"sourceRoots\""));
    }
}
