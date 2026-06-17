package com.nyx.media;

import static com.nyx.common.SqliteWriteTransactions.sqliteWriteTransaction;
import static com.nyx.common.SqliteWriteTransactions.withHandleUnchecked;

import com.nyx.common.DatabaseFactory;
import com.nyx.common.DatabaseResources;
import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.common.PathSecurity;
import com.nyx.config.DatabaseConfig;
import com.nyx.media.model.ChapterMark;
import com.nyx.media.model.ChapterSet;
import com.nyx.media.model.UpsertChapterMarkRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

public final class ChapterService {
    private final Jdbi jdbi;
    private final PathSecurity pathSecurity;
    private final Clock clock;

    public ChapterService(Jdbi jdbi) {
        this(jdbi, null, Clock.systemUTC());
    }

    public ChapterService(Jdbi jdbi, PathSecurity pathSecurity) {
        this(jdbi, pathSecurity, Clock.systemUTC());
    }

    public ChapterService(Jdbi jdbi, PathSecurity pathSecurity, Clock clock) {
        this.jdbi = jdbi;
        this.pathSecurity = pathSecurity;
        this.clock = clock;
    }

    public ChapterSet getByMediaPath(String mediaPath) {
        String normalizedPath = normalizeMediaPath(mediaPath);
        return withHandleUnchecked(jdbi, handle -> {
            StoredChapterSet chapterSet = loadChapterSetByMediaPath(handle, normalizedPath);
            return chapterSet == null ? null : toDto(handle, chapterSet);
        });
    }

    public ChapterSet upsertForMediaPath(String mediaPath, String title, List<UpsertChapterMarkRequest> marks) {
        String normalizedPath = normalizeMediaPath(mediaPath);
        String normalizedTitle = title.trim();
        validateUniqueMarkIds(marks);
        Instant now = clock.instant();

        return sqliteWriteTransaction(jdbi, handle -> {
            StoredChapterSet existing = loadChapterSetByMediaPath(handle, normalizedPath);
            String chapterSetId = existing == null ? UUID.randomUUID().toString() : existing.id();
            Instant createdAt = existing == null ? now : existing.createdAt();

            if (existing == null) {
                handle.createUpdate(
                        """
                        INSERT INTO chapter_sets (id, media_path, title, created_at, updated_at)
                        VALUES (:id, :mediaPath, :title, :createdAt, :updatedAt)
                        """
                    )
                    .bind("id", chapterSetId)
                    .bind("mediaPath", normalizedPath)
                    .bind("title", normalizedTitle)
                    .bind("createdAt", createdAt.toString())
                    .bind("updatedAt", now.toString())
                    .execute();
            } else {
                handle.createUpdate(
                        """
                        UPDATE chapter_sets
                        SET title = :title, updated_at = :updatedAt
                        WHERE id = :id
                        """
                    )
                    .bind("id", chapterSetId)
                    .bind("title", normalizedTitle)
                    .bind("updatedAt", now.toString())
                    .execute();
            }

            Map<String, StoredChapterMark> currentMarks = loadStoredMarks(handle, chapterSetId)
                .stream()
                .collect(Collectors.toMap(StoredChapterMark::id, Function.identity()));
            List<IndexedStoredChapterMark> desiredMarks = new ArrayList<>();
            for (int index = 0; index < marks.size(); index++) {
                UpsertChapterMarkRequest mark = marks.get(index);
                ValidatedMark validated = validateMark(mark.label(), mark.ptsSecs(), mark.notes(), mark.sortOrder());
                StoredChapterMark existingMark = mark.id() == null ? null : currentMarks.get(mark.id());
                desiredMarks.add(new IndexedStoredChapterMark(
                    new StoredChapterMark(
                        existingMark == null ? UUID.randomUUID().toString() : existingMark.id(),
                        validated.label(),
                        validated.ptsSecs(),
                        validated.notes(),
                        validated.sortOrder(),
                        existingMark == null ? now : existingMark.createdAt(),
                        now
                    ),
                    index
                ));
            }
            desiredMarks.sort(
                Comparator.comparingInt((IndexedStoredChapterMark entry) -> entry.mark().sortOrder())
                    .thenComparingInt(IndexedStoredChapterMark::index)
            );

            List<StoredChapterMark> normalizedMarks = new ArrayList<>(desiredMarks.size());
            for (int index = 0; index < desiredMarks.size(); index++) {
                normalizedMarks.add(desiredMarks.get(index).mark().withSortOrder(index));
            }

            replaceMarks(handle, chapterSetId, normalizedMarks);
            StoredChapterSet updated = loadChapterSetById(handle, chapterSetId);
            if (updated == null) {
                return sneakyThrow(new NyxException(
                    ErrorCode.INVALID_REQUEST,
                    "Failed to load chapter set after upsert"
                ));
            }
            return toDto(handle, updated);
        });
    }

