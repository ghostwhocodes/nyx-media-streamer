package com.nyx.media.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlaylistModelCoverageTest {

    @Test
    void requestModelsPreserveDefaultsAndDefensiveCopies() {
        List<String> requestedTracks = new ArrayList<>(List.of("track-1", "track-2"));
        CreatePlaylistRequest nameOnly = new CreatePlaylistRequest("Roadtrip");
        CreatePlaylistRequest described = new CreatePlaylistRequest("Roadtrip", "Summer mix");
        CreatePlaylistRequest normalized = new CreatePlaylistRequest("Roadtrip", null, requestedTracks);
        UpdatePlaylistRequest update = new UpdatePlaylistRequest();
        ReorderTracksRequest emptyReorder = new ReorderTracksRequest(null);
        ReorderTracksRequest reorder = new ReorderTracksRequest(requestedTracks);

        requestedTracks.add("track-3");

        assertEquals("Roadtrip", nameOnly.name());
        assertEquals("", nameOnly.description());
        assertTrue(nameOnly.tracks().isEmpty());

        assertEquals("Summer mix", described.description());
        assertTrue(described.tracks().isEmpty());

        assertEquals("", normalized.description());
        assertEquals(List.of("track-1", "track-2"), normalized.tracks());
        assertThrows(UnsupportedOperationException.class, () -> normalized.tracks().add("track-4"));

        assertNull(update.name());
        assertNull(update.description());
        assertNull(update.tracks());

        assertTrue(emptyReorder.trackIds().isEmpty());
        assertEquals(List.of("track-1", "track-2"), reorder.trackIds());
        assertThrows(UnsupportedOperationException.class, () -> reorder.trackIds().add("track-4"));
    }

    @Test
    void playlistModelsApplyLegacyConvenienceDefaults() {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant updatedAt = Instant.parse("2026-01-01T01:00:00Z");
        PlaylistTrack track = new PlaylistTrack("track-1", "/music/track-1.mp3", 0, createdAt);
        List<PlaylistTrack> tracks = new ArrayList<>(List.of(track));
        Playlist minimal = new Playlist("playlist-1", "Roadtrip", createdAt, updatedAt);
        Playlist described = new Playlist("playlist-2", "Sleep", "Night mix", createdAt, updatedAt);
        Playlist normalized = new Playlist("playlist-3", "Focus", null, tracks, createdAt, updatedAt);
        PlaylistSummary summary = new PlaylistSummary("playlist-1", "Roadtrip", 1, createdAt, updatedAt);
        PlaylistSummary describedSummary = new PlaylistSummary("playlist-2", "Sleep", "Night mix", 2, createdAt, updatedAt);

        tracks.add(new PlaylistTrack("track-2", "/music/track-2.mp3", 1, updatedAt));

        assertEquals("", minimal.description());
        assertTrue(minimal.tracks().isEmpty());
        assertEquals("Night mix", described.description());
        assertTrue(described.tracks().isEmpty());

        assertEquals("", normalized.description());
        assertEquals(List.of(track), normalized.tracks());
        assertEquals(createdAt, normalized.createdAt());
        assertEquals(updatedAt, normalized.updatedAt());
        assertThrows(
            UnsupportedOperationException.class,
            () -> normalized.tracks().add(new PlaylistTrack("track-3", "/music/track-3.mp3", 2, updatedAt))
        );

        assertEquals("", summary.description());
        assertEquals(1, summary.trackCount());
        assertEquals("Night mix", describedSummary.description());
        assertEquals(updatedAt, describedSummary.updatedAt());
    }
}
