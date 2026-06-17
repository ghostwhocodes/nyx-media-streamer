package com.nyx.media;

import static com.nyx.common.SqliteWriteTransactions.sqliteWriteTransaction;
import static com.nyx.common.SqliteWriteTransactions.withHandleUnchecked;

import com.nyx.common.DatabaseFactory;
import com.nyx.common.DatabaseResources;
import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.config.DatabaseConfig;
import com.nyx.media.contracts.CreateLibraryRequest;
import com.nyx.media.contracts.Library;
import com.nyx.media.contracts.LibraryScanState;
import com.nyx.media.contracts.LibraryScanStatus;
import com.nyx.media.contracts.LibrarySourceRoot;
import com.nyx.media.contracts.LibrarySourceRootWriteRequest;
import com.nyx.media.contracts.LibraryType;
import com.nyx.media.contracts.UpdateLibraryRequest;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

public final class LibraryService {
    private final Jdbi jdbi;
    private final Clock clock;

    public LibraryService(Jdbi jdbi) {
        this(jdbi, Clock.systemUTC());
    }

    public LibraryService(Jdbi jdbi, Clock clock) {
        this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Library createLibrary(CreateLibraryRequest request) {
        NormalizedLibraryMutation normalized = normalize(request);
        String libraryId = UUID.randomUUID().toString();
        String now = clock.instant().toString();

        return sqliteWriteTransaction(jdbi, handle -> {
            handleEnsureSourceRootsAreAvailable(handle, normalized.sourceRoots().stream().map(NormalizedLibrarySourceRoot::path).toList(), null);
            handle.createUpdate("""
                    INSERT INTO libraries(
                        library_id, name, description, library_type, scan_status,
                        last_scan_started_at, last_scan_completed_at, last_scan_failed_at,
                        last_scan_error, created_at, updated_at
                    ) VALUES (
                        :libraryId, :name, :description, :libraryType, :scanStatus,
                        :lastScanStartedAt, :lastScanCompletedAt, :lastScanFailedAt,
                        :lastScanError, :createdAt, :updatedAt
                    )
                    """)
                .bind("libraryId", libraryId)
                .bind("name", normalized.name())
                .bind("description", normalized.description())
                .bind("libraryType", normalized.type().name())
                .bind("scanStatus", LibraryScanStatus.IDLE.name())
                .bindNull("lastScanStartedAt", java.sql.Types.VARCHAR)
                .bindNull("lastScanCompletedAt", java.sql.Types.VARCHAR)
                .bindNull("lastScanFailedAt", java.sql.Types.VARCHAR)
                .bindNull("lastScanError", java.sql.Types.VARCHAR)
                .bind("createdAt", now)
                .bind("updatedAt", now)
                .execute();
            handleSyncSourceRoots(handle, libraryId, normalized.sourceRoots(), now);
            Library created = handleLoadLibrary(handle, libraryId);
            if (created == null) {
                throw sneakyThrow(new NyxException(ErrorCode.INVALID_REQUEST, "Failed to load library after create"));
            }
            return created;
        });
    }

    public Library getLibrary(String libraryId) {
        String normalizedLibraryId = libraryId.trim();
        return withHandleUnchecked(jdbi, handle -> handleLoadLibrary(handle, normalizedLibraryId));
    }

    public List<Library> listLibraries() {
        return withHandleUnchecked(jdbi, handle -> {
            List<PersistedLibraryRow> libraries = handle.createQuery("""
                    SELECT library_id, name, description, library_type, scan_status,
                           last_scan_started_at, last_scan_completed_at, last_scan_failed_at,
                           last_scan_error, created_at, updated_at
                    FROM libraries
                    ORDER BY updated_at DESC
                    """)
                .map((resultSet, context) -> toLibraryRow(resultSet))
                .list();

            Map<String, List<PersistedLibrarySourceRootRow>> sourceRootsByLibrary = handle.createQuery("""
                    SELECT source_root_id, library_id, root_path, display_name, position, created_at, updated_at
                    FROM library_source_roots
                    ORDER BY library_id ASC, position ASC
                    """)
                .map((resultSet, context) -> toLibrarySourceRootRow(resultSet))
                .list()
                .stream()
                .collect(Collectors.groupingBy(
                    PersistedLibrarySourceRootRow::libraryId,
                    LinkedHashMap::new,
                    Collectors.toList()
                ));

            return libraries.stream()
                .map(row -> row.toLibrary(sourceRootsByLibrary.getOrDefault(row.libraryId(), List.of())))
                .toList();
        });
    }

    public Library updateLibrary(String libraryId, UpdateLibraryRequest request) {
        String normalizedLibraryId = libraryId.trim();
        return sqliteWriteTransaction(jdbi, handle -> {
            Library existing = handleLoadLibrary(handle, normalizedLibraryId);
            if (existing == null) {
                throw sneakyThrow(new NyxException(ErrorCode.LIBRARY_NOT_FOUND, "Library not found: " + normalizedLibraryId));
            }
            NormalizedLibraryMutation normalized = normalize(request, existing);
            String now = clock.instant().toString();

            handleEnsureSourceRootsAreAvailable(
                handle,
                normalized.sourceRoots().stream().map(NormalizedLibrarySourceRoot::path).toList(),
                normalizedLibraryId
            );

            handle.createUpdate("""
                    UPDATE libraries
                    SET name = :name,
                        description = :description,
                        library_type = :libraryType,
                        updated_at = :updatedAt
                    WHERE library_id = :libraryId
                    """)
                .bind("name", normalized.name())
                .bind("description", normalized.description())
                .bind("libraryType", normalized.type().name())
                .bind("updatedAt", now)
                .bind("libraryId", normalizedLibraryId)
                .execute();
            handleSyncSourceRoots(handle, normalizedLibraryId, normalized.sourceRoots(), now);
            Library updated = handleLoadLibrary(handle, normalizedLibraryId);
            if (updated == null) {
                throw sneakyThrow(new NyxException(ErrorCode.INVALID_REQUEST, "Failed to load library after update"));
            }
            return updated;
        });
    }

    public void deleteLibrary(String libraryId) {
        String normalizedLibraryId = libraryId.trim();
        sqliteWriteTransaction(jdbi, handle -> {
            int deleted = handle.createUpdate("DELETE FROM libraries WHERE library_id = :libraryId")
                .bind("libraryId", normalizedLibraryId)
                .execute();
            if (deleted == 0) {
                throw sneakyThrow(new NyxException(ErrorCode.LIBRARY_NOT_FOUND, "Library not found: " + normalizedLibraryId));
            }
            return null;
        });
    }

    private static Library handleLoadLibrary(Handle handle, String libraryId) {
        PersistedLibraryRow row = handle.createQuery("""
                SELECT library_id, name, description, library_type, scan_status,
                       last_scan_started_at, last_scan_completed_at, last_scan_failed_at,
                       last_scan_error, created_at, updated_at
                FROM libraries
                WHERE library_id = :libraryId
                """)
            .bind("libraryId", libraryId)
            .map((resultSet, context) -> toLibraryRow(resultSet))
            .findOne()
            .orElse(null);
        if (row == null) {
            return null;
        }
        List<PersistedLibrarySourceRootRow> sourceRoots = handle.createQuery("""
                SELECT source_root_id, library_id, root_path, display_name, position, created_at, updated_at
                FROM library_source_roots
                WHERE library_id = :libraryId
                ORDER BY position ASC
                """)
            .bind("libraryId", libraryId)
            .map((resultSet, context) -> toLibrarySourceRootRow(resultSet))
            .list();
        return row.toLibrary(sourceRoots);
    }

    private static void handleSyncSourceRoots(
        Handle handle,
        String libraryId,
        List<NormalizedLibrarySourceRoot> sourceRoots,
        String now
    ) {
        Map<String, PersistedLibrarySourceRootRow> existingByPath = handle.createQuery("""
                SELECT source_root_id, library_id, root_path, display_name, position, created_at, updated_at
                FROM library_source_roots
                WHERE library_id = :libraryId
                """)
            .bind("libraryId", libraryId)
            .map((resultSet, context) -> toLibrarySourceRootRow(resultSet))
            .list()
            .stream()
            .collect(Collectors.toMap(
                PersistedLibrarySourceRootRow::path,
                row -> row,
                (left, right) -> left,
                LinkedHashMap::new
            ));

        List<String> requestedPaths = sourceRoots.stream().map(NormalizedLibrarySourceRoot::path).toList();
        if (requestedPaths.isEmpty()) {
            handle.createUpdate("DELETE FROM library_source_roots WHERE library_id = :libraryId")
                .bind("libraryId", libraryId)
                .execute();
        } else {
            handle.createUpdate("""
                    DELETE FROM library_source_roots
                    WHERE library_id = :libraryId
                      AND root_path NOT IN (<requestedPaths>)
                    """)
                .bind("libraryId", libraryId)
                .bindList("requestedPaths", requestedPaths)
                .execute();
        }

        int index = 0;
        for (PersistedLibrarySourceRootRow existing : existingByPath.values()) {
            if (requestedPaths.contains(existing.path())) {
                handle.createUpdate("""
                        UPDATE library_source_roots
                        SET position = :position,
                            updated_at = :updatedAt
                        WHERE source_root_id = :sourceRootId
                        """)
                    .bind("position", sourceRoots.size() + index)
                    .bind("updatedAt", now)
                    .bind("sourceRootId", existing.sourceRootId())
                    .execute();
                index += 1;
            }
        }

        for (int position = 0; position < sourceRoots.size(); position += 1) {
            NormalizedLibrarySourceRoot sourceRoot = sourceRoots.get(position);
            PersistedLibrarySourceRootRow existing = existingByPath.get(sourceRoot.path());
            if (existing == null) {
                handle.createUpdate("""
                        INSERT INTO library_source_roots(
                            source_root_id, library_id, root_path, display_name, position, created_at, updated_at
                        ) VALUES (
                            :sourceRootId, :libraryId, :rootPath, :displayName, :position, :createdAt, :updatedAt
                        )
                        """)
                    .bind("sourceRootId", UUID.randomUUID().toString())
                    .bind("libraryId", libraryId)
                    .bind("rootPath", sourceRoot.path())
                    .bind("displayName", sourceRoot.displayName())
                    .bind("position", position)
                    .bind("createdAt", now)
                    .bind("updatedAt", now)
                    .execute();
            } else {
                handle.createUpdate("""
                        UPDATE library_source_roots
                        SET display_name = :displayName,
                            position = :position,
                            updated_at = :updatedAt
                        WHERE source_root_id = :sourceRootId
                        """)
                    .bind("displayName", sourceRoot.displayName())
                    .bind("position", position)
                    .bind("updatedAt", now)
                    .bind("sourceRootId", existing.sourceRootId())
                    .execute();
            }
        }
    }

    private static void handleEnsureSourceRootsAreAvailable(
        Handle handle,
        List<String> paths,
        String excludingLibraryId
    ) {
        if (paths.isEmpty()) {
            return;
        }

        String sql = excludingLibraryId == null
            ? """
                SELECT root_path, library_id
                FROM library_source_roots
                WHERE root_path IN (<paths>)
                """
            : """
                SELECT root_path, library_id
                FROM library_source_roots
                WHERE root_path IN (<paths>)
                  AND library_id <> :excludingLibraryId
                """;

        var conflictsQuery = handle.createQuery(sql).bindList("paths", paths);
        if (excludingLibraryId != null) {
            conflictsQuery = conflictsQuery.bind("excludingLibraryId", excludingLibraryId);
        }

        List<String> conflicts = conflictsQuery
            .map((resultSet, context) -> resultSet.getString("root_path"))
            .list();

        if (!conflicts.isEmpty()) {
            String conflictPaths = conflicts.stream().distinct().sorted().collect(Collectors.joining(", "));
            throw sneakyThrow(new NyxException(
                ErrorCode.SCHEMA_CONFLICT,
                "Source root already assigned to another library: " + conflictPaths
            ));
        }
    }

    private static PersistedLibraryRow toLibraryRow(ResultSet resultSet) throws java.sql.SQLException {
        return new PersistedLibraryRow(
            resultSet.getString("library_id"),
            resultSet.getString("name"),
            resultSet.getString("description"),
            LibraryType.valueOf(resultSet.getString("library_type")),
            LibraryScanStatus.valueOf(resultSet.getString("scan_status")),
            resultSet.getString("last_scan_started_at"),
            resultSet.getString("last_scan_completed_at"),
            resultSet.getString("last_scan_failed_at"),
            resultSet.getString("last_scan_error"),
            resultSet.getString("created_at"),
            resultSet.getString("updated_at")
        );
    }

    private static PersistedLibrarySourceRootRow toLibrarySourceRootRow(ResultSet resultSet) throws java.sql.SQLException {
        return new PersistedLibrarySourceRootRow(
            resultSet.getString("source_root_id"),
            resultSet.getString("library_id"),
            resultSet.getString("root_path"),
            resultSet.getString("display_name"),
            resultSet.getInt("position"),
            resultSet.getString("created_at"),
            resultSet.getString("updated_at")
        );
    }

    private static NormalizedLibraryMutation normalize(CreateLibraryRequest request) {
        return new NormalizedLibraryMutation(
            normalizeLibraryName(request.name()),
            normalizeDescription(request.description()),
            request.type(),
            normalizeSourceRoots(request.sourceRoots())
        );
    }

    private static NormalizedLibraryMutation normalize(UpdateLibraryRequest request, Library existing) {
        List<NormalizedLibrarySourceRoot> sourceRoots;
        if (request.sourceRoots() == null) {
            sourceRoots = existing.sourceRoots().stream()
                .map(root -> new NormalizedLibrarySourceRoot(root.path(), root.displayName()))
                .toList();
        } else {
            sourceRoots = normalizeSourceRoots(request.sourceRoots());
        }

        return new NormalizedLibraryMutation(
            normalizeLibraryName(request.name() == null ? existing.name() : request.name()),
            normalizeDescription(request.description() == null ? existing.description() : request.description()),
            request.type() == null ? existing.type() : request.type(),
            sourceRoots
        );
    }

    private static String normalizeLibraryName(String raw) {
        String normalized = raw.trim();
        if (normalized.isBlank()) {
            throw sneakyThrow(new NyxException(ErrorCode.INVALID_REQUEST, "Library name must not be blank"));
        }
        return normalized;
    }

    private static String normalizeDescription(String raw) {
        return raw.trim();
    }

    private static List<NormalizedLibrarySourceRoot> normalizeSourceRoots(List<LibrarySourceRootWriteRequest> sourceRoots) {
        if (sourceRoots.isEmpty()) {
            throw sneakyThrow(new NyxException(ErrorCode.INVALID_REQUEST, "Library must have at least one source root"));
        }

        List<NormalizedLibrarySourceRoot> normalized = new ArrayList<>(sourceRoots.size());
        for (LibrarySourceRootWriteRequest sourceRoot : sourceRoots) {
            String normalizedPath = normalizeSourceRootPath(sourceRoot.path());
            String normalizedDisplayName;
            if (sourceRoot.displayName() == null) {
                normalizedDisplayName = Path.of(normalizedPath).getFileName() == null
                    ? normalizedPath
                    : Path.of(normalizedPath).getFileName().toString();
            } else {
                String trimmed = sourceRoot.displayName().trim();
                if (trimmed.isBlank()) {
                    normalizedDisplayName = Path.of(normalizedPath).getFileName() == null
                        ? normalizedPath
                        : Path.of(normalizedPath).getFileName().toString();
                } else {
                    normalizedDisplayName = trimmed;
                }
            }
            normalized.add(new NormalizedLibrarySourceRoot(normalizedPath, normalizedDisplayName));
        }

        List<String> duplicatePaths = normalized.stream()
            .collect(Collectors.groupingBy(NormalizedLibrarySourceRoot::path, Collectors.counting()))
            .entrySet()
            .stream()
            .filter(entry -> entry.getValue() > 1)
            .map(Map.Entry::getKey)
            .sorted()
            .toList();
        if (!duplicatePaths.isEmpty()) {
            throw sneakyThrow(new NyxException(
                ErrorCode.INVALID_REQUEST,
                "Library source roots must be unique: " + String.join(", ", duplicatePaths)
            ));
        }
        return normalized;
    }

    private static String normalizeSourceRootPath(String rawPath) {
        String trimmed = rawPath.trim();
        if (trimmed.isBlank()) {
            throw sneakyThrow(new NyxException(ErrorCode.INVALID_REQUEST, "Library source root path must not be blank"));
        }
        return Path.of(trimmed).normalize().toAbsolutePath().toString();
    }

    public static DatabaseResources createDatabase(Path dbDir) {
        return createDatabase(dbDir, new DatabaseConfig(dbDir, 1));
    }

    public static DatabaseResources createDatabase(Path dbDir, DatabaseConfig dbConfig) {
        return DatabaseFactory.create(dbDir, "libraries", dbConfig);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }

    private record NormalizedLibraryMutation(
        String name,
        String description,
        LibraryType type,
        List<NormalizedLibrarySourceRoot> sourceRoots
    ) {
    }

    private record NormalizedLibrarySourceRoot(String path, String displayName) {
    }

    private record PersistedLibraryRow(
        String libraryId,
        String name,
        String description,
        LibraryType type,
        LibraryScanStatus scanStatus,
        String lastScanStartedAt,
        String lastScanCompletedAt,
        String lastScanFailedAt,
        String lastScanError,
        String createdAt,
        String updatedAt
    ) {
        private Library toLibrary(List<PersistedLibrarySourceRootRow> sourceRoots) {
            return new Library(
                libraryId,
                name,
                description,
                type,
                sourceRoots.stream().map(PersistedLibrarySourceRootRow::toLibrarySourceRoot).toList(),
                new LibraryScanState(
                    scanStatus,
                    lastScanStartedAt,
                    lastScanCompletedAt,
                    lastScanFailedAt,
                    lastScanError
                ),
                createdAt,
                updatedAt
            );
        }
    }

    private record PersistedLibrarySourceRootRow(
        String sourceRootId,
        String libraryId,
        String path,
        String displayName,
        int position,
        String createdAt,
        String updatedAt
    ) {
        private LibrarySourceRoot toLibrarySourceRoot() {
            return new LibrarySourceRoot(
                sourceRootId,
                path,
                displayName,
                position,
                createdAt,
                updatedAt
            );
        }
    }
}
