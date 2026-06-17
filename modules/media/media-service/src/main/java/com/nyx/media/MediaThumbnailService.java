package com.nyx.media;

import static com.nyx.common.SqliteWriteTransactions.sqliteWriteTransaction;
import static com.nyx.common.SqliteWriteTransactions.withHandleUnchecked;

import com.nyx.media.contracts.MediaKind;
import com.nyx.media.contracts.MediaObject;
import com.nyx.media.contracts.MediaThumbnail;
import com.nyx.media.contracts.MediaThumbnailKind;
import com.nyx.media.contracts.MediaThumbnailReference;
import com.nyx.media.contracts.MediaThumbnailStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

public final class MediaThumbnailService implements MediaThumbnailPersistence {
    private static final String DEFAULT_FORMAT = "jpeg";

    private final Jdbi jdbi;
    private final Clock clock;

    public MediaThumbnailService(Jdbi jdbi) {
        this(jdbi, Clock.systemUTC());
    }

    public MediaThumbnailService(Jdbi jdbi, Clock clock) {
        this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public MediaThumbnail ensurePlaceholder(MediaObject mediaObject) {
        return sqliteWriteTransaction(jdbi, handle -> {
            String now = Instant.now(clock).toString();
            MediaThumbnail thumbnail = new MediaThumbnail(
                UUID.randomUUID().toString(),
                mediaObject.objectId(),
                MediaThumbnailKind.PLACEHOLDER,
                null,
                null,
                DEFAULT_FORMAT,
                buildPlaceholderStorageKey(mediaObject.objectId()),
                null,
                true,
                defaultPlaceholderStatus(mediaObject.mediaKind()),
                now,
                now
            );

            insertPrimaryThumbnailIgnore(handle, thumbnail);
            MediaThumbnail persisted = loadPrimaryThumbnail(handle, mediaObject.objectId());
            if (persisted == null) {
                throw new IllegalStateException(
                    "Primary thumbnail for '" + mediaObject.objectId() + "' missing after placeholder ensure"
                );
            }
            return persisted;
        });
    }

    public MediaThumbnail getPrimaryThumbnail(String objectId) {
        return withHandleUnchecked(jdbi, handle -> loadPrimaryThumbnail(handle, objectId));
    }

    @Override
    public MediaThumbnailReference primaryThumbnailReference(String objectId, String url) {
        MediaThumbnail thumbnail = getPrimaryThumbnail(objectId);
        return thumbnail == null ? null : toReference(thumbnail, url);
    }

    public MediaThumbnailReference primaryThumbnailReference(String objectId) {
        return primaryThumbnailReference(objectId, null);
    }

    @Override
    public MediaThumbnail markReady(
        String objectId,
        String storageKey,
        Integer width,
        Integer height,
        String format,
        MediaThumbnailKind kind,
        Long sourcePositionMillis
    ) {
        return sqliteWriteTransaction(jdbi, handle -> {
            String now = Instant.now(clock).toString();
            insertPrimaryThumbnailIgnore(
                handle,
                new MediaThumbnail(
                    UUID.randomUUID().toString(),
                    objectId,
                    kind,
                    width,
                    height,
                    format,
                    storageKey,
                    sourcePositionMillis,
                    true,
                    MediaThumbnailStatus.READY,
                    now,
                    now
                )
            );

            MediaThumbnail existing = loadPrimaryThumbnail(handle, objectId);
            if (existing == null) {
                throw new IllegalStateException("Primary thumbnail for '" + objectId + "' missing before ready update");
            }

            handle.createUpdate(
                """
                UPDATE media_thumbnails
                SET kind = :kind,
                    width = :width,
                    height = :height,
                    format = :format,
                    storage_key = :storageKey,
                    source_position_millis = :sourcePositionMillis,
                    is_primary = :isPrimary,
                    status = :status,
                    updated_at = :updatedAt
                WHERE thumbnail_id = :thumbnailId
                """
            )
                .bind("kind", kind.name())
                .bind("width", width)
                .bind("height", height)
                .bind("format", format)
                .bind("storageKey", storageKey)
                .bind("sourcePositionMillis", sourcePositionMillis)
                .bind("isPrimary", true)
                .bind("status", MediaThumbnailStatus.READY.name())
                .bind("updatedAt", now)
                .bind("thumbnailId", existing.thumbnailId())
                .execute();

            MediaThumbnail updated = loadPrimaryThumbnail(handle, objectId);
            if (updated == null) {
                throw new IllegalStateException("Primary thumbnail for '" + objectId + "' missing after ready update");
            }
            return updated;
        });
    }

    public MediaThumbnail markReady(String objectId, String storageKey) {
        return MediaThumbnailPersistence.super.markReady(objectId, storageKey);
    }

    public MediaThumbnail markReady(String objectId, String storageKey, Integer width) {
        return MediaThumbnailPersistence.super.markReady(objectId, storageKey, width);
    }

    public MediaThumbnail markReady(String objectId, String storageKey, Integer width, Integer height) {
        return MediaThumbnailPersistence.super.markReady(objectId, storageKey, width, height);
    }

    @Override
    public MediaThumbnail markFailed(
        String objectId,
        String storageKey,
        MediaThumbnailKind kind,
        String format
    ) {
        return sqliteWriteTransaction(jdbi, handle -> {
            String now = Instant.now(clock).toString();
            insertPrimaryThumbnailIgnore(
                handle,
                new MediaThumbnail(
                    UUID.randomUUID().toString(),
                    objectId,
                    kind,
                    null,
                    null,
                    format,
                    storageKey,
                    null,
                    true,
                    MediaThumbnailStatus.FAILED,
                    now,
                    now
                )
            );

            MediaThumbnail existing = loadPrimaryThumbnail(handle, objectId);
            if (existing == null) {
                throw new IllegalStateException("Primary thumbnail for '" + objectId + "' missing before failure update");
            }
            if (existing.status() == MediaThumbnailStatus.READY && !Objects.equals(existing.storageKey(), storageKey)) {
                return existing;
            }

            handle.createUpdate(
                """
                UPDATE media_thumbnails
                SET kind = :kind,
                    format = :format,
                    storage_key = :storageKey,
                    is_primary = :isPrimary,
                    status = :status,
                    updated_at = :updatedAt
                WHERE thumbnail_id = :thumbnailId
                """
            )
                .bind("kind", kind.name())
                .bind("format", format)
                .bind("storageKey", storageKey)
                .bind("isPrimary", true)
                .bind("status", MediaThumbnailStatus.FAILED.name())
                .bind("updatedAt", now)
                .bind("thumbnailId", existing.thumbnailId())
                .execute();

            MediaThumbnail updated = loadPrimaryThumbnail(handle, objectId);
            if (updated == null) {
                throw new IllegalStateException("Primary thumbnail for '" + objectId + "' missing after failure update");
            }
            return updated;
        });
    }

    public MediaThumbnail markFailed(String objectId, String storageKey) {
        return MediaThumbnailPersistence.super.markFailed(objectId, storageKey);
    }

    public MediaThumbnail ensurePlaceholderBlocking(MediaObject mediaObject) {
        return ensurePlaceholder(mediaObject);
    }

    public MediaThumbnailReference primaryThumbnailReferenceBlocking(String objectId) {
        return primaryThumbnailReference(objectId, null);
    }

    public MediaThumbnailReference primaryThumbnailReferenceBlocking(String objectId, String url) {
        return primaryThumbnailReference(objectId, url);
    }

    public static String buildStorageKey(String objectId, int size) {
        return "thumbnails/objects/" + objectId + "/" + size + ".jpg";
    }

    public static String buildPlaceholderStorageKey(String objectId) {
        return "thumbnails/objects/" + objectId + "/placeholder";
    }

    public static boolean supportsGeneratedPrimaryThumbnail(MediaKind mediaKind) {
        return mediaKind == MediaKind.IMAGE || mediaKind == MediaKind.VIDEO;
    }

    private static MediaThumbnailStatus defaultPlaceholderStatus(MediaKind mediaKind) {
        return supportsGeneratedPrimaryThumbnail(mediaKind)
            ? MediaThumbnailStatus.PENDING
            : MediaThumbnailStatus.MISSING;
    }

    private static MediaThumbnail loadPrimaryThumbnail(Handle handle, String objectId) {
        return handle.createQuery(
            """
            SELECT thumbnail_id, object_id, kind, width, height, format, storage_key,
                   source_position_millis, is_primary, status, created_at, updated_at
            FROM media_thumbnails
            WHERE object_id = :objectId
              AND is_primary = :isPrimary
            LIMIT 1
            """
        )
            .bind("objectId", objectId)
            .bind("isPrimary", true)
            .map((resultSet, context) -> toMediaThumbnail(resultSet))
            .findOne()
            .orElse(null);
    }

    private static void insertPrimaryThumbnailIgnore(Handle handle, MediaThumbnail thumbnail) {
        handle.createUpdate(
            """
            INSERT OR IGNORE INTO media_thumbnails(
                thumbnail_id, object_id, kind, width, height, format, storage_key,
                source_position_millis, is_primary, status, created_at, updated_at
            ) VALUES (
                :thumbnailId, :objectId, :kind, :width, :height, :format, :storageKey,
                :sourcePositionMillis, :isPrimary, :status, :createdAt, :updatedAt
            )
            """
        )
            .bind("thumbnailId", thumbnail.thumbnailId())
            .bind("objectId", thumbnail.objectId())
            .bind("kind", thumbnail.kind().name())
            .bind("width", thumbnail.width())
            .bind("height", thumbnail.height())
            .bind("format", thumbnail.format())
            .bind("storageKey", thumbnail.storageKey())
            .bind("sourcePositionMillis", thumbnail.sourcePositionMillis())
            .bind("isPrimary", thumbnail.isPrimary())
            .bind("status", thumbnail.status().name())
            .bind("createdAt", thumbnail.createdAt())
            .bind("updatedAt", thumbnail.updatedAt())
            .execute();
    }

    private static MediaThumbnail toMediaThumbnail(ResultSet resultSet) throws SQLException {
        return new MediaThumbnail(
            resultSet.getString("thumbnail_id"),
            resultSet.getString("object_id"),
            MediaThumbnailKind.valueOf(resultSet.getString("kind")),
            getNullableInt(resultSet, "width"),
            getNullableInt(resultSet, "height"),
            resultSet.getString("format"),
            resultSet.getString("storage_key"),
            getNullableLong(resultSet, "source_position_millis"),
            resultSet.getBoolean("is_primary"),
            MediaThumbnailStatus.valueOf(resultSet.getString("status")),
            resultSet.getString("created_at"),
            resultSet.getString("updated_at")
        );
    }

    private static MediaThumbnailReference toReference(MediaThumbnail thumbnail, String url) {
        return new MediaThumbnailReference(
            thumbnail.thumbnailId(),
            thumbnail.kind(),
            thumbnail.status(),
            url,
            thumbnail.width(),
            thumbnail.height(),
            thumbnail.format()
        );
    }

    private static Integer getNullableInt(ResultSet resultSet, String columnLabel) throws SQLException {
        Object value = resultSet.getObject(columnLabel);
        return value == null ? null : ((Number) value).intValue();
    }

    private static Long getNullableLong(ResultSet resultSet, String columnLabel) throws SQLException {
        Object value = resultSet.getObject(columnLabel);
        return value == null ? null : ((Number) value).longValue();
    }
}
