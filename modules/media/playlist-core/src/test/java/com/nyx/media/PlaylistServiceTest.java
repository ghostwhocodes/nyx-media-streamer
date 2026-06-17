package com.nyx.media;

import com.nyx.common.DatabaseResources;
import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.common.PathSecurity;
import com.nyx.media.model.PlaylistSummary;
import com.nyx.media.model.PlaylistTrack;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaylistServiceTest {
    @TempDir
    Path tempDir;

    private final List<HikariDataSource> dataSources = new ArrayList<>();
    private PlaylistService service;
    private int dbCounter;

    @BeforeEach
    void setup() throws IOException {
        service = createService(null);
    }

    @AfterEach
    void teardown() {
        dataSources.forEach(HikariDataSource::close);
        dataSources.clear();
    }

    @Test
    void createPlaylistCreatesWithTracks() {
        var playlist = service.createPlaylist("Test", "Description", List.of("/media/a.mp3", "/media/b.flac"));

        assertNotNull(playlist.id());
        assertEquals("Test", playlist.name());
        assertEquals("Description", playlist.description());
        assertEquals(2, playlist.tracks().size());
        assertEquals("/media/a.mp3", playlist.tracks().get(0).trackPath());
        assertEquals("/media/b.flac", playlist.tracks().get(1).trackPath());
        assertEquals(0, playlist.tracks().get(0).position());
        assertEquals(1, playlist.tracks().get(1).position());
    }

    @Test
    void createPlaylistCreatesWithNoTracks() {
        var playlist = service.createPlaylist("Empty", "", List.of());

        assertEquals("Empty", playlist.name());
        assertTrue(playlist.tracks().isEmpty());
    }

    @Test
    void getPlaylistReturnsNullForNonExistentId() {
        assertNull(service.getPlaylist("nonexistent"));
    }

    @Test
    void getPlaylistReturnsPlaylistWithOrderedTracks() {
        var created = service.createPlaylist("Ordered", "", List.of("/a.mp3", "/b.mp3", "/c.mp3"));
        var fetched = service.getPlaylist(created.id());

        assertNotNull(fetched);
        assertEquals(3, fetched.tracks().size());
        assertEquals("/a.mp3", fetched.tracks().get(0).trackPath());
        assertEquals("/b.mp3", fetched.tracks().get(1).trackPath());
        assertEquals("/c.mp3", fetched.tracks().get(2).trackPath());
    }

    @Test
    void updatePlaylistUpdatesName() {
        var created = service.createPlaylist("Original", "", List.of("/a.mp3"));
        var updated = service.updatePlaylist(created.id(), "Renamed", null, null);

        assertEquals("Renamed", updated.name());
        assertEquals(1, updated.tracks().size());
    }

    @Test
    void updatePlaylistUpdatesDescription() {
        var created = service.createPlaylist("Test", "", List.of());
        var updated = service.updatePlaylist(created.id(), null, "New desc", null);

        assertEquals("New desc", updated.description());
    }

    @Test
    void updatePlaylistReplacesTracks() {
        var created = service.createPlaylist("Test", "", List.of("/a.mp3", "/b.mp3"));
        var updated = service.updatePlaylist(created.id(), null, null, List.of("/x.mp3", "/y.mp3", "/z.mp3"));

        assertEquals(3, updated.tracks().size());
        assertEquals("/x.mp3", updated.tracks().get(0).trackPath());
        assertEquals("/y.mp3", updated.tracks().get(1).trackPath());
        assertEquals("/z.mp3", updated.tracks().get(2).trackPath());
    }

    @Test
    void updatePlaylistThrowsForNonExistentId() {
        assertThrows(NyxException.class, () -> service.updatePlaylist("nonexistent", "New", null, null));
    }

    @Test
    void deletePlaylistRemovesPlaylist() {
        var created = service.createPlaylist("To Delete", "", List.of("/a.mp3"));
        service.deletePlaylist(created.id());

        assertNull(service.getPlaylist(created.id()));
    }

    @Test
    void deletePlaylistThrowsForNonExistentId() {
        assertThrows(NyxException.class, () -> service.deletePlaylist("nonexistent"));
    }

    @Test
    void listPlaylistsReturnsAllPlaylistsWithTrackCounts() {
        service.createPlaylist("Playlist 1", "", List.of("/a.mp3"));
        service.createPlaylist("Playlist 2", "", List.of("/b.mp3", "/c.mp3"));
        service.createPlaylist("Playlist 3", "", List.of());

        var list = service.listPlaylists();
        assertEquals(3, list.size());

        var byName = list.stream().collect(java.util.stream.Collectors.toMap(PlaylistSummary::name, summary -> summary));
        assertEquals(1, byName.get("Playlist 1").trackCount());
        assertEquals(2, byName.get("Playlist 2").trackCount());
        assertEquals(0, byName.get("Playlist 3").trackCount());
    }

    @Test
    void trackPositionsAreMaintainedAfterUpdate() {
        var created = service.createPlaylist("Reorder", "", List.of("/a.mp3", "/b.mp3", "/c.mp3"));
        var updated = service.updatePlaylist(created.id(), null, null, List.of("/c.mp3", "/b.mp3", "/a.mp3"));

        assertEquals("/c.mp3", updated.tracks().get(0).trackPath());
        assertEquals(0, updated.tracks().get(0).position());
        assertEquals("/b.mp3", updated.tracks().get(1).trackPath());
        assertEquals(1, updated.tracks().get(1).position());
        assertEquals("/a.mp3", updated.tracks().get(2).trackPath());
        assertEquals(2, updated.tracks().get(2).position());
    }

    @Test
    void parseM3UHandlesSimpleFormat() {
        String m3u = """
            /media/music/song1.mp3
            /media/music/song2.flac
            """.stripIndent();

        var tracks = PlaylistService.parseM3U(m3u);
        assertEquals(2, tracks.size());
        assertEquals("/media/music/song1.mp3", tracks.get(0));
        assertEquals("/media/music/song2.flac", tracks.get(1));
    }

    @Test
    void parseM3UHandlesExtendedFormat() {
        String m3u = """
            #EXTM3U
            #EXTINF:120,Song One
            /media/music/song1.mp3
            #EXTINF:180,Song Two
            /media/music/song2.flac
            """.stripIndent();

        var tracks = PlaylistService.parseM3U(m3u);
        assertEquals(2, tracks.size());
        assertEquals("/media/music/song1.mp3", tracks.get(0));
        assertEquals("/media/music/song2.flac", tracks.get(1));
    }

    @Test
    void parseM3USkipsEmptyLines() {
        String m3u = """
            /media/song1.mp3

            /media/song2.mp3

            """.stripIndent();

        var tracks = PlaylistService.parseM3U(m3u);
        assertEquals(2, tracks.size());
    }

    @Test
    void parseM3UResolvesRelativePathsWithBaseDir() {
        String m3u = """
            song1.mp3
            subdir/song2.flac
            """.stripIndent();

        var tracks = PlaylistService.parseM3U(m3u, "/media/music");
        assertEquals(2, tracks.size());
        assertEquals("/media/music/song1.mp3", tracks.get(0));
        assertEquals("/media/music/subdir/song2.flac", tracks.get(1));
    }

    @Test
    void parseM3UKeepsAbsolutePathsEvenWithBaseDir() {
        var tracks = PlaylistService.parseM3U("/media/absolute/song.mp3", "/media/music");

        assertEquals(1, tracks.size());
        assertEquals("/media/absolute/song.mp3", tracks.get(0));
    }

    @Test
    void parseM3UHandlesBaseDirWithTrailingSlashWithoutDoubleSlash() {
        var tracks = PlaylistService.parseM3U("song.mp3", "/media/music/");

        assertEquals(1, tracks.size());
        assertFalse(tracks.get(0).contains("//"), "Path should not contain double slashes");
        assertTrue(tracks.get(0).endsWith("song.mp3"));
    }

    @Test
    void parseM3UHandlesEmptyM3UContent() {
        assertTrue(PlaylistService.parseM3U("").isEmpty());
    }

    @Test
    void exportM3UProducesValidM3U() {
        var playlist = service.createPlaylist("Export", "", List.of("/media/a.mp3", "/media/b.flac"));
        var m3u = service.exportM3U(playlist.id());

        assertTrue(m3u.contains("#EXTM3U"));
        assertTrue(m3u.contains("#PLAYLIST:Export"));
        assertTrue(m3u.contains("/media/a.mp3"));
        assertTrue(m3u.contains("/media/b.flac"));
    }

    @Test
    void exportM3UThrowsForNonExistentPlaylist() {
        assertThrows(NyxException.class, () -> service.exportM3U("nonexistent"));
    }

    @Test
    void m3URoundTripImportThenExportYieldsEquivalentTracks() {
        var originalTracks = List.of("/media/x.mp3", "/media/y.flac", "/media/z.ogg");
        var playlist = service.createPlaylist("Round Trip", "", originalTracks);
        var exported = service.exportM3U(playlist.id());
        var reimported = service.importM3U(exported, "Re-imported");

        assertEquals(3, reimported.tracks().size());
        assertEquals("/media/x.mp3", reimported.tracks().get(0).trackPath());
        assertEquals("/media/y.flac", reimported.tracks().get(1).trackPath());
        assertEquals("/media/z.ogg", reimported.tracks().get(2).trackPath());
    }

    @Test
    void deletePlaylistRemovesAssociatedTracks() {
        var created = service.createPlaylist("With Tracks", "", List.of("/a.mp3", "/b.mp3", "/c.mp3"));
        service.deletePlaylist(created.id());

        assertNull(service.getPlaylist(created.id()));

        var newPlaylist = service.createPlaylist("With Tracks", "", List.of("/x.mp3"));
        assertEquals(1, newPlaylist.tracks().size());
    }

    @Test
    void multiplePlaylistsCanBeCreatedAndDeletedIndependently() {
        var first = service.createPlaylist("First", "", List.of("/a.mp3"));
        var second = service.createPlaylist("Second", "", List.of("/b.mp3"));

        service.deletePlaylist(first.id());
        assertNull(service.getPlaylist(first.id()));
        assertNotNull(service.getPlaylist(second.id()));

        service.deletePlaylist(second.id());
        assertNull(service.getPlaylist(second.id()));
    }

    @Test
    void reorderTracksUpdatesTrackPositions() {
        var playlist = service.createPlaylist("Reorder Test", "", List.of("/a.mp3", "/b.mp3", "/c.mp3"));
        var trackIds = playlist.tracks().stream().map(PlaylistTrack::id).toList();

        var reordered = service.reorderTracks(playlist.id(), List.of(trackIds.get(2), trackIds.get(1), trackIds.get(0)));

        assertEquals(trackIds.get(2), reordered.tracks().get(0).id());
        assertEquals(trackIds.get(1), reordered.tracks().get(1).id());
        assertEquals(trackIds.get(0), reordered.tracks().get(2).id());
        assertEquals(0, reordered.tracks().get(0).position());
        assertEquals(1, reordered.tracks().get(1).position());
        assertEquals(2, reordered.tracks().get(2).position());
    }

    @Test
    void reorderTracksWithSameOrderPreservesPositions() {
        var playlist = service.createPlaylist("Same Order", "", List.of("/x.mp3", "/y.mp3"));
        var trackIds = playlist.tracks().stream().map(PlaylistTrack::id).toList();

        var reordered = service.reorderTracks(playlist.id(), trackIds);

        assertEquals("/x.mp3", reordered.tracks().get(0).trackPath());
        assertEquals("/y.mp3", reordered.tracks().get(1).trackPath());
    }

    @Test
    void reorderTracksThrowsForNonexistentPlaylist() {
        assertThrows(NyxException.class, () -> service.reorderTracks("nonexistent-id", List.of("track-1")));
    }

    @Test
    void reorderTracksWithEmptyListIsANoop() {
        var playlist = service.createPlaylist("Empty Reorder", "", List.of("/a.mp3"));

        var result = service.reorderTracks(playlist.id(), List.of());
        assertNotNull(result);
    }

    @Test
    void validateTrackPathsRejectsPathsOutsideAllowedRoots() throws IOException {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media-val"));
        PathSecurity pathSecurity = new PathSecurity(List.of(mediaRoot));
        var validatedService = createService(pathSecurity);

        NyxException exception = assertThrows(
            NyxException.class,
            () -> validatedService.createPlaylist("Test", "desc", List.of("/etc/passwd"))
        );
        assertEquals(ErrorCode.PATH_NOT_ALLOWED, exception.getErrorCode());
    }

    @Test
    void validateTrackPathsAllowsPathsInsideMediaRoots() throws IOException {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media-val2"));
        Path audioFile = mediaRoot.resolve("song.mp3");
        Files.write(audioFile, new byte[10]);
        PathSecurity pathSecurity = new PathSecurity(List.of(mediaRoot));
        var validatedService = createService(pathSecurity);

        var playlist = validatedService.createPlaylist("Test", "desc", List.of(audioFile.toString()));
        assertEquals("Test", playlist.name());
        assertEquals(1, playlist.tracks().size());
    }

    @Test
    void playlistServiceWithoutPathSecuritySkipsValidation() throws IOException {
        var validatedService = createService(null);

        var playlist = validatedService.createPlaylist("Test", "desc", List.of("/any/path.mp3"));
        assertEquals("Test", playlist.name());
    }

    @Test
    void updatePlaylistValidatesTrackPathsFromImprovements() throws IOException {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media-val4"));
        Path audioFile = mediaRoot.resolve("song.mp3");
        Files.write(audioFile, new byte[10]);
        PathSecurity pathSecurity = new PathSecurity(List.of(mediaRoot));
        var validatedService = createService(pathSecurity);

        var playlist = validatedService.createPlaylist("Test", "desc", List.of(audioFile.toString()));

        NyxException exception = assertThrows(
            NyxException.class,
            () -> validatedService.updatePlaylist(playlist.id(), null, null, List.of("/etc/passwd"))
        );
        assertEquals(ErrorCode.PATH_NOT_ALLOWED, exception.getErrorCode());
    }

    @Test
    void importM3UValidatesTrackPaths() throws IOException {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media-val5"));
        PathSecurity pathSecurity = new PathSecurity(List.of(mediaRoot));
        var validatedService = createService(pathSecurity);

        String m3u = """
            #EXTM3U
            #EXTINF:-1,Bad Song
            /etc/passwd
            """.stripIndent();

        NyxException exception = assertThrows(
            NyxException.class,
            () -> validatedService.importM3U(m3u, "Bad Playlist")
        );
        assertEquals(ErrorCode.PATH_NOT_ALLOWED, exception.getErrorCode());
    }

    @Test
    void parseM3UWithBaseDirFromImprovements() {
        String m3u = """
            #EXTM3U
            #EXTINF:-1,Song 1
            relative/song1.mp3
            #EXTINF:-1,Song 2
            /absolute/song2.mp3
            """.stripIndent();

        var tracks = PlaylistService.parseM3U(m3u, "/music");
        assertEquals(2, tracks.size());
        assertTrue(tracks.get(0).startsWith("/music"));
        assertEquals("/absolute/song2.mp3", tracks.get(1));
    }

    @Test
    void playlistTrackRecordCoverage() {
        var track = new PlaylistTrack("t1", "/music/song.mp3", 0, Instant.now());
        assertEquals("t1", track.id());
        assertEquals("/music/song.mp3", track.trackPath());
        assertEquals(0, track.position());
    }

    @Test
    void playlistSummaryRecordCoverage() {
        Instant now = Instant.now();
        var summary = new PlaylistSummary("p1", "My Playlist", "desc", 5, now, now);

        assertEquals("p1", summary.id());
        assertEquals(5, summary.trackCount());
    }

    private PlaylistService createService(PathSecurity pathSecurity) throws IOException {
        dbCounter++;
        Path dbDir = Files.createDirectories(tempDir.resolve("db" + dbCounter));
        DatabaseResources resources = PlaylistService.createDatabase(dbDir);
        dataSources.add(resources.getDataSource());
        return new PlaylistService(resources.getJdbi(), pathSecurity);
    }
}