    public ChapterSet createMark(String mediaPath, String label, double ptsSecs, String notes) {
        String normalizedPath = normalizeMediaPath(mediaPath);
        ValidatedMark validated = validateMark(label, ptsSecs, notes, 0);
        Instant now = clock.instant();

        return sqliteWriteTransaction(jdbi, handle -> {
            StoredChapterSet existing = loadChapterSetByMediaPath(handle, normalizedPath);
            String chapterSetId = existing == null ? UUID.randomUUID().toString() : existing.id();

            if (existing == null) {
                handle.createUpdate(
                        """
                        INSERT INTO chapter_sets (id, media_path, title, created_at, updated_at)
                        VALUES (:id, :mediaPath, :title, :createdAt, :updatedAt)
                        """
                    )
                    .bind("id", chapterSetId)
                    .bind("mediaPath", normalizedPath)
                    .bind("title", "")
                    .bind("createdAt", now.toString())
                    .bind("updatedAt", now.toString())
                    .execute();
            } else {
                handle.createUpdate("UPDATE chapter_sets SET updated_at = :updatedAt WHERE id = :id")
                    .bind("id", chapterSetId)
                    .bind("updatedAt", now.toString())
                    .execute();
            }

            List<StoredChapterMark> currentMarks = loadStoredMarks(handle, chapterSetId);
            handle.createUpdate(
                    """
                    INSERT INTO chapter_marks (
                        id,
                        chapter_set_id,
                        label,
                        pts_secs,
                        notes,
                        sort_order,
                        created_at,
                        updated_at
                    )
                    VALUES (:id, :chapterSetId, :label, :ptsSecs, :notes, :sortOrder, :createdAt, :updatedAt)
                    """
                )
                .bind("id", UUID.randomUUID().toString())
                .bind("chapterSetId", chapterSetId)
                .bind("label", validated.label())
                .bind("ptsSecs", validated.ptsSecs())
                .bind("notes", validated.notes())
                .bind("sortOrder", currentMarks.size())
                .bind("createdAt", now.toString())
                .bind("updatedAt", now.toString())
                .execute();

            StoredChapterSet updated = loadChapterSetById(handle, chapterSetId);
            if (updated == null) {
                return sneakyThrow(new NyxException(
                    ErrorCode.INVALID_REQUEST,
                    "Failed to load chapter set after mark creation"
                ));
            }
            return toDto(handle, updated);
        });
    }

