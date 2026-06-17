package com.nyx.media;

import static com.nyx.common.RouteUtilsJava.pageEndIndex;
import static com.nyx.common.RouteUtilsJava.pageStartIndex;
import static com.nyx.common.SqliteWriteTransactions.sqliteWriteTransaction;
import static com.nyx.common.SqliteWriteTransactions.withHandleUnchecked;

import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.media.contracts.MediaKind;
import com.nyx.media.contracts.MediaObjectPathKind;
import com.nyx.media.contracts.MediaObjectStatus;
import com.nyx.media.contracts.MediaThumbnailKind;
import com.nyx.media.contracts.MediaThumbnailReference;
import com.nyx.media.contracts.MediaThumbnailStatus;
import com.nyx.media.contracts.UserMediaState;
import com.nyx.media.contracts.UserMediaStateEntry;
import com.nyx.media.contracts.UserMediaStateListing;
import com.nyx.media.contracts.UserMediaStateMediaSummary;
import com.nyx.media.contracts.UserMediaStateWriteRequest;
import com.nyx.playback.contracts.MediaPlaystateProjection;
import com.nyx.playback.contracts.MediaPlaystateProjector;
import com.nyx.playback.contracts.MediaSessionPlaybackReport;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.Query;

public final class UserMediaStateService implements MediaPlaystateProjector {
    private final Jdbi jdbi;
    private final Clock clock;

    public UserMediaStateService(Jdbi jdbi) {
        this(jdbi, Clock.systemUTC());
    }

