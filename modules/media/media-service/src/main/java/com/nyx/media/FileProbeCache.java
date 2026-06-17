package com.nyx.media;

import static com.nyx.common.SqliteWriteTransactions.sqliteWriteTransaction;
import static com.nyx.common.SqliteWriteTransactions.withHandleUnchecked;

import com.nyx.common.DatabaseFactory;
import com.nyx.common.DatabaseResources;
import com.nyx.config.DatabaseConfig;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import org.jdbi.v3.core.Jdbi;

public final class FileProbeCache {
    private final Jdbi jdbi;
    private final Clock clock;

    public FileProbeCache(Jdbi jdbi) {
        this(jdbi, Clock.systemUTC());
    }

    public FileProbeCache(Jdbi jdbi, Clock clock) {
        this.jdbi = jdbi;
        this.clock = clock;
    }

    public AudioMetadata get(String path, long mtime, long size) {
        StoredFileProbe stored = withHandleUnchecked(jdbi, handle -> handle.createQuery("""
                SELECT path, mtime, size, duration_secs, bitrate, channels, artist, album, title
                FROM file_probe_cache
                WHERE path = :path
                """)
            .bind("path", path)
            .map((resultSet, context) -> new StoredFileProbe(
                resultSet.getString("path"),
                resultSet.getLong("mtime"),
                resultSet.getLong("size"),
                getNullableDouble(resultSet, "duration_secs"),
                getNullableLong(resultSet, "bitrate"),
                getNullableInt(resultSet, "channels"),
                resultSet.getString("artist"),
                resultSet.getString("album"),
                resultSet.getString("title")
            ))
            .findOne()
            .orElse(null));
        if (stored == null) {
            return null;
        }
        if (stored.mtime() != mtime || stored.size() != size) {
            return null;
        }
        return new AudioMetadata(
            stored.durationSecs(),
            stored.bitrate(),
            stored.channels(),
            stored.artist(),
            stored.album(),
            stored.title()
        );
    }

    public void put(String path, long mtime, long size, AudioMetadata meta) {
        sqliteWriteTransaction(jdbi, handle -> {
            handle.createUpdate("""
                    INSERT INTO file_probe_cache (
                        path,
                        mtime,
                        size,
                        duration_secs,
                        bitrate,
                        channels,
                        artist,
                        album,
                        title,
                        probed_at
                    )
                    VALUES (
                        :path,
                        :mtime,
                        :size,
                        :durationSecs,
                        :bitrate,
                        :channels,
                        :artist,
                        :album,
                        :title,
                        :probedAt
                    )
                    ON CONFLICT(path) DO UPDATE SET
                        mtime = excluded.mtime,
                        size = excluded.size,
                        duration_secs = excluded.duration_secs,
                        bitrate = excluded.bitrate,
                        channels = excluded.channels,
                        artist = excluded.artist,
                        album = excluded.album,
                        title = excluded.title,
                        probed_at = excluded.probed_at
                    """)
                .bind("path", path)
                .bind("mtime", mtime)
                .bind("size", size)
                .bind("durationSecs", meta.getDuration())
                .bind("bitrate", meta.getBitrate())
                .bind("channels", meta.getChannels())
                .bind("artist", meta.getArtist())
                .bind("album", meta.getAlbum())
                .bind("title", meta.getTitle())
                .bind("probedAt", Instant.now(clock).toString())
                .execute();
            return null;
        });
    }

    public static DatabaseResources createDatabase(Path dbDir) {
        return createDatabase(dbDir, new DatabaseConfig(dbDir, 1));
    }

    public static DatabaseResources createDatabase(Path dbDir, DatabaseConfig dbConfig) {
        return DatabaseFactory.create(dbDir, "audio_probes", dbConfig);
    }

    private static Integer getNullableInt(ResultSet resultSet, String columnLabel) throws SQLException {
        Object value = resultSet.getObject(columnLabel);
        return value == null ? null : ((Number) value).intValue();
    }

    private static Long getNullableLong(ResultSet resultSet, String columnLabel) throws SQLException {
        Object value = resultSet.getObject(columnLabel);
        return value == null ? null : ((Number) value).longValue();
    }

    private static Double getNullableDouble(ResultSet resultSet, String columnLabel) throws SQLException {
        Object value = resultSet.getObject(columnLabel);
        return value == null ? null : ((Number) value).doubleValue();
    }

    private record StoredFileProbe(
        String path,
        long mtime,
        long size,
        Double durationSecs,
        Long bitrate,
        Integer channels,
        String artist,
        String album,
        String title
    ) {
    }
}