    public ChapterSet updateMark(String markId, String label, double ptsSecs, String notes, int sortOrder) {
        ValidatedMark validated = validateMark(label, ptsSecs, notes, sortOrder);
        Instant now = clock.instant();

        return sqliteWriteTransaction(jdbi, handle -> {
            ChapterMarkRow existingMark = loadStoredMark(handle, markId);
            if (existingMark == null) {
                return sneakyThrow(new NyxException(
                    ErrorCode.CHAPTER_MARK_NOT_FOUND,
                    "Chapter mark not found: " + markId
                ));
            }
            StoredChapterSet chapterSet = loadChapterSetById(handle, existingMark.chapterSetId());
            if (chapterSet == null) {
                return sneakyThrow(new NyxException(
                    ErrorCode.CHAPTER_MARK_NOT_FOUND,
                    "Chapter set not found for mark: " + markId
                ));
            }
            normalizeMediaPath(chapterSet.mediaPath());

            List<StoredChapterMark> marks = loadStoredMarks(handle, existingMark.chapterSetId())
                .stream()
                .sorted(Comparator.comparingInt(StoredChapterMark::sortOrder))
                .toList();
            List<StoredChapterMark> remaining = marks.stream()
                .filter(mark -> !mark.id().equals(markId))
                .toList();
            StoredChapterMark updatedMark = existingMark.toStoredChapterMark(validated, now);
            int destinationIndex = Math.max(0, Math.min(validated.sortOrder(), remaining.size()));

            List<StoredChapterMark> reordered = new ArrayList<>(marks.size());
            reordered.addAll(remaining.subList(0, destinationIndex));
            reordered.add(updatedMark);
            reordered.addAll(remaining.subList(destinationIndex, remaining.size()));

            List<StoredChapterMark> normalized = new ArrayList<>(reordered.size());
            for (int index = 0; index < reordered.size(); index++) {
                StoredChapterMark mark = reordered.get(index);
                boolean changedOrder = mark.sortOrder() != index;
                normalized.add(mark.withSortOrderAndUpdatedAt(index, mark.id().equals(markId) || changedOrder ? now : mark.updatedAt()));
            }

            handle.createUpdate("UPDATE chapter_sets SET updated_at = :updatedAt WHERE id = :id")
                .bind("id", chapterSet.id())
                .bind("updatedAt", now.toString())
                .execute();
            replaceMarks(handle, chapterSet.id(), normalized);

            StoredChapterSet updated = loadChapterSetById(handle, chapterSet.id());
            if (updated == null) {
                return sneakyThrow(new NyxException(
                    ErrorCode.CHAPTER_MARK_NOT_FOUND,
                    "Failed to load chapter set after mark update"
                ));
            }
            return toDto(handle, updated);
        });
    }

    public void deleteMark(String markId) {
        Instant now = clock.instant();

        sqliteWriteTransaction(jdbi, handle -> {
            ChapterMarkRow existingMark = loadStoredMark(handle, markId);
            if (existingMark == null) {
                sneakyThrow(new NyxException(
                    ErrorCode.CHAPTER_MARK_NOT_FOUND,
                    "Chapter mark not found: " + markId
                ));
            }
            StoredChapterSet chapterSet = loadChapterSetById(handle, existingMark.chapterSetId());
            if (chapterSet == null) {
                sneakyThrow(new NyxException(
                    ErrorCode.CHAPTER_MARK_NOT_FOUND,
                    "Chapter set not found for mark: " + markId
                ));
            }
            normalizeMediaPath(chapterSet.mediaPath());

            List<StoredChapterMark> remaining = loadStoredMarks(handle, existingMark.chapterSetId())
                .stream()
                .filter(mark -> !mark.id().equals(markId))
                .sorted(Comparator.comparingInt(StoredChapterMark::sortOrder))
                .toList();
            List<StoredChapterMark> normalized = new ArrayList<>(remaining.size());
            for (int index = 0; index < remaining.size(); index++) {
                StoredChapterMark mark = remaining.get(index);
                boolean changedOrder = mark.sortOrder() != index;
                normalized.add(mark.withSortOrderAndUpdatedAt(index, changedOrder ? now : mark.updatedAt()));
            }

            handle.createUpdate("UPDATE chapter_sets SET updated_at = :updatedAt WHERE id = :id")
                .bind("id", existingMark.chapterSetId())
                .bind("updatedAt", now.toString())
                .execute();
            replaceMarks(handle, existingMark.chapterSetId(), normalized);
            return null;
        });
    }

    public static DatabaseResources createDatabase(Path dbDir) {
        return createDatabase(dbDir, new DatabaseConfig(dbDir, 1));
    }

    public static DatabaseResources createDatabase(Path dbDir, DatabaseConfig dbConfig) {
        return DatabaseFactory.create(dbDir, "chapters", dbConfig);
    }

