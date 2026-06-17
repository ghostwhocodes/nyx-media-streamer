package com.nyx.media;

import static com.nyx.common.SqliteWriteTransactions.sqliteWriteTransaction;
import static com.nyx.common.SqliteWriteTransactions.withHandleUnchecked;

import com.nyx.common.DatabaseFactory;
import com.nyx.common.DatabaseResources;
import com.nyx.common.MediaTypes;
import com.nyx.config.DatabaseConfig;
import com.nyx.media.contracts.MediaKind;
import com.nyx.media.contracts.MediaObject;
import com.nyx.media.contracts.MediaObjectContracts;
import com.nyx.media.contracts.MediaObjectPath;
import com.nyx.media.contracts.MediaObjectPathKind;
import com.nyx.media.contracts.MediaObjectStatus;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

public final class MediaObjectService {
    private final Jdbi jdbi;
    private final Clock clock;
    private final ConcurrentHashMap<String, ReentrantLock> pathLocks = new ConcurrentHashMap<>();

    public MediaObjectService(Jdbi jdbi) {
        this(jdbi, Clock.systemUTC());
    }

    public MediaObjectService(Jdbi jdbi, Clock clock) {
        this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public MediaObject getByObjectId(String objectId) {
        return withHandleUnchecked(jdbi, handle -> loadMediaObjectById(handle, objectId));
    }

    public MediaObject getByPath(String path) {
        return withHandleUnchecked(jdbi, handle -> loadCurrentPrimaryPathOwner(handle, normalizePathKey(path)));
    }

    public List<MediaObjectPath> listPaths(String objectId) {
        return withHandleUnchecked(jdbi, handle ->
            handle.createQuery(
                """
                SELECT object_id, path, path_kind, first_seen_at, last_seen_at
                FROM media_object_paths
                WHERE object_id = :objectId
                """
            )
                .bind("objectId", objectId)
                .map((resultSet, ignored) -> toMediaObjectPath(resultSet))
                .list()
        );
    }

    public MediaObject upsertPrimaryPath(MediaObjectUpsertRequest request) {
        NormalizedMediaObjectUpsertRequest normalized = normalized(request);
        String now = clock.instant().toString();

        return withPathLock(normalized.pathKey(), () ->
            sqliteWriteTransaction(jdbi, handle -> {
                MediaObject currentOwner = loadCurrentPrimaryPathOwner(handle, normalized.pathKey());
                if (currentOwner == null) {
                    return createOrLoadCurrentOwner(handle, normalized, now);
                }
                if (matchesConcreteFile(currentOwner, normalized)) {
                    return refreshExistingObject(handle, currentOwner.objectId(), normalized, now);
                }
                return rotatePathOwnership(handle, currentOwner, normalized, now);
            })
        );
    }

    public MediaObject markMissing(String objectId) {
        return sqliteWriteTransaction(jdbi, handle -> {
            int updated = handle.createUpdate(
                """
                UPDATE media_objects
                SET status = :status
                WHERE object_id = :objectId
                """
            )
                .bind("status", MediaObjectStatus.MISSING.name())
                .bind("objectId", objectId)
                .execute();
            return updated == 0 ? null : loadMediaObjectById(handle, objectId);
        });
    }

    private MediaObject createOrLoadCurrentOwner(
        Handle handle,
        NormalizedMediaObjectUpsertRequest normalized,
        String now
    ) {
        String pendingObjectId = UUID.randomUUID().toString();
        insertMediaObjectIgnore(handle, pendingObjectId, normalized, now, now);
        insertPrimaryPathIgnore(handle, pendingObjectId, normalized.primaryPath(), now);

        MediaObject currentOwner = loadCurrentPrimaryPathOwner(handle, normalized.pathKey());
        if (currentOwner == null) {
            throw new IllegalStateException(
                "Media object missing after primary path create for '" + normalized.pathKey() + "'"
            );
        }
        if (!currentOwner.objectId().equals(pendingObjectId)) {
            handle.createUpdate("DELETE FROM media_objects WHERE object_id = :objectId")
                .bind("objectId", pendingObjectId)
                .execute();
            return refreshExistingObject(handle, currentOwner.objectId(), normalized, now);
        }
        return refreshExistingObject(handle, pendingObjectId, normalized, now);
    }

    private MediaObject rotatePathOwnership(
        Handle handle,
        MediaObject currentOwner,
        NormalizedMediaObjectUpsertRequest normalized,
        String now
    ) {
        demotePrimaryPath(handle, currentOwner.objectId(), normalized.pathKey());
        handle.createUpdate(
            """
            UPDATE media_objects
            SET status = :status
            WHERE object_id = :objectId
            """
        )
            .bind("status", MediaObjectStatus.MISSING.name())
            .bind("objectId", currentOwner.objectId())
            .execute();

        return createOrLoadCurrentOwner(handle, normalized, now);
    }

    private MediaObject refreshExistingObject(
        Handle handle,
        String objectId,
        NormalizedMediaObjectUpsertRequest normalized,
        String now
    ) {
        MediaObject persisted = loadMediaObjectById(handle, objectId);
        if (persisted == null) {
            throw new IllegalStateException("Media object '" + objectId + "' missing before refresh");
        }
        handle.createUpdate(
            """
            UPDATE media_objects
            SET media_kind = :mediaKind,
                primary_path = :primaryPath,
                path_key = :pathKey,
                mime_type = :mimeType,
                size_bytes = :sizeBytes,
                modified_at = :modifiedAt,
                hash_algorithm = :hashAlgorithm,
                content_hash = :contentHash,
                display_name = :displayName,
                duration_millis = :durationMillis,
                width = :width,
                height = :height,
                channels = :channels,
                taken_at = :takenAt,
                embedded_title = :embeddedTitle,
                embedded_artist = :embeddedArtist,
                embedded_album = :embeddedAlbum,
                discovered_at = :discoveredAt,
                last_seen_at = :lastSeenAt,
                status = :status
            WHERE object_id = :objectId
            """
        )
            .bind("mediaKind", normalized.mediaKind().name())
            .bind("primaryPath", normalized.primaryPath())
            .bind("pathKey", normalized.pathKey())
            .bind("mimeType", normalized.mimeType())
            .bind("sizeBytes", normalized.sizeBytes())
            .bind("modifiedAt", normalized.modifiedAt())
            .bind("hashAlgorithm", normalized.hashAlgorithm())
            .bind("contentHash", normalized.contentHash())
            .bind("displayName", normalized.displayName())
            .bind("durationMillis", normalized.durationMillis())
            .bind("width", normalized.width())
            .bind("height", normalized.height())
            .bind("channels", normalized.channels())
            .bind("takenAt", normalized.takenAt())
            .bind("embeddedTitle", normalized.embeddedTitle())
            .bind("embeddedArtist", normalized.embeddedArtist())
            .bind("embeddedAlbum", normalized.embeddedAlbum())
            .bind("discoveredAt", persisted.discoveredAt())
            .bind("lastSeenAt", now)
            .bind("status", normalized.status().name())
            .bind("objectId", objectId)
            .execute();

        ensurePrimaryPath(handle, objectId, normalized.primaryPath(), now);

        MediaObject updated = loadMediaObjectById(handle, objectId);
        if (updated == null) {
            throw new IllegalStateException("Media object '" + objectId + "' missing after refresh");
        }
        return updated;
    }

    private void insertMediaObjectIgnore(
        Handle handle,
        String objectId,
        NormalizedMediaObjectUpsertRequest normalized,
        String discoveredAt,
        String lastSeenAt
    ) {
        handle.createUpdate(
            """
            INSERT OR IGNORE INTO media_objects(
                object_id, media_kind, primary_path, path_key, mime_type, size_bytes,
                modified_at, hash_algorithm, content_hash, display_name, duration_millis,
                width, height, channels, taken_at, embedded_title, embedded_artist,
                embedded_album, discovered_at, last_seen_at, status
            ) VALUES (
                :objectId, :mediaKind, :primaryPath, :pathKey, :mimeType, :sizeBytes,
                :modifiedAt, :hashAlgorithm, :contentHash, :displayName, :durationMillis,
                :width, :height, :channels, :takenAt, :embeddedTitle, :embeddedArtist,
                :embeddedAlbum, :discoveredAt, :lastSeenAt, :status
            )
            """
        )
            .bind("objectId", objectId)
            .bind("mediaKind", normalized.mediaKind().name())
            .bind("primaryPath", normalized.primaryPath())
            .bind("pathKey", normalized.pathKey())
            .bind("mimeType", normalized.mimeType())
            .bind("sizeBytes", normalized.sizeBytes())
            .bind("modifiedAt", normalized.modifiedAt())
            .bind("hashAlgorithm", normalized.hashAlgorithm())
            .bind("contentHash", normalized.contentHash())
            .bind("displayName", normalized.displayName())
            .bind("durationMillis", normalized.durationMillis())
            .bind("width", normalized.width())
            .bind("height", normalized.height())
            .bind("channels", normalized.channels())
            .bind("takenAt", normalized.takenAt())
            .bind("embeddedTitle", normalized.embeddedTitle())
            .bind("embeddedArtist", normalized.embeddedArtist())
            .bind("embeddedAlbum", normalized.embeddedAlbum())
            .bind("discoveredAt", discoveredAt)
            .bind("lastSeenAt", lastSeenAt)
            .bind("status", normalized.status().name())
            .execute();
    }

    private void insertPrimaryPathIgnore(Handle handle, String objectId, String path, String seenAt) {
        handle.createUpdate(
            """
            INSERT OR IGNORE INTO media_object_paths(
                object_id, path, path_kind, first_seen_at, last_seen_at
            ) VALUES (
                :objectId, :path, :pathKind, :firstSeenAt, :lastSeenAt
            )
            """
        )
            .bind("objectId", objectId)
            .bind("path", path)
            .bind("pathKind", MediaObjectPathKind.PRIMARY.name())
            .bind("firstSeenAt", seenAt)
            .bind("lastSeenAt", seenAt)
            .execute();
    }

    private void ensurePrimaryPath(Handle handle, String objectId, String path, String seenAt) {
        insertPrimaryPathIgnore(handle, objectId, path, seenAt);
        handle.createUpdate(
            """
            UPDATE media_object_paths
            SET path_kind = :pathKind,
                last_seen_at = :lastSeenAt
            WHERE object_id = :objectId
              AND path = :path
            """
        )
            .bind("pathKind", MediaObjectPathKind.PRIMARY.name())
            .bind("lastSeenAt", seenAt)
            .bind("objectId", objectId)
            .bind("path", path)
            .execute();
    }

    private void demotePrimaryPath(Handle handle, String objectId, String path) {
        handle.createUpdate(
            """
            UPDATE media_object_paths
            SET path_kind = :historicalKind
            WHERE object_id = :objectId
              AND path = :path
              AND path_kind = :primaryKind
            """
        )
            .bind("historicalKind", MediaObjectPathKind.HISTORICAL.name())
            .bind("primaryKind", MediaObjectPathKind.PRIMARY.name())
            .bind("objectId", objectId)
            .bind("path", path)
            .execute();
    }

    private <T> T withPathLock(String pathKey, Supplier<T> block) {
        ReentrantLock lock = pathLocks.computeIfAbsent(pathKey, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return block.get();
        } finally {
            lock.unlock();
        }
    }

    private MediaObject loadMediaObjectById(Handle handle, String objectId) {
        return handle.createQuery(
            """
            SELECT object_id, media_kind, primary_path, path_key, mime_type, size_bytes,
                   modified_at, hash_algorithm, content_hash, display_name, duration_millis,
                   width, height, channels, taken_at, embedded_title, embedded_artist,
                   embedded_album, discovered_at, last_seen_at, status
            FROM media_objects
            WHERE object_id = :objectId
            """
        )
            .bind("objectId", objectId)
            .map((resultSet, ignored) -> toMediaObject(resultSet))
            .findOne()
            .orElse(null);
    }

    private MediaObject loadCurrentPrimaryPathOwner(Handle handle, String pathKey) {
        return handle.createQuery(
            """
            SELECT mo.object_id, mo.media_kind, mo.primary_path, mo.path_key, mo.mime_type, mo.size_bytes,
                   mo.modified_at, mo.hash_algorithm, mo.content_hash, mo.display_name, mo.duration_millis,
                   mo.width, mo.height, mo.channels, mo.taken_at, mo.embedded_title, mo.embedded_artist,
                   mo.embedded_album, mo.discovered_at, mo.last_seen_at, mo.status
            FROM media_object_paths mop
            INNER JOIN media_objects mo ON mo.object_id = mop.object_id
            WHERE mop.path = :path
              AND mop.path_kind = :pathKind
            LIMIT 1
            """
        )
            .bind("path", pathKey)
            .bind("pathKind", MediaObjectPathKind.PRIMARY.name())
            .map((resultSet, ignored) -> toMediaObject(resultSet))
            .findOne()
            .orElse(null);
    }

    private MediaObject toMediaObject(ResultSet resultSet) {
        return new MediaObject(
            getString(resultSet, "object_id"),
            MediaKind.valueOf(getString(resultSet, "media_kind")),
            getString(resultSet, "primary_path"),
            getString(resultSet, "path_key"),
            getString(resultSet, "mime_type"),
            getLong(resultSet, "size_bytes"),
            getString(resultSet, "modified_at"),
            getString(resultSet, "hash_algorithm"),
            getString(resultSet, "content_hash"),
            getString(resultSet, "display_name"),
            getNullableLong(resultSet, "duration_millis"),
            getNullableInt(resultSet, "width"),
            getNullableInt(resultSet, "height"),
            getNullableInt(resultSet, "channels"),
            getString(resultSet, "taken_at"),
            getString(resultSet, "embedded_title"),
            getString(resultSet, "embedded_artist"),
            getString(resultSet, "embedded_album"),
            getString(resultSet, "discovered_at"),
            getString(resultSet, "last_seen_at"),
            MediaObjectStatus.valueOf(getString(resultSet, "status"))
        );
    }

    private MediaObjectPath toMediaObjectPath(ResultSet resultSet) {
        return new MediaObjectPath(
            getString(resultSet, "object_id"),
            getString(resultSet, "path"),
            MediaObjectPathKind.valueOf(getString(resultSet, "path_kind")),
            getString(resultSet, "first_seen_at"),
            getString(resultSet, "last_seen_at")
        );
    }

    private static NormalizedMediaObjectUpsertRequest normalized(MediaObjectUpsertRequest request) {
        Objects.requireNonNull(request, "request");
        String normalizedPath = normalizePathKey(request.getPrimaryPath());
        String normalizedDisplayName = request.getDisplayName().trim().isBlank()
            ? Path.of(normalizedPath).getFileName() == null ? normalizedPath : Path.of(normalizedPath).getFileName().toString()
            : request.getDisplayName().trim();
        if (request.getSizeBytes() < 0L) {
            throw new IllegalArgumentException("sizeBytes must be non-negative");
        }
        if (request.getModifiedAt().isBlank()) {
            throw new IllegalArgumentException("modifiedAt must not be blank");
        }

        return new NormalizedMediaObjectUpsertRequest(
            request.getMediaKind(),
            normalizedPath,
            normalizedPath,
            request.getMimeType().trim().isBlank() ? MediaTypes.APPLICATION_OCTET_STREAM : request.getMimeType().trim(),
            request.getSizeBytes(),
            request.getModifiedAt().trim(),
            normalizedDisplayName,
            request.getDurationMillis(),
            request.getWidth(),
            request.getHeight(),
            request.getChannels(),
            normalizeOptionalText(request.getTakenAt()),
            normalizeOptionalText(request.getEmbeddedTitle()),
            normalizeOptionalText(request.getEmbeddedArtist()),
            normalizeOptionalText(request.getEmbeddedAlbum()),
            request.getHashAlgorithm().trim().isBlank()
                ? MediaObjectContracts.MEDIA_OBJECT_HASH_ALGORITHM_NONE
                : request.getHashAlgorithm().trim(),
            normalizeOptionalText(request.getContentHash()),
            request.getStatus()
        );
    }

    private static boolean matchesConcreteFile(MediaObject existingObject, NormalizedMediaObjectUpsertRequest request) {
        MediaObjectIdentitySignature existing = new MediaObjectIdentitySignature(
            existingObject.mediaKind(),
            existingObject.hashAlgorithm(),
            existingObject.contentHash(),
            existingObject.sizeBytes(),
            existingObject.modifiedAt()
        );
        MediaObjectIdentitySignature incoming = new MediaObjectIdentitySignature(
            request.mediaKind(),
            request.hashAlgorithm(),
            request.contentHash(),
            request.sizeBytes(),
            request.modifiedAt()
        );

        if (existing.mediaKind() != incoming.mediaKind()) {
            return false;
        }
        if (existing.hasMeaningfulHash() && incoming.hasMeaningfulHash()) {
            return existing.hashAlgorithm().equals(incoming.hashAlgorithm())
                && Objects.equals(existing.contentHash(), incoming.contentHash());
        }
        return existing.sizeBytes() == incoming.sizeBytes()
            && existing.modifiedAt().equals(incoming.modifiedAt());
    }

    public static DatabaseResources createDatabase(Path dbDir) {
        return createDatabase(dbDir, new DatabaseConfig(dbDir, 1));
    }

    public static DatabaseResources createDatabase(Path dbDir, DatabaseConfig dbConfig) {
        return DatabaseFactory.create(dbDir, "media_objects", dbConfig);
    }

    public static String normalizePathKey(String path) {
        return Path.of(path.trim()).normalize().toAbsolutePath().toString();
    }

    private static String normalizeOptionalText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static String getString(ResultSet resultSet, String columnLabel) {
        try {
            return resultSet.getString(columnLabel);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read column '" + columnLabel + "'", exception);
        }
    }

    private static long getLong(ResultSet resultSet, String columnLabel) {
        try {
            return resultSet.getLong(columnLabel);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read column '" + columnLabel + "'", exception);
        }
    }

    private static Integer getNullableInt(ResultSet resultSet, String columnLabel) {
        try {
            Object value = resultSet.getObject(columnLabel);
            return value == null ? null : ((Number) value).intValue();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read column '" + columnLabel + "'", exception);
        }
    }

    private static Long getNullableLong(ResultSet resultSet, String columnLabel) {
        try {
            Object value = resultSet.getObject(columnLabel);
            return value == null ? null : ((Number) value).longValue();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read column '" + columnLabel + "'", exception);
        }
    }

    private record NormalizedMediaObjectUpsertRequest(
        MediaKind mediaKind,
        String primaryPath,
        String pathKey,
        String mimeType,
        long sizeBytes,
        String modifiedAt,
        String displayName,
        Long durationMillis,
        Integer width,
        Integer height,
        Integer channels,
        String takenAt,
        String embeddedTitle,
        String embeddedArtist,
        String embeddedAlbum,
        String hashAlgorithm,
        String contentHash,
        MediaObjectStatus status
    ) {
    }

    private record MediaObjectIdentitySignature(
        MediaKind mediaKind,
        String hashAlgorithm,
        String contentHash,
        long sizeBytes,
        String modifiedAt
    ) {
        private boolean hasMeaningfulHash() {
            return contentHash != null
                && !MediaObjectContracts.MEDIA_OBJECT_HASH_ALGORITHM_NONE.equals(hashAlgorithm);
        }
    }
}
