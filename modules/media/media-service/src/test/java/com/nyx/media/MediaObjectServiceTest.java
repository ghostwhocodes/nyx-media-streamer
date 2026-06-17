package com.nyx.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.common.DatabaseResources;
import com.nyx.config.DatabaseConfig;
import com.nyx.media.contracts.MediaKind;
import com.nyx.media.contracts.MediaObject;
import com.nyx.media.contracts.MediaObjectContracts;
import com.nyx.media.contracts.MediaObjectPathKind;
import com.nyx.media.contracts.MediaObjectStatus;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaObjectServiceTest {
    @TempDir
    Path tempDir;

    private final List<HikariDataSource> dataSources = new ArrayList<>();

    @AfterEach
    void teardown() {
        ModuleMediaTestSupport.closeDataSources(dataSources);
    }

    private MediaObjectService createService(int poolSize) {
        DatabaseResources resources = MediaObjectService.createDatabase(tempDir, new DatabaseConfig(tempDir, poolSize));
        dataSources.add(resources.getDataSource());
        return new MediaObjectService(resources.getJdbi());
    }

    private MediaObjectService createService() {
        return createService(1);
    }

    private Path createMediaFile(String name) throws IOException {
        return ModuleMediaTestSupport.writeTextFile(tempDir.resolve("library").resolve(name), "media-" + name);
    }

    private MediaObjectUpsertRequest sampleUpsert(Path path, MediaKind mediaKind) throws IOException {
        return new MediaObjectUpsertRequest(
            mediaKind,
            path.toString(),
            switch (mediaKind) {
                case VIDEO -> "video/mp4";
                case AUDIO -> "audio/flac";
                case IMAGE -> "image/jpeg";
                case OTHER -> "application/octet-stream";
            },
            Files.size(path),
            "2026-04-10T12:00:00Z",
            path.getFileName().toString(),
            mediaKind == MediaKind.VIDEO || mediaKind == MediaKind.AUDIO ? 95_000L : null,
            mediaKind == MediaKind.VIDEO || mediaKind == MediaKind.IMAGE ? 1920 : null,
            mediaKind == MediaKind.VIDEO || mediaKind == MediaKind.IMAGE ? 1080 : null,
            mediaKind == MediaKind.AUDIO ? 2 : null,
            mediaKind == MediaKind.IMAGE ? "2026-04-09T09:00:00Z" : null,
            "Sample Title",
            mediaKind == MediaKind.AUDIO ? "Sample Artist" : null,
            mediaKind == MediaKind.AUDIO ? "Sample Album" : null,
            MediaObjectContracts.MEDIA_OBJECT_HASH_ALGORITHM_NONE,
            null,
            MediaObjectStatus.ACTIVE
        );
    }

    private MediaObjectUpsertRequest sampleUpsert(Path path) throws IOException {
        return sampleUpsert(path, MediaKind.VIDEO);
    }

    private MediaObjectUpsertRequest copyUpsert(
        MediaObjectUpsertRequest base,
        String mimeType,
        Long durationMillis,
        Long sizeBytes,
        String modifiedAt,
        String hashAlgorithm,
        String contentHash
    ) {
        return new MediaObjectUpsertRequest(
            base.getMediaKind(),
            base.getPrimaryPath(),
            mimeType != null ? mimeType : base.getMimeType(),
            sizeBytes != null ? sizeBytes : base.getSizeBytes(),
            modifiedAt != null ? modifiedAt : base.getModifiedAt(),
            base.getDisplayName(),
            durationMillis != null ? durationMillis : base.getDurationMillis(),
            base.getWidth(),
            base.getHeight(),
            base.getChannels(),
            base.getTakenAt(),
            base.getEmbeddedTitle(),
            base.getEmbeddedArtist(),
            base.getEmbeddedAlbum(),
            hashAlgorithm != null ? hashAlgorithm : base.getHashAlgorithm(),
            contentHash != null ? contentHash : base.getContentHash(),
            base.getStatus()
        );
    }

    @Test
    void createDatabaseProvisionsMediaObjectReservedTablesAndHashingColumns() {
        DatabaseResources resources = MediaObjectService.createDatabase(tempDir);
        dataSources.add(resources.getDataSource());

        Set<String> tables = new java.util.HashSet<>();
        Map<String, Integer> mediaObjectColumns = new HashMap<>();

        try (var connection = resources.getDataSource().getConnection();
             var statement = connection.createStatement()) {
            try (var resultSet = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
                while (resultSet.next()) {
                    tables.add(resultSet.getString("name"));
                }
            }
            try (var resultSet = statement.executeQuery("PRAGMA table_info(media_objects)")) {
                while (resultSet.next()) {
                    mediaObjectColumns.put(resultSet.getString("name"), resultSet.getInt("notnull"));
                }
            }
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }

        assertTrue(tables.contains("media_objects"));
        assertTrue(tables.contains("media_object_paths"));
        assertTrue(tables.contains("media_object_correlations"));
        assertTrue(tables.contains("media_object_correlation_payloads"));
        assertTrue(tables.contains("media_thumbnails"));
        assertTrue(tables.contains("user_media_states"));
        assertEquals(Integer.valueOf(1), mediaObjectColumns.get("hash_algorithm"));
        assertEquals(Integer.valueOf(0), mediaObjectColumns.get("content_hash"));
    }

    @Test
    void upsertPrimaryPathCreatesStableObjectIdentityWithExplicitPrimaryPathHistory() throws Exception {
        MediaObjectService service = createService();
        Path mediaFile = createMediaFile("movie.mp4");

        var created = service.upsertPrimaryPath(sampleUpsert(mediaFile));
        var stored = service.getByObjectId(created.objectId());
        var paths = service.listPaths(created.objectId());

        assertNotNull(stored);
        assertEquals(created.objectId(), stored.objectId());
        assertEquals(MediaKind.VIDEO, stored.mediaKind());
        assertEquals(mediaFile.toAbsolutePath().normalize().toString(), stored.primaryPath());
        assertEquals(stored.primaryPath(), stored.pathKey());
        assertEquals(MediaObjectContracts.MEDIA_OBJECT_HASH_ALGORITHM_NONE, stored.hashAlgorithm());
        assertNull(stored.contentHash());
        assertEquals(MediaObjectStatus.ACTIVE, stored.status());
        assertEquals(1, paths.size());
        assertEquals(MediaObjectPathKind.PRIMARY, paths.getFirst().kind());
        assertEquals(stored.primaryPath(), paths.getFirst().path());
    }

    @Test
    void upsertPrimaryPathReusesAnExistingObjectForRepeatedNormalizedPaths() throws Exception {
        MediaObjectService service = createService();
        Path mediaFile = createMediaFile("concert.flac");
        var first = service.upsertPrimaryPath(sampleUpsert(mediaFile, MediaKind.AUDIO));

        Thread.sleep(5);

        var second = service.upsertPrimaryPath(
            copyUpsert(sampleUpsert(mediaFile, MediaKind.AUDIO), "audio/mpeg", 101_000L, null, null, null, null)
        );

        assertEquals(first.objectId(), second.objectId());
        assertEquals(first.discoveredAt(), second.discoveredAt());
        assertEquals("audio/mpeg", second.mimeType());
        assertEquals(Files.size(mediaFile), second.sizeBytes());
        assertEquals(101_000L, second.durationMillis());
        assertEquals(1, service.listPaths(second.objectId()).size());
    }

    @Test
    void upsertPrimaryPathRotatesObjectIdentityWhenAPathIsReusedForADifferentFile() throws Exception {
        MediaObjectService service = createService();
        Path mediaFile = createMediaFile("movie.mp4");
        var first = service.upsertPrimaryPath(sampleUpsert(mediaFile));

        var replacement = service.upsertPrimaryPath(
            copyUpsert(sampleUpsert(mediaFile), null, null, first.sizeBytes() + 4_096L, "2026-04-10T12:10:00Z", null, null)
        );

        var oldObject = service.getByObjectId(first.objectId());
        var currentByPath = service.getByPath(mediaFile.toString());
        var oldPaths = service.listPaths(first.objectId());
        var replacementPaths = service.listPaths(replacement.objectId());

        assertNotNull(oldObject);
        assertNotEquals(first.objectId(), replacement.objectId());
        assertEquals(MediaObjectStatus.MISSING, oldObject.status());
        assertEquals(replacement.objectId(), currentByPath.objectId());
        assertEquals(Set.of(MediaObjectPathKind.HISTORICAL), oldPaths.stream().map(path -> path.kind()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(MediaObjectPathKind.PRIMARY), replacementPaths.stream().map(path -> path.kind()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(first.primaryPath(), oldPaths.getFirst().path());
        assertEquals(replacement.primaryPath(), replacementPaths.getFirst().path());
    }

    @Test
    void upsertPrimaryPathPrefersContentHashWhenAvailableForIdentityMatching() throws Exception {
        MediaObjectService service = createService();
        Path mediaFile = createMediaFile("hashed.mp4");
        var first = service.upsertPrimaryPath(
            copyUpsert(sampleUpsert(mediaFile), null, null, null, null, "SHA256", "abc123")
        );

        var second = service.upsertPrimaryPath(
            copyUpsert(sampleUpsert(mediaFile), null, null, null, "2026-04-10T12:30:00Z", "SHA256", "abc123")
        );
        var replacement = service.upsertPrimaryPath(
            copyUpsert(sampleUpsert(mediaFile), null, null, null, "2026-04-10T12:31:00Z", "SHA256", "def456")
        );

        assertEquals(first.objectId(), second.objectId());
        assertNotEquals(first.objectId(), replacement.objectId());
    }

    @Test
    void upsertPrimaryPathCreatesDistinctIdentitiesForDistinctConcreteFiles() throws Exception {
        MediaObjectService service = createService();
        Path firstFile = createMediaFile("movie-a.mp4");
        Path secondFile = createMediaFile("movie-b.mp4");

        var first = service.upsertPrimaryPath(sampleUpsert(firstFile));
        var second = service.upsertPrimaryPath(sampleUpsert(secondFile));

        assertNotEquals(first.objectId(), second.objectId());
        assertNotEquals(first.pathKey(), second.pathKey());
        assertEquals(first.objectId(), service.getByPath(firstFile.toString()).objectId());
        assertEquals(second.objectId(), service.getByPath(secondFile.toString()).objectId());
    }

    @Test
    void upsertPrimaryPathIsSafeForConcurrentFirstAccess() throws Exception {
        MediaObjectService service = createService(4);
        Path mediaFile = createMediaFile("race.mp4");
        MediaObjectUpsertRequest request = sampleUpsert(mediaFile);

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            var futures = executor.invokeAll(List.<Callable<MediaObject>>of(
                () -> service.upsertPrimaryPath(request),
                () -> service.upsertPrimaryPath(request),
                () -> service.upsertPrimaryPath(request),
                () -> service.upsertPrimaryPath(request),
                () -> service.upsertPrimaryPath(request),
                () -> service.upsertPrimaryPath(request),
                () -> service.upsertPrimaryPath(request),
                () -> service.upsertPrimaryPath(request)
            ));
            List<MediaObject> results = new ArrayList<>(futures.size());
            for (var future : futures) {
                results.add(future.get());
            }

            Set<String> objectIds = results.stream().map(result -> result.objectId()).collect(java.util.stream.Collectors.toSet());
            assertEquals(1, objectIds.size());
            assertEquals(objectIds.iterator().next(), service.getByPath(mediaFile.toString()).objectId());
            assertEquals(1, service.listPaths(objectIds.iterator().next()).size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void upsertPrimaryPathReplacementIsSafeUnderConcurrentDetection() throws Exception {
        MediaObjectService service = createService(4);
        Path mediaFile = createMediaFile("replacement-race.mp4");
        var first = service.upsertPrimaryPath(sampleUpsert(mediaFile));
        MediaObjectUpsertRequest replacementRequest = copyUpsert(
            sampleUpsert(mediaFile),
            null,
            null,
            first.sizeBytes() + 2_048L,
            "2026-04-10T12:40:00Z",
            null,
            null
        );

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            var futures = executor.invokeAll(List.<Callable<MediaObject>>of(
                () -> service.upsertPrimaryPath(replacementRequest),
                () -> service.upsertPrimaryPath(replacementRequest),
                () -> service.upsertPrimaryPath(replacementRequest),
                () -> service.upsertPrimaryPath(replacementRequest)
            ));
            List<MediaObject> results = new ArrayList<>(futures.size());
            for (var future : futures) {
                results.add(future.get());
            }

            Set<String> replacementObjectIds = results.stream()
                .map(result -> result.objectId())
                .collect(java.util.stream.Collectors.toSet());
            var currentByPath = service.getByPath(mediaFile.toString());
            int mediaObjectCount;
            try (var connection = dataSources.getLast().getConnection();
                 var statement = connection.createStatement();
                 var resultSet = statement.executeQuery("SELECT COUNT(*) AS count FROM media_objects")) {
                resultSet.next();
                mediaObjectCount = resultSet.getInt("count");
            }

            assertEquals(1, replacementObjectIds.size());
            assertNotEquals(first.objectId(), replacementObjectIds.iterator().next());
            assertEquals(replacementObjectIds.iterator().next(), currentByPath.objectId());
            assertEquals(2, mediaObjectCount);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void v4MigrationReconcilesCanonicalRowsAfterDemotingDuplicatePrimaryPaths() throws Exception {
        Path dbDir = Files.createDirectories(tempDir.resolve("migration-db"));
        Path dbPath = dbDir.resolve("media_objects.db");

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dbPath + "?journal_mode=WAL&foreign_keys=true");
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(1);
        config.setConnectionInitSql("PRAGMA busy_timeout=5000");
        HikariDataSource dataSource = new HikariDataSource(config);
        dataSources.add(dataSource);

        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/media_objects")
            .target("3")
            .load()
            .migrate();

        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.executeUpdate(
                """
                INSERT INTO media_objects (
                    object_id, media_kind, primary_path, path_key, mime_type, size_bytes, modified_at,
                    hash_algorithm, content_hash, display_name, duration_millis, width, height, channels,
                    taken_at, embedded_title, embedded_artist, embedded_album, discovered_at, last_seen_at, status
                ) VALUES
                    (
                        'winner', 'VIDEO', '/library/stale-winner.mp4', '/library/stale-winner.mp4',
                        'video/mp4', 1024, '2026-04-10T12:00:00Z', 'NONE', NULL, 'Winner',
                        NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
                        '2026-04-10T12:00:00Z', '2026-04-10T12:00:00Z', 'ACTIVE'
                    ),
                    (
                        'loser', 'VIDEO', '/library/stale-loser.mp4', '/library/stale-loser.mp4',
                        'video/mp4', 2048, '2026-04-10T12:00:00Z', 'NONE', NULL, 'Loser',
                        NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
                        '2026-04-10T12:00:00Z', '2026-04-10T12:00:00Z', 'ACTIVE'
                    )
                """
            );
            statement.executeUpdate(
                """
                INSERT INTO media_object_paths (
                    object_id, path, path_kind, first_seen_at, last_seen_at
                ) VALUES
                    ('winner', '/library/shared.mp4', 'PRIMARY', '2026-04-10T12:00:00Z', '2026-04-10T12:00:00Z'),
                    ('loser', '/library/shared.mp4', 'PRIMARY', '2026-04-10T12:01:00Z', '2026-04-10T12:01:00Z')
                """
            );
        }

        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/media_objects")
            .load()
            .migrate();

        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            Map<String, List<String>> objects = new HashMap<>();
            try (var resultSet = statement.executeQuery(
                "SELECT object_id, primary_path, path_key, status FROM media_objects ORDER BY object_id"
            )) {
                while (resultSet.next()) {
                    objects.put(
                        resultSet.getString("object_id"),
                        List.of(
                            resultSet.getString("primary_path"),
                            resultSet.getString("path_key"),
                            resultSet.getString("status")
                        )
                    );
                }
            }

            Map<String, List<String>> pathKinds = new HashMap<>();
            try (var resultSet = statement.executeQuery(
                "SELECT object_id, path_kind FROM media_object_paths ORDER BY object_id, path"
            )) {
                while (resultSet.next()) {
                    pathKinds.computeIfAbsent(resultSet.getString("object_id"), key -> new ArrayList<>())
                        .add(resultSet.getString("path_kind"));
                }
            }

            assertEquals(List.of("/library/shared.mp4", "/library/shared.mp4", "ACTIVE"), objects.get("loser"));
            assertEquals(List.of("/library/shared.mp4", "/library/shared.mp4", "MISSING"), objects.get("winner"));
            assertEquals(List.of("PRIMARY"), pathKinds.get("loser"));
            assertEquals(List.of("HISTORICAL"), pathKinds.get("winner"));
        }
    }
}