    private StoredChapterSet loadChapterSetByMediaPath(Handle handle, String mediaPath) {
        return handle.createQuery(
                """
                SELECT id, media_path, title, created_at, updated_at
                FROM chapter_sets
                WHERE media_path = :mediaPath
                """
            )
            .bind("mediaPath", mediaPath)
            .map((resultSet, context) -> new StoredChapterSet(
                resultSet.getString("id"),
                resultSet.getString("media_path"),
                resultSet.getString("title"),
                Instant.parse(resultSet.getString("created_at")),
                Instant.parse(resultSet.getString("updated_at"))
            ))
            .findOne()
            .orElse(null);
    }

    private StoredChapterSet loadChapterSetById(Handle handle, String id) {
        return handle.createQuery(
                """
                SELECT id, media_path, title, created_at, updated_at
                FROM chapter_sets
                WHERE id = :id
                """
            )
            .bind("id", id)
            .map((resultSet, context) -> new StoredChapterSet(
                resultSet.getString("id"),
                resultSet.getString("media_path"),
                resultSet.getString("title"),
                Instant.parse(resultSet.getString("created_at")),
                Instant.parse(resultSet.getString("updated_at"))
            ))
            .findOne()
            .orElse(null);
    }

    private List<StoredChapterMark> loadStoredMarks(Handle handle, String chapterSetId) {
        return handle.createQuery(
                """
                SELECT id, label, pts_secs, notes, sort_order, created_at, updated_at
                FROM chapter_marks
                WHERE chapter_set_id = :chapterSetId
                ORDER BY sort_order
                """
            )
            .bind("chapterSetId", chapterSetId)
            .map((resultSet, context) -> new StoredChapterMark(
                resultSet.getString("id"),
                resultSet.getString("label"),
                resultSet.getDouble("pts_secs"),
                resultSet.getString("notes"),
                resultSet.getInt("sort_order"),
                Instant.parse(resultSet.getString("created_at")),
                Instant.parse(resultSet.getString("updated_at"))
            ))
            .list();
    }

    private ChapterMarkRow loadStoredMark(Handle handle, String markId) {
        return handle.createQuery(
                """
                SELECT id, chapter_set_id, label, pts_secs, notes, sort_order, created_at, updated_at
                FROM chapter_marks
                WHERE id = :id
                """
            )
            .bind("id", markId)
            .map((resultSet, context) -> new ChapterMarkRow(
                resultSet.getString("id"),
                resultSet.getString("chapter_set_id"),
                resultSet.getString("label"),
                resultSet.getDouble("pts_secs"),
                resultSet.getString("notes"),
                resultSet.getInt("sort_order"),
                Instant.parse(resultSet.getString("created_at")),
                Instant.parse(resultSet.getString("updated_at"))
            ))
            .findOne()
            .orElse(null);
    }

    private void replaceMarks(Handle handle, String chapterSetId, List<StoredChapterMark> marks) {
        handle.createUpdate("DELETE FROM chapter_marks WHERE chapter_set_id = :chapterSetId")
            .bind("chapterSetId", chapterSetId)
            .execute();
        for (StoredChapterMark mark : marks) {
            handle.createUpdate(
                    """
                    INSERT INTO chapter_marks (
                        id,
                        chapter_set_id,
                        label,
                        pts_secs,
                        notes,
                        sort_order,
                        created_at,
                        updated_at
                    )
                    VALUES (:id, :chapterSetId, :label, :ptsSecs, :notes, :sortOrder, :createdAt, :updatedAt)
                    """
                )
                .bind("id", mark.id())
                .bind("chapterSetId", chapterSetId)
                .bind("label", mark.label())
                .bind("ptsSecs", mark.ptsSecs())
                .bind("notes", mark.notes())
                .bind("sortOrder", mark.sortOrder())
                .bind("createdAt", mark.createdAt().toString())
                .bind("updatedAt", mark.updatedAt().toString())
                .execute();
        }
    }

