package com.nyx.media;

import com.nyx.common.DatabaseResources;
import com.nyx.common.PathSecurity;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlaylistServiceM3UNormalizationDeepTest {
    private Path tempDir;
    private Path mediaRoot;
    private final List<HikariDataSource> dataSources = new ArrayList<>();

    @BeforeEach
    void setup() throws IOException {
        tempDir = Files.createTempDirectory("nyx-m3u-norm-deep");
        mediaRoot = Files.createTempDirectory("nyx-m3u-norm-media");
        Files.createDirectories(mediaRoot.resolve("music"));
        Files.createFile(mediaRoot.resolve("music/song.mp3"));
        Files.createDirectories(mediaRoot.resolve("my music"));
        Files.createFile(mediaRoot.resolve("my music/song.mp3"));
    }

    @AfterEach
    void teardown() throws IOException {
        dataSources.forEach(HikariDataSource::close);
        dataSources.clear();
        deleteRecursively(tempDir);
        deleteRecursively(mediaRoot);
    }

    @Test
    void httpsUrlBypassesPathSecurityInMixedPlaylist() throws IOException {
        PathSecurity pathSecurity = new PathSecurity(List.of(mediaRoot));
        PlaylistService service = createService(pathSecurity);

        String localPath = mediaRoot.resolve("music/song.mp3").toString();
        String m3u = "#EXTM3U\n" + localPath + "\nhttps://cdn.example.com/remote.mp3";

        var playlist = service.importM3U(m3u, "Mixed Remote");
        assertEquals(2, playlist.tracks().size());
        assertEquals(localPath, playlist.tracks().get(0).trackPath());
        assertEquals("https://cdn.example.com/remote.mp3", playlist.tracks().get(1).trackPath());
    }

    @Test
    void httpUrlBypassesPathSecurity() throws IOException {
        PathSecurity pathSecurity = new PathSecurity(List.of(mediaRoot));
        PlaylistService service = createService(pathSecurity);

        var playlist = service.importM3U("http://example.com/stream.mp3", "Remote Only");
        assertEquals(1, playlist.tracks().size());
        assertEquals("http://example.com/stream.mp3", playlist.tracks().get(0).trackPath());
    }

    @Test
    void fileUriResolvesAndPassesPathSecurity() throws IOException {
        PathSecurity pathSecurity = new PathSecurity(List.of(mediaRoot));
        PlaylistService service = createService(pathSecurity);

        String fileUri = "file://" + mediaRoot.resolve("music/song.mp3");
        var playlist = service.importM3U(fileUri, "File URI Playlist");
        assertEquals(1, playlist.tracks().size());
        assertEquals(mediaRoot.resolve("music/song.mp3").toString(), playlist.tracks().get(0).trackPath());
    }

    @Test
    void percentEncodedLocalPathDecodesAndPassesPathSecurity() throws IOException {
        PathSecurity pathSecurity = new PathSecurity(List.of(mediaRoot));
        PlaylistService service = createService(pathSecurity);

        String encodedPath = mediaRoot + "/my%20music/song.mp3";
        var playlist = service.importM3U(encodedPath, "Encoded Path Playlist");
        assertEquals(1, playlist.tracks().size());
        assertEquals(mediaRoot.resolve("my music/song.mp3").toString(), playlist.tracks().get(0).trackPath());
    }

    private PlaylistService createService(PathSecurity pathSecurity) throws IOException {
        Path dbDir = tempDir.resolve("db-" + UUID.randomUUID());
        Files.createDirectories(dbDir);
        DatabaseResources resources = PlaylistService.createDatabase(dbDir);
        dataSources.add(resources.getDataSource());
        return new PlaylistService(resources.getJdbi(), pathSecurity);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            });
        }
    }
}