    public UserMediaStateService(Jdbi jdbi, Clock clock) {
        this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public UserMediaState getState(String userId, String objectId) {
        return withHandleUnchecked(jdbi, handle -> {
            requireUserId(userId);
            requireObjectId(objectId);
            UserMediaState state = loadState(handle, userId, objectId);
            return state != null ? state : new UserMediaState(userId, objectId);
        });
    }

    public UserMediaState putState(
        String userId,
        String objectId,
        UserMediaStateWriteRequest request
    ) {
        return sqliteWriteTransaction(jdbi, handle -> {
            requireUserId(userId);
            requireObjectId(objectId);
            Objects.requireNonNull(request, "request");
            validateRequest(request);

            UserMediaState existing = loadState(handle, userId, objectId);
            String now = Instant.now(clock).toString();
            boolean watched = request.watched();
            Long resumePositionMillis = watched ? null : request.resumePositionMillis();
            String watchedAt;
            if (!watched) {
                watchedAt = null;
            } else if (existing != null && existing.watched()) {
                watchedAt = existing.watchedAt() != null ? existing.watchedAt() : now;
            } else {
                watchedAt = now;
            }
            int existingPlayCount = existing == null ? 0 : existing.playCount();
            int playCount = watched && (existing == null || !existing.watched())
                ? existingPlayCount + 1
                : existingPlayCount;
            String lastPlayedAt = watched || resumePositionMillis != null
                ? now
                : existing == null ? null : existing.lastPlayedAt();

            upsertState(
                handle,
                new UserMediaState(
                    userId,
                    objectId,
                    resumePositionMillis,
                    watched,
                    watchedAt,
                    request.favorite(),
                    request.rating(),
                    playCount,
                    lastPlayedAt,
                    now
                )
            );

            UserMediaState persisted = loadState(handle, userId, objectId);
            if (persisted == null) {
                throw sneakyThrow(new NyxException(
                    ErrorCode.INVALID_REQUEST,
                    "Failed to load user media state after update"
                ));
            }
            return persisted;
        });
    }

    @Override
    public UserMediaState projectPlaybackState(
        String userId,
        String objectId,
        MediaSessionPlaybackReport report
    ) {
        return sqliteWriteTransaction(jdbi, handle -> {
            requireUserId(userId);
            requireObjectId(objectId);
            Objects.requireNonNull(report, "report");
            validatePlaybackReport(report);

            UserMediaState existing = loadState(handle, userId, objectId);
            String occurredAt = report.occurredAt() != null
                ? report.occurredAt()
                : Instant.now(clock).toString();
            UserMediaState projected = MediaPlaystateProjection.projectUserMediaStateFromPlaybackReport(
                existing,
                userId,
                objectId,
                report,
                occurredAt
            );
            if (projected == null) {
                return existing != null ? existing : new UserMediaState(userId, objectId);
            }

            upsertState(handle, projected);

            UserMediaState persisted = loadState(handle, userId, objectId);
            if (persisted == null) {
                throw sneakyThrow(new NyxException(
                    ErrorCode.INVALID_REQUEST,
                    "Failed to load user media state after playback projection"
                ));
            }
            return persisted;
        });
    }

    public UserMediaStateListing listFavorites(String userId) {
        return listFavorites(userId, 1, 50);
    }

    public UserMediaStateListing listFavorites(String userId, int page) {
        return listFavorites(userId, page, 50);
    }

    public UserMediaStateListing listFavorites(String userId, int page, int limit) {
        return withHandleUnchecked(jdbi, handle -> {
            requireUserId(userId);
            List<UserMediaStateListingRow> rows = loadListingRows(
                handle,
                "ums.user_id = :userId AND ums.favorite = :favorite",
                "ums.last_interaction_at DESC",
                query -> {
                    query.bind("userId", userId);
                    query.bind("favorite", true);
                }
            );
            return toListing(handle, rows, page, limit);
        });
    }

    public UserMediaStateListing listContinueWatching(String userId) {
        return listContinueWatching(userId, 1, 50);
    }

    public UserMediaStateListing listContinueWatching(String userId, int page) {
        return listContinueWatching(userId, page, 50);
    }

    public UserMediaStateListing listContinueWatching(String userId, int page, int limit) {
        return withHandleUnchecked(jdbi, handle -> {
            requireUserId(userId);
            List<UserMediaStateListingRow> rows = loadListingRows(
                handle,
                """
                    ums.user_id = :userId
                    AND ums.watched = :watched
                    AND ums.resume_position_millis IS NOT NULL
                """,
                "ums.last_played_at DESC",
                query -> {
                    query.bind("userId", userId);
                    query.bind("watched", false);
                }
            );
            return toListing(handle, rows, page, limit);
        });
    }

    public List<UserMediaState> listStatesForObjects(String userId, Set<String> objectIds) {
        return withHandleUnchecked(jdbi, handle -> {
            requireUserId(userId);
            if (objectIds.isEmpty()) {
                return List.of();
            }
            return handle.createQuery(
                """
                SELECT user_id, object_id, resume_position_millis, watched, watched_at, favorite,
                       rating, play_count, last_played_at, last_interaction_at
                FROM user_media_states
                WHERE user_id = :userId
                  AND object_id IN (<objectIds>)
                """
            )
                .bind("userId", userId)
                .bindList("objectIds", new ArrayList<>(objectIds))
                .map((resultSet, ignored) -> toUserMediaState(resultSet))
                .list();
        });
    }

    private UserMediaState loadState(Handle handle, String userId, String objectId) {
        return handle.createQuery(
            """
            SELECT user_id, object_id, resume_position_millis, watched, watched_at, favorite,
                   rating, play_count, last_played_at, last_interaction_at
            FROM user_media_states
            WHERE user_id = :userId
              AND object_id = :objectId
            """
        )
            .bind("userId", userId)
            .bind("objectId", objectId)
            .map((resultSet, ignored) -> toUserMediaState(resultSet))
            .findOne()
            .orElse(null);
    }

    private void upsertState(Handle handle, UserMediaState state) {
        String lastInteractionAt = state.lastInteractionAt();
        if (lastInteractionAt == null) {
            throw sneakyThrow(new NyxException(
                ErrorCode.INVALID_REQUEST,
                "lastInteractionAt must be present when persisting user media state"
            ));
        }
        handle.createUpdate(
            """
            INSERT INTO user_media_states(
                user_id, object_id, resume_position_millis, watched, watched_at, favorite,
                rating, play_count, last_played_at, last_interaction_at
            ) VALUES (
                :userId, :objectId, :resumePositionMillis, :watched, :watchedAt, :favorite,
                :rating, :playCount, :lastPlayedAt, :lastInteractionAt
            )
            ON CONFLICT(user_id, object_id) DO UPDATE SET
                resume_position_millis = excluded.resume_position_millis,
                watched = excluded.watched,
                watched_at = excluded.watched_at,
                favorite = excluded.favorite,
                rating = excluded.rating,
                play_count = excluded.play_count,
                last_played_at = excluded.last_played_at,
                last_interaction_at = excluded.last_interaction_at
            """
        )
            .bind("userId", state.userId())
            .bind("objectId", state.objectId())
            .bind("resumePositionMillis", state.resumePositionMillis())
            .bind("watched", state.watched())
            .bind("watchedAt", state.watchedAt())
            .bind("favorite", state.favorite())
            .bind("rating", state.rating())
            .bind("playCount", state.playCount())
            .bind("lastPlayedAt", state.lastPlayedAt())
            .bind("lastInteractionAt", lastInteractionAt)
            .execute();
    }

    private UserMediaState toUserMediaState(ResultSet resultSet) {
        return new UserMediaState(
            getString(resultSet, "user_id"),
            getString(resultSet, "object_id"),
            getNullableLong(resultSet, "resume_position_millis"),
            getBoolean(resultSet, "watched"),
            getString(resultSet, "watched_at"),
            getBoolean(resultSet, "favorite"),
            getNullableInt(resultSet, "rating"),
            getInt(resultSet, "play_count"),
            getString(resultSet, "last_played_at"),
            getString(resultSet, "last_interaction_at")
        );
    }

    private Map<String, String> loadCurrentPrimaryPaths(Handle handle, List<String> objectIds) {
        if (objectIds.isEmpty()) {
            return Map.of();
        }
        return handle.createQuery(
            """
            SELECT object_id, path
            FROM media_object_paths
            WHERE object_id IN (<objectIds>)
              AND path_kind = :pathKind
            """
        )
            .bindList("objectIds", objectIds)
            .bind("pathKind", MediaObjectPathKind.PRIMARY.name())
            .map((resultSet, ignored) -> Map.entry(getString(resultSet, "object_id"), getString(resultSet, "path")))
            .list()
            .stream()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Map<String, MediaThumbnailReference> loadPrimaryThumbnailReferences(Handle handle, List<String> objectIds) {
        if (objectIds.isEmpty()) {
            return Map.of();
        }
        return handle.createQuery(
            """
            SELECT object_id, thumbnail_id, kind, status, width, height, format
            FROM media_thumbnails
            WHERE object_id IN (<objectIds>)
              AND is_primary = :isPrimary
            """
        )
            .bindList("objectIds", objectIds)
            .bind("isPrimary", true)
            .map((resultSet, ignored) -> Map.entry(
                getString(resultSet, "object_id"),
                new MediaThumbnailReference(
                    getString(resultSet, "thumbnail_id"),
                    MediaThumbnailKind.valueOf(getString(resultSet, "kind")),
                    MediaThumbnailStatus.valueOf(getString(resultSet, "status")),
                    null,
                    getNullableInt(resultSet, "width"),
                    getNullableInt(resultSet, "height"),
                    getString(resultSet, "format")
                )
            ))
            .list()
            .stream()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private UserMediaStateEntry toEntry(
        UserMediaStateListingRow row,
        Map<String, String> currentPaths,
        Map<String, MediaThumbnailReference> primaryThumbnails
    ) {
        String path = row.mediaStatus() == MediaObjectStatus.ACTIVE
            ? currentPaths.get(row.objectId())
            : null;
        return new UserMediaStateEntry(
            new UserMediaStateMediaSummary(
                row.objectId(),
                row.mediaKind(),
                path,
                row.displayName(),
                row.mimeType(),
                row.sizeBytes(),
                row.modifiedAt(),
                row.durationMillis(),
                row.width(),
                row.height(),
                row.channels(),
                row.takenAt(),
                row.embeddedTitle(),
                row.embeddedArtist(),
                row.embeddedAlbum(),
                primaryThumbnails.get(row.objectId()),
                row.mediaStatus()
            ),
            row.state()
        );
    }

    private List<UserMediaStateListingRow> loadListingRows(
        Handle handle,
        String whereClause,
        String orderByClause,
        Consumer<Query> bindParameters
    ) {
        Query query = handle.createQuery(String.format(
            """
            SELECT
                ums.user_id AS ums_user_id,
                ums.object_id AS ums_object_id,
                ums.resume_position_millis AS ums_resume_position_millis,
                ums.watched AS ums_watched,
                ums.watched_at AS ums_watched_at,
                ums.favorite AS ums_favorite,
                ums.rating AS ums_rating,
                ums.play_count AS ums_play_count,
                ums.last_played_at AS ums_last_played_at,
                ums.last_interaction_at AS ums_last_interaction_at,
                mo.object_id AS mo_object_id,
                mo.media_kind AS mo_media_kind,
                mo.status AS mo_status,
                mo.display_name AS mo_display_name,
                mo.mime_type AS mo_mime_type,
                mo.size_bytes AS mo_size_bytes,
                mo.modified_at AS mo_modified_at,
                mo.duration_millis AS mo_duration_millis,
                mo.width AS mo_width,
                mo.height AS mo_height,
                mo.channels AS mo_channels,
                mo.taken_at AS mo_taken_at,
                mo.embedded_title AS mo_embedded_title,
                mo.embedded_artist AS mo_embedded_artist,
                mo.embedded_album AS mo_embedded_album
            FROM user_media_states ums
            INNER JOIN media_objects mo ON mo.object_id = ums.object_id
            WHERE %s
            ORDER BY %s
            """,
            whereClause,
            orderByClause
        ));
        bindParameters.accept(query);
        return query.map((resultSet, ignored) -> toUserMediaStateListingRow(resultSet)).list();
    }

    private UserMediaStateListingRow toUserMediaStateListingRow(ResultSet resultSet) {
        return new UserMediaStateListingRow(
            new UserMediaState(
                getString(resultSet, "ums_user_id"),
                getString(resultSet, "ums_object_id"),
                getNullableLong(resultSet, "ums_resume_position_millis"),
                getBoolean(resultSet, "ums_watched"),
                getString(resultSet, "ums_watched_at"),
                getBoolean(resultSet, "ums_favorite"),
                getNullableInt(resultSet, "ums_rating"),
                getInt(resultSet, "ums_play_count"),
                getString(resultSet, "ums_last_played_at"),
                getString(resultSet, "ums_last_interaction_at")
            ),
            getString(resultSet, "mo_object_id"),
            MediaKind.valueOf(getString(resultSet, "mo_media_kind")),
            MediaObjectStatus.valueOf(getString(resultSet, "mo_status")),
            getString(resultSet, "mo_display_name"),
            getString(resultSet, "mo_mime_type"),
            getLong(resultSet, "mo_size_bytes"),
            getString(resultSet, "mo_modified_at"),
            getNullableLong(resultSet, "mo_duration_millis"),
            getNullableInt(resultSet, "mo_width"),
            getNullableInt(resultSet, "mo_height"),
            getNullableInt(resultSet, "mo_channels"),
            getString(resultSet, "mo_taken_at"),
            getString(resultSet, "mo_embedded_title"),
            getString(resultSet, "mo_embedded_artist"),
            getString(resultSet, "mo_embedded_album")
        );
    }

    private UserMediaStateListing toListing(
        Handle handle,
        List<UserMediaStateListingRow> rows,
        int page,
        int limit
    ) {
        List<String> objectIds = rows.stream().map(UserMediaStateListingRow::objectId).toList();
        Map<String, String> currentPaths = loadCurrentPrimaryPaths(handle, objectIds);
        Map<String, MediaThumbnailReference> primaryThumbnails = loadPrimaryThumbnailReferences(handle, objectIds);
        List<UserMediaStateEntry> entries = rows.stream()
            .map(row -> toEntry(row, currentPaths, primaryThumbnails))
            .toList();
        return toListing(entries, page, limit);
    }

    private UserMediaStateListing toListing(List<UserMediaStateEntry> entries, int page, int limit) {
        int total = entries.size();
        int start = pageStartIndex(page, limit, total);
        int end = pageEndIndex(start, limit, total);
        return new UserMediaStateListing(entries.subList(start, end), total, page, limit);
    }

    private void requireUserId(String userId) {
        if (userId.isBlank()) {
            throw sneakyThrow(new NyxException(ErrorCode.INVALID_REQUEST, "userId must not be blank"));
        }
    }

    private void requireObjectId(String objectId) {
        if (objectId.isBlank()) {
            throw sneakyThrow(new NyxException(ErrorCode.INVALID_REQUEST, "objectId must not be blank"));
        }
    }

    private void validateRequest(UserMediaStateWriteRequest request) {
        Long resumePositionMillis = request.resumePositionMillis();
        if (resumePositionMillis != null && resumePositionMillis < 0L) {
            throw sneakyThrow(new NyxException(
                ErrorCode.INVALID_REQUEST,
                "resumePositionMillis must be non-negative"
            ));
        }
        Integer rating = request.rating();
        if (rating != null && rating < 0) {
            throw sneakyThrow(new NyxException(
                ErrorCode.INVALID_REQUEST,
                "rating must be non-negative when provided"
            ));
        }
    }

    private void validatePlaybackReport(MediaSessionPlaybackReport report) {
        Long positionMillis = report.positionMillis();
        if (positionMillis != null && positionMillis < 0L) {
            throw sneakyThrow(new NyxException(
                ErrorCode.INVALID_REQUEST,
                "positionMillis must be non-negative when provided"
            ));
        }
        Long durationMillis = report.durationMillis();
        if (durationMillis != null && durationMillis <= 0L) {
            throw sneakyThrow(new NyxException(
                ErrorCode.INVALID_REQUEST,
                "durationMillis must be positive when provided"
            ));
        }
    }

    private static String getString(ResultSet resultSet, String columnLabel) {
        try {
            return resultSet.getString(columnLabel);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read column '" + columnLabel + "'", exception);
        }
    }

    private static boolean getBoolean(ResultSet resultSet, String columnLabel) {
        try {
            return resultSet.getBoolean(columnLabel);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read column '" + columnLabel + "'", exception);
        }
    }

    private static int getInt(ResultSet resultSet, String columnLabel) {
        try {
            return resultSet.getInt(columnLabel);
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

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }

    private record UserMediaStateListingRow(
        UserMediaState state,
        String objectId,
        MediaKind mediaKind,
        MediaObjectStatus mediaStatus,
        String displayName,
        String mimeType,
        long sizeBytes,
        String modifiedAt,
        Long durationMillis,
        Integer width,
        Integer height,
        Integer channels,
        String takenAt,
        String embeddedTitle,
        String embeddedArtist,
        String embeddedAlbum
    ) {
    }
}
