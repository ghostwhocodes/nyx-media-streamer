package com.nyx.media;

import com.nyx.common.AuditLogger;
import com.nyx.common.DatabaseFactory;
import com.nyx.common.DatabaseResources;
import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.common.PathSecurity;
import com.nyx.common.SqliteWriteTransactions;
import com.nyx.config.DatabaseConfig;
import com.nyx.media.model.Playlist;
import com.nyx.media.model.PlaylistSummary;
import com.nyx.media.model.PlaylistTrack;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PlaylistService {
    private final Jdbi jdbi;
    private final PathSecurity pathSecurity;
    private final Clock clock;
    private final Logger log = LoggerFactory.getLogger(PlaylistService.class);

    public PlaylistService(Jdbi jdbi) {
        this(jdbi, null, Clock.systemUTC());
    }

    public PlaylistService(Jdbi jdbi, PathSecurity pathSecurity) {
        this(jdbi, pathSecurity, Clock.systemUTC());
    }

    public PlaylistService(Jdbi jdbi, PathSecurity pathSecurity, Clock clock) {
        this.jdbi = jdbi;
        this.pathSecurity = pathSecurity;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public Playlist createPlaylist(String name, String description, List<String> tracks) {
        validateTrackPaths(tracks);
        log.info("Creating playlist '{}' with {} tracks", name, tracks.size());
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now(clock);

        SqliteWriteTransactions.inTransactionUnchecked(jdbi, handle -> {
            handle.createUpdate(
                    """
                    INSERT INTO playlists(id, name, description, created_at, updated_at)
                    VALUES (:id, :name, :description, :createdAt, :updatedAt)
                    """
                )
                .bind("id", id)
                .bind("name", name)
                .bind("description", description)
                .bind("createdAt", now.toString())
                .bind("updatedAt", now.toString())
                .execute();

            insertTracks(handle, id, tracks, now);
            return null;
        });

        Playlist playlist = getPlaylist(id);
        if (playlist == null) {
            sneakyThrow(new NyxException(ErrorCode.PLAYLIST_NOT_FOUND, "Failed to create playlist", Map.of(), null));
        }
        return playlist;
    }

    public Playlist getPlaylist(String id) {
        return SqliteWriteTransactions.withHandleUnchecked(jdbi, handle -> {
            PlaylistRow playlistRow = handle.createQuery(
                    """
                    SELECT id, name, description, created_at, updated_at
                    FROM playlists
                    WHERE id = :id
                    """
                )
                .bind("id", id)
                .map((resultSet, context) -> new PlaylistRow(
                    resultSet.getString("id"),
                    resultSet.getString("name"),
                    resultSet.getString("description"),
                    Instant.parse(resultSet.getString("created_at")),
                    Instant.parse(resultSet.getString("updated_at"))
                ))
                .findOne()
                .orElse(null);
            if (playlistRow == null) {
                return null;
            }
            return playlistRow.toPlaylist(loadTracks(handle, id));
        });
    }

    public Playlist updatePlaylist(String id, String name, String description, List<String> tracks) {
        if (tracks != null) {
            validateTrackPaths(tracks);
        }
        Instant now = Instant.now(clock);

        SqliteWriteTransactions.inTransactionUnchecked(jdbi, handle -> {
            ensurePlaylistExists(handle, id);

            handle.createUpdate(
                    """
                    UPDATE playlists
                    SET name = COALESCE(:name, name),
                        description = COALESCE(:description, description),
                        updated_at = :updatedAt
                    WHERE id = :id
                    """
                )
                .bind("id", id)
                .bind("name", name)
                .bind("description", description)
                .bind("updatedAt", now.toString())
                .execute();

            if (tracks != null) {
                handle.createUpdate("DELETE FROM playlist_tracks WHERE playlist_id = :playlistId")
                    .bind("playlistId", id)
                    .execute();
                insertTracks(handle, id, tracks, now);
            }
            return null;
        });

        Playlist playlist = getPlaylist(id);
        if (playlist == null) {
            sneakyThrow(new NyxException(ErrorCode.PLAYLIST_NOT_FOUND, "Playlist not found after update", Map.of(), null));
        }
        return playlist;
    }

    public Playlist reorderTracks(String playlistId, List<String> trackIds) {
        log.info("Reordering {} tracks in playlist {}", trackIds.size(), playlistId);
        SqliteWriteTransactions.inTransactionUnchecked(jdbi, handle -> {
            ensurePlaylistExists(handle, playlistId);

            for (int index = 0; index < trackIds.size(); index++) {
                handle.createUpdate(
                        """
                        UPDATE playlist_tracks
                        SET position = :position
                        WHERE id = :trackId AND playlist_id = :playlistId
                        """
                    )
                    .bind("position", index)
                    .bind("trackId", trackIds.get(index))
                    .bind("playlistId", playlistId)
                    .execute();
            }

            handle.createUpdate("UPDATE playlists SET updated_at = :updatedAt WHERE id = :id")
                .bind("updatedAt", Instant.now(clock).toString())
                .bind("id", playlistId)
                .execute();
            return null;
        });

        Playlist playlist = getPlaylist(playlistId);
        if (playlist == null) {
            sneakyThrow(new NyxException(ErrorCode.PLAYLIST_NOT_FOUND, "Playlist not found after reorder", Map.of(), null));
        }
        return playlist;
    }

    public void deletePlaylist(String id) {
        log.info("Deleting playlist {}", id);
        SqliteWriteTransactions.inTransactionUnchecked(jdbi, handle -> {
            handle.createUpdate("DELETE FROM playlist_tracks WHERE playlist_id = :playlistId")
                .bind("playlistId", id)
                .execute();

            int deleted = handle.createUpdate("DELETE FROM playlists WHERE id = :id")
                .bind("id", id)
                .execute();
            if (deleted == 0) {
                sneakyThrow(new NyxException(ErrorCode.PLAYLIST_NOT_FOUND, "Playlist not found: " + id, Map.of(), null));
            }
            return null;
        });
        AuditLogger.log("DELETE", "/api/v1/audio/playlists/" + id, "service", id, "204");
    }

    public List<PlaylistSummary> listPlaylists() {
        return SqliteWriteTransactions.withHandleUnchecked(jdbi, handle ->
            handle.createQuery(
                    """
                    SELECT p.id,
                           p.name,
                           p.description,
                           p.created_at,
                           p.updated_at,
                           COUNT(t.id) AS track_count
                    FROM playlists p
                    LEFT JOIN playlist_tracks t ON t.playlist_id = p.id
                    GROUP BY p.id, p.name, p.description, p.created_at, p.updated_at
                    ORDER BY p.updated_at DESC
                    """
                )
                .map((resultSet, context) -> new PlaylistSummary(
                    resultSet.getString("id"),
                    resultSet.getString("name"),
                    resultSet.getString("description"),
                    resultSet.getInt("track_count"),
                    Instant.parse(resultSet.getString("created_at")),
                    Instant.parse(resultSet.getString("updated_at"))
                ))
                .list()
        );
    }

    public Playlist importM3U(String content, String playlistName) {
        return importM3U(content, playlistName, null);
    }

    public Playlist importM3U(String content, String playlistName, String baseDir) {
        List<String> tracks = parseM3U(content, baseDir);
        validateTrackPaths(tracks);
        log.info("Importing M3U playlist '{}' with {} tracks", playlistName, tracks.size());
        return createPlaylist(playlistName, "Imported from M3U", tracks);
    }

    public String exportM3U(String id) {
        Playlist playlist = getPlaylist(id);
        if (playlist == null) {
            sneakyThrow(new NyxException(ErrorCode.PLAYLIST_NOT_FOUND, "Playlist not found: " + id, Map.of(), null));
        }

        StringBuilder builder = new StringBuilder();
        builder.append("#EXTM3U").append(System.lineSeparator());
        builder.append("#PLAYLIST:").append(playlist.name()).append(System.lineSeparator());
        for (PlaylistTrack track : playlist.tracks()) {
            builder.append("#EXTINF:-1,").append(track.trackPath().substring(track.trackPath().lastIndexOf('/') + 1))
                .append(System.lineSeparator());
            builder.append(track.trackPath()).append(System.lineSeparator());
        }
        return builder.toString();
    }

    private void validateTrackPaths(List<String> tracks) {
        if (pathSecurity == null) {
            return;
        }
        List<String> invalid = tracks.stream()
            .filter(track -> !isRemoteUrl(track))
            .filter(track -> {
                try {
                    pathSecurity.validate(track);
                    return false;
                } catch (RuntimeException failure) {
                    return true;
                }
            })
            .toList();
        if (!invalid.isEmpty()) {
            sneakyThrow(new NyxException(
                ErrorCode.PATH_NOT_ALLOWED,
                "Playlist contains " + invalid.size() + " path(s) outside allowed media roots",
                Map.of(),
                null
            ));
        }
    }

    private void ensurePlaylistExists(Handle handle, String playlistId) {
        boolean exists = handle.createQuery("SELECT COUNT(*) FROM playlists WHERE id = :id")
            .bind("id", playlistId)
            .mapTo(Integer.class)
            .one() > 0;
        if (!exists) {
            sneakyThrow(new NyxException(ErrorCode.PLAYLIST_NOT_FOUND, "Playlist not found: " + playlistId, Map.of(), null));
        }
    }

    private void insertTracks(Handle handle, String playlistId, List<String> tracks, Instant now) {
        for (int index = 0; index < tracks.size(); index++) {
            handle.createUpdate(
                    """
                    INSERT INTO playlist_tracks(id, playlist_id, track_path, position, added_at)
                    VALUES (:id, :playlistId, :trackPath, :position, :addedAt)
                    """
                )
                .bind("id", UUID.randomUUID().toString())
                .bind("playlistId", playlistId)
                .bind("trackPath", tracks.get(index))
                .bind("position", index)
                .bind("addedAt", now.toString())
                .execute();
        }
    }

    private List<PlaylistTrack> loadTracks(Handle handle, String playlistId) {
        return handle.createQuery(
                """
                SELECT id, track_path, position, added_at
                FROM playlist_tracks
                WHERE playlist_id = :playlistId
                ORDER BY position
                """
            )
            .bind("playlistId", playlistId)
            .map((resultSet, context) -> new PlaylistTrack(
                resultSet.getString("id"),
                resultSet.getString("track_path"),
                resultSet.getInt("position"),
                Instant.parse(resultSet.getString("added_at"))
            ))
            .list();
    }

    public static DatabaseResources createDatabase(Path dbDir) {
        return createDatabase(dbDir, new DatabaseConfig(dbDir, 1, 600_000L, 1_800_000L));
    }

    public static DatabaseResources createDatabase(Path dbDir, DatabaseConfig dbConfig) {
        return DatabaseFactory.create(dbDir, "playlists", dbConfig);
    }

    public static List<String> parseM3U(String content) {
        return parseM3U(content, null);
    }

    public static List<String> parseM3U(String content, String baseDir) {
        List<String> tracks = new ArrayList<>();
        for (String line : content.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            String normalized = normalizeLine(trimmed);
            String path;
            if (!isRemoteUrl(normalized) && baseDir != null && !normalized.startsWith("/")) {
                path = Path.of(baseDir).resolve(normalized).toString();
            } else {
                path = normalized;
            }
            tracks.add(path);
        }
        return tracks;
    }

    private static boolean isRemoteUrl(String path) {
        int colonSlashSlash = path.indexOf("://");
        return colonSlashSlash > 0 && !path.startsWith("file:");
    }

    private static String decodePercent(String value) {
        if (!value.contains("%")) {
            return value;
        }
        try {
            if (value.startsWith("/")) {
                return new URI("file:" + value).getPath();
            }
            return new URI("file:/" + value).getPath().substring(1);
        } catch (URISyntaxException ignored) {
            return value;
        }
    }

    private static String normalizeLine(String raw) {
        if (isRemoteUrl(raw)) {
            return raw;
        }
        if (raw.startsWith("file:")) {
            try {
                String path = new URI(raw).getPath();
                return path == null ? raw : path;
            } catch (URISyntaxException ignored) {
                return raw;
            }
        }
        return decodePercent(raw.replace('\\', '/'));
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }

    private record PlaylistRow(
        String id,
        String name,
        String description,
        Instant createdAt,
        Instant updatedAt
    ) {
        private Playlist toPlaylist(List<PlaylistTrack> tracks) {
            return new Playlist(id, name, description, tracks, createdAt, updatedAt);
        }
    }
}
