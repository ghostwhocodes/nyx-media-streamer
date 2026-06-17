package com.nyx.media;

import com.nyx.common.DatabaseResources;
import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.common.PathSecurity;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaylistServicePathValidationTest {
    @TempDir
    Path tempDir;

    private final List<HikariDataSource> dataSources = new ArrayList<>();
    private Path mediaRoot;
    private Path outsideDir;
    private int dbCounter;

    @BeforeEach
    void setup() throws IOException {
        mediaRoot = Files.createDirectories(tempDir.resolve("media-root"));
        outsideDir = Files.createDirectories(tempDir.resolve("outside-root"));

        Files.createDirectories(mediaRoot.resolve("music"));
        Files.createFile(mediaRoot.resolve("music/song1.mp3"));
        Files.createFile(mediaRoot.resolve("music/song2.flac"));
        Files.createFile(mediaRoot.resolve("music/song3.ogg"));
        Files.createFile(outsideDir.resolve("evil.mp3"));
    }

    @AfterEach
    void teardown() {
        dataSources.forEach(HikariDataSource::close);
        dataSources.clear();
    }

    @Test
    void importM3UWithPathOutsideAllowedRootThrowsPathNotAllowed() throws IOException {
        PathSecurity pathSecurity = new PathSecurity(List.of(mediaRoot));
        PlaylistService service = createService(pathSecurity);

        String evilPath = outsideDir.resolve("evil.mp3").toString();
        String m3u = "#EXTM3U\n" + evilPath;

        NyxException exception = assertThrows(NyxException.class, () -> service.importM3U(m3u, "Evil Playlist"));
        assertEquals(ErrorCode.PATH_NOT_ALLOWED, exception.getErrorCode());
    }

    @Test
    void importM3UWithMixedValidAndInvalidPathsThrowsPathNotAllowed() throws IOException {
        PathSecurity pathSecurity = new PathSecurity(List.of(mediaRoot));
        PlaylistService service = createService(pathSecurity);

        String validPath = mediaRoot.resolve("music/song1.mp3").toString();
        String evilPath = outsideDir.resolve("evil.mp3").toString();
        String m3u = "#EXTM3U\n" + validPath + '\n' + evilPath;

        NyxException exception = assertThrows(NyxException.class, () -> service.importM3U(m3u, "Mixed Playlist"));
        assertEquals(ErrorCode.PATH_NOT_ALLOWED, exception.getErrorCode());
    }

    @Test
    void importM3UWithAllPathsInsideAllowedRootSucceeds() throws IOException {
        PathSecurity pathSecurity = new PathSecurity(List.of(mediaRoot));
        PlaylistService service = createService(pathSecurity);

        String path1 = mediaRoot.resolve("music/song1.mp3").toString();
        String path2 = mediaRoot.resolve("music/song2.flac").toString();
        String m3u = "#EXTM3U\n" + path1 + '\n' + path2;

        var playlist = service.importM3U(m3u, "Valid Playlist");

        assertEquals("Valid Playlist", playlist.name());
        assertEquals(2, playlist.tracks().size());
        assertEquals(path1, playlist.tracks().get(0).trackPath());
        assertEquals(path2, playlist.tracks().get(1).trackPath());
    }

    @Test
    void importM3UWithNonexistentFileInsideRootThrowsPathNotAllowed() throws IOException {
        PathSecurity pathSecurity = new PathSecurity(List.of(mediaRoot));
        PlaylistService service = createService(pathSecurity);

        String nonexistentPath = mediaRoot.resolve("music/nonexistent.mp3").toString();

        NyxException exception = assertThrows(
            NyxException.class,
            () -> service.importM3U(nonexistentPath, "Nonexistent")
        );
        assertEquals(ErrorCode.PATH_NOT_ALLOWED, exception.getErrorCode());
    }

    @Test
    void createPlaylistWithInvalidPathsThrowsPathNotAllowed() throws IOException {
        PathSecurity pathSecurity = new PathSecurity(List.of(mediaRoot));
        PlaylistService service = createService(pathSecurity);

        String evilPath = outsideDir.resolve("evil.mp3").toString();

        NyxException exception = assertThrows(
            NyxException.class,
            () -> service.createPlaylist("Bad Playlist", "Has invalid paths", List.of(evilPath))
        );
        assertEquals(ErrorCode.PATH_NOT_ALLOWED, exception.getErrorCode());
    }

    @Test
    void createPlaylistWithValidPathsSucceeds() throws IOException {
        PathSecurity pathSecurity = new PathSecurity(List.of(mediaRoot));
        PlaylistService service = createService(pathSecurity);

        String validPath = mediaRoot.resolve("music/song1.mp3").toString();
        var playlist = service.createPlaylist("Good Playlist", "Valid paths", List.of(validPath));

        assertEquals("Good Playlist", playlist.name());
        assertEquals(1, playlist.tracks().size());
    }

    @Test
    void createPlaylistWithEmptyTrackListSucceeds() throws IOException {
        PathSecurity pathSecurity = new PathSecurity(List.of(mediaRoot));
        PlaylistService service = createService(pathSecurity);

        var playlist = service.createPlaylist("Empty", "No tracks", List.of());
        assertEquals("Empty", playlist.name());
        assertTrue(playlist.tracks().isEmpty());
    }

    @Test
    void updatePlaylistWithInvalidTrackPathsThrowsPathNotAllowed() throws IOException {
        PathSecurity pathSecurity = new PathSecurity(List.of(mediaRoot));
        PlaylistService service = createService(pathSecurity);

        String validPath = mediaRoot.resolve("music/song1.mp3").toString();
        String evilPath = outsideDir.resolve("evil.mp3").toString();
        var playlist = service.createPlaylist("Test", "", List.of(validPath));

        NyxException exception = assertThrows(
            NyxException.class,
            () -> service.updatePlaylist(playlist.id(), null, null, List.of(evilPath))
        );
        assertEquals(ErrorCode.PATH_NOT_ALLOWED, exception.getErrorCode());
    }

    @Test
    void updatePlaylistWithValidTrackPathsSucceeds() throws IOException {
        PathSecurity pathSecurity = new PathSecurity(List.of(mediaRoot));
        PlaylistService service = createService(pathSecurity);

        String path1 = mediaRoot.resolve("music/song1.mp3").toString();
        String path2 = mediaRoot.resolve("music/song2.flac").toString();
        String path3 = mediaRoot.resolve("music/song3.ogg").toString();

        var playlist = service.createPlaylist("Test", "", List.of(path1));
        var updated = service.updatePlaylist(playlist.id(), null, null, List.of(path2, path3));

        assertEquals(2, updated.tracks().size());
    }

    @Test
    void updatePlaylistWithNullTracksSkipsPathValidation() throws IOException {
        PathSecurity pathSecurity = new PathSecurity(List.of(mediaRoot));
        PlaylistService service = createService(pathSecurity);

        String validPath = mediaRoot.resolve("music/song1.mp3").toString();
        var playlist = service.createPlaylist("Test", "", List.of(validPath));

        var updated = service.updatePlaylist(playlist.id(), "Renamed", null, null);
        assertEquals("Renamed", updated.name());
        assertEquals(1, updated.tracks().size());
    }

    @Test
    void nullPathSecurityAllowsAnyPathsInCreatePlaylist() throws IOException {
        PlaylistService service = createService(null);

        var playlist = service.createPlaylist(
            "No Security",
            "",
            List.of("/any/arbitrary/path.mp3", "/another/random/path.flac")
        );

        assertEquals(2, playlist.tracks().size());
        assertEquals("/any/arbitrary/path.mp3", playlist.tracks().get(0).trackPath());
    }

    @Test
    void nullPathSecurityAllowsAnyPathsInImportM3U() throws IOException {
        PlaylistService service = createService(null);

        String m3u = "#EXTM3U\n/anything/goes.mp3\n/whatever/path.flac";
        var playlist = service.importM3U(m3u, "No Validation");

        assertEquals(2, playlist.tracks().size());
    }

    @Test
    void nullPathSecurityAllowsAnyPathsInUpdatePlaylist() throws IOException {
        PlaylistService service = createService(null);

        var playlist = service.createPlaylist("Test", "", List.of("/a.mp3"));
        var updated = service.updatePlaylist(playlist.id(), null, null, List.of("/anywhere/new.mp3"));

        assertEquals(1, updated.tracks().size());
        assertEquals("/anywhere/new.mp3", updated.tracks().get(0).trackPath());
    }

    @Test
    void pathsFromMultipleAllowedRootsAllSucceed() throws IOException {
        Path secondRoot = Files.createDirectories(tempDir.resolve("media-root-2"));
        Files.createDirectories(secondRoot.resolve("audio"));
        Files.createFile(secondRoot.resolve("audio/track.wav"));

        PathSecurity pathSecurity = new PathSecurity(List.of(mediaRoot, secondRoot));
        PlaylistService service = createService(pathSecurity);

        String path1 = mediaRoot.resolve("music/song1.mp3").toString();
        String path2 = secondRoot.resolve("audio/track.wav").toString();

        var playlist = service.createPlaylist("Multi-root", "", List.of(path1, path2));
        assertEquals(2, playlist.tracks().size());
    }

    @Test
    void pathTraversalAttemptInM3UIsRejected() throws IOException {
        PathSecurity pathSecurity = new PathSecurity(List.of(mediaRoot));
        PlaylistService service = createService(pathSecurity);

        String traversalPath = mediaRoot.resolve("music/../../../etc/passwd").toString();

        NyxException exception = assertThrows(
            NyxException.class,
            () -> service.importM3U(traversalPath, "Traversal Attempt")
        );
        assertEquals(ErrorCode.PATH_NOT_ALLOWED, exception.getErrorCode());
    }

    private PlaylistService createService(PathSecurity pathSecurity) throws IOException {
        dbCounter++;
        Path dbDir = Files.createDirectories(tempDir.resolve("db" + dbCounter));
        DatabaseResources resources = PlaylistService.createDatabase(dbDir);
        dataSources.add(resources.getDataSource());
        return new PlaylistService(resources.getJdbi(), pathSecurity);
    }
}