    private ChapterSet toDto(Handle handle, StoredChapterSet chapterSet) {
        List<ChapterMark> marks = loadStoredMarks(handle, chapterSet.id())
            .stream()
            .map(StoredChapterMark::toDto)
            .toList();
        return new ChapterSet(
            chapterSet.id(),
            chapterSet.mediaPath(),
            chapterSet.title(),
            marks,
            chapterSet.createdAt(),
            chapterSet.updatedAt()
        );
    }

    private String normalizeMediaPath(String mediaPath) {
        String trimmed = mediaPath.trim();
        if (trimmed.isBlank()) {
            return sneakyThrow(new NyxException(ErrorCode.INVALID_REQUEST, "Media path must not be blank"));
        }
        Path normalizedPath = pathSecurity == null ? Path.of(trimmed) : pathSecurity.validate(trimmed);
        if (Files.isDirectory(normalizedPath)) {
            return sneakyThrow(new NyxException(ErrorCode.INVALID_REQUEST, "Media path must reference a file"));
        }
        return normalizedPath.toString();
    }

    private ValidatedMark validateMark(String label, double ptsSecs, String notes, int sortOrder) {
        String normalizedLabel = label.trim();
        if (normalizedLabel.isBlank()) {
            return sneakyThrow(new NyxException(
                ErrorCode.VALIDATION_ERROR,
                "Chapter mark label must not be blank"
            ));
        }
        if (!Double.isFinite(ptsSecs) || ptsSecs < 0.0d) {
            return sneakyThrow(new NyxException(
                ErrorCode.VALIDATION_ERROR,
                "Chapter mark pts_secs must be a non-negative finite number"
            ));
        }
        if (sortOrder < 0) {
            return sneakyThrow(new NyxException(
                ErrorCode.VALIDATION_ERROR,
                "Chapter mark sort_order must be non-negative"
            ));
        }
        return new ValidatedMark(normalizedLabel, ptsSecs, notes.trim().isBlank() ? "" : notes.trim(), sortOrder);
    }

    private void validateUniqueMarkIds(List<UpsertChapterMarkRequest> marks) {
        List<String> duplicateIds = marks.stream()
            .map(UpsertChapterMarkRequest::id)
            .filter(id -> id != null && !id.isBlank())
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
            .entrySet()
            .stream()
            .filter(entry -> entry.getValue() > 1L)
            .map(Map.Entry::getKey)
            .sorted()
            .toList();
        if (!duplicateIds.isEmpty()) {
            sneakyThrow(new NyxException(
                ErrorCode.VALIDATION_ERROR,
                "Duplicate chapter mark id: " + duplicateIds.getFirst()
            ));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }

    private record IndexedStoredChapterMark(StoredChapterMark mark, int index) {}

    private record StoredChapterMark(
        String id,
        String label,
        double ptsSecs,
        String notes,
        int sortOrder,
        Instant createdAt,
        Instant updatedAt
    ) {
        private StoredChapterMark withSortOrder(int newSortOrder) {
            return new StoredChapterMark(id, label, ptsSecs, notes, newSortOrder, createdAt, updatedAt);
        }

        private StoredChapterMark withSortOrderAndUpdatedAt(int newSortOrder, Instant newUpdatedAt) {
            return new StoredChapterMark(id, label, ptsSecs, notes, newSortOrder, createdAt, newUpdatedAt);
        }

        private ChapterMark toDto() {
            return new ChapterMark(id, label, ptsSecs, notes, sortOrder, createdAt, updatedAt);
        }
    }

    private record StoredChapterSet(
        String id,
        String mediaPath,
        String title,
        Instant createdAt,
        Instant updatedAt
    ) {}

    private record ValidatedMark(
        String label,
        double ptsSecs,
        String notes,
        int sortOrder
    ) {}

    private record ChapterMarkRow(
        String id,
        String chapterSetId,
        String label,
        double ptsSecs,
        String notes,
        int sortOrder,
        Instant createdAt,
        Instant updatedAt
    ) {
        private StoredChapterMark toStoredChapterMark(ValidatedMark validated, Instant now) {
            return new StoredChapterMark(
                id,
                validated.label(),
                validated.ptsSecs(),
                validated.notes(),
                validated.sortOrder(),
                createdAt,
                now
            );
        }
    }
}
