package com.nyx.media;

import static com.nyx.common.SqliteWriteTransactions.sqliteWriteTransaction;
import static com.nyx.common.SqliteWriteTransactions.withHandleUnchecked;

import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.media.contracts.Library;
import com.nyx.media.contracts.LibraryScanState;
import com.nyx.media.contracts.LibraryScanStatus;
import com.nyx.media.contracts.LibrarySourceRoot;
import com.nyx.media.contracts.LibraryType;
import com.nyx.media.contracts.MediaKind;
import com.nyx.media.contracts.MediaObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

public final class LibraryScanService {
    private static final List<String> ACTIVE_RUN_STATUSES = List.of(
        LibraryScanRunStatus.QUEUED.name(),
        LibraryScanRunStatus.RUNNING.name()
    );

    private final Jdbi jdbi;
    private final LibraryService libraryService;
    private final MediaObjectService mediaObjectService;
    private final MediaObjectResolver mediaObjectResolver;
    private final ExecutorService backgroundExecutor;
    private final LibraryInterpretationService libraryInterpretationService;
    private final LibraryCatalogService libraryCatalogService;
    private final LibraryExtensionCoordinator libraryExtensionCoordinator;
    private final Map<String, ReentrantLock> libraryLocks = new ConcurrentHashMap<>();
    private final Set<String> processActiveScanRunIds = ConcurrentHashMap.newKeySet();

    public LibraryScanService(
        Jdbi jdbi,
        LibraryService libraryService,
        MediaObjectService mediaObjectService,
        MediaObjectResolver mediaObjectResolver,
        ExecutorService backgroundExecutor
    ) {
        this(
            jdbi,
            libraryService,
            mediaObjectService,
            mediaObjectResolver,
            backgroundExecutor,
            null,
            null,
            null
        );
    }

    public LibraryScanService(
        Jdbi jdbi,
        LibraryService libraryService,
        MediaObjectService mediaObjectService,
        MediaObjectResolver mediaObjectResolver,
        ExecutorService backgroundExecutor,
        LibraryInterpretationService libraryInterpretationService,
        LibraryCatalogService libraryCatalogService
    ) {
        this(
            jdbi,
            libraryService,
            mediaObjectService,
            mediaObjectResolver,
            backgroundExecutor,
            libraryInterpretationService,
            libraryCatalogService,
            null
        );
    }

    public LibraryScanService(
        Jdbi jdbi,
        LibraryService libraryService,
        MediaObjectService mediaObjectService,
        MediaObjectResolver mediaObjectResolver,
        ExecutorService backgroundExecutor,
        LibraryInterpretationService libraryInterpretationService,
        LibraryCatalogService libraryCatalogService,
        LibraryExtensionCoordinator libraryExtensionCoordinator
    ) {
        this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
        this.libraryService = Objects.requireNonNull(libraryService, "libraryService");
        this.mediaObjectService = Objects.requireNonNull(mediaObjectService, "mediaObjectService");
        this.mediaObjectResolver = Objects.requireNonNull(mediaObjectResolver, "mediaObjectResolver");
        this.backgroundExecutor = Objects.requireNonNull(backgroundExecutor, "backgroundExecutor");
        this.libraryInterpretationService = libraryInterpretationService;
        this.libraryCatalogService = libraryCatalogService;
        this.libraryExtensionCoordinator = libraryExtensionCoordinator;
    }

    public LibraryScanRun triggerScan(String libraryId, LibraryScanMode mode) {
        String normalizedLibraryId = libraryId.trim();
        return withLibraryLock(normalizedLibraryId, () -> {
            LibraryScanRun queuedRun = createQueuedRun(normalizedLibraryId, mode);
            backgroundExecutor.submit(() ->
                withLibraryLock(normalizedLibraryId, () -> executeQueuedRun(queuedRun.getScanRunId()))
            );
            return queuedRun;
        });
    }

    public LibraryScanRun runScanNow(String libraryId, LibraryScanMode mode) {
        String normalizedLibraryId = libraryId.trim();
        return withLibraryLock(normalizedLibraryId, () -> {
            LibraryScanRun queuedRun = createQueuedRun(normalizedLibraryId, mode);
            return executeQueuedRun(queuedRun.getScanRunId());
        });
    }

    public List<LibraryScanRun> listRuns(String libraryId) {
        return listRuns(libraryId, 20);
    }

    public List<LibraryScanRun> listRuns(String libraryId, int limit) {
        return withHandleUnchecked(jdbi, handle -> {
            ensureLibraryExists(handle, libraryId.trim());
            return handle.createQuery("""
                    SELECT scan_run_id, library_id, mode, status, created_at, started_at, completed_at,
                           error_message, files_scanned, imported_count, refreshed_count, missing_count
                    FROM library_scan_runs
                    WHERE library_id = :libraryId
                    ORDER BY created_at DESC
                    LIMIT :limit
                    """)
                .bind("libraryId", libraryId.trim())
                .bind("limit", limit)
                .map((resultSet, context) -> toLibraryScanRun(resultSet))
                .list();
        });
    }

    public LibraryScanRun getRun(String scanRunId) {
        return withHandleUnchecked(jdbi, handle -> handle.createQuery("""
                SELECT scan_run_id, library_id, mode, status, created_at, started_at, completed_at,
                       error_message, files_scanned, imported_count, refreshed_count, missing_count
                FROM library_scan_runs
                WHERE scan_run_id = :scanRunId
                """)
            .bind("scanRunId", scanRunId.trim())
            .map((resultSet, context) -> toLibraryScanRun(resultSet))
            .findOne()
            .orElse(null));
    }

    public List<LibraryTrackedObject> listTrackedObjects(String libraryId) {
        return withHandleUnchecked(jdbi, handle -> {
            ensureLibraryExists(handle, libraryId.trim());
            return handle.createQuery("""
                    SELECT library_entry_id, library_id, object_id, source_root_id, media_kind, primary_path,
                           path_key, status, first_scanned_at, last_scanned_at, missing_at, last_scan_run_id
                    FROM library_entries
                    WHERE library_id = :libraryId
                    ORDER BY primary_path ASC
                    """)
                .bind("libraryId", libraryId.trim())
                .map((resultSet, context) -> toLibraryTrackedObject(resultSet))
                .list();
        });
    }

    private LibraryScanRun executeQueuedRun(String scanRunId) {
        try {
            LibraryScanRun queuedRun = getRun(scanRunId);
            if (queuedRun == null) {
                throw sneakyThrow(new NyxException(
                    ErrorCode.INVALID_REQUEST,
                    "Library scan run not found: " + scanRunId
                ));
            }
            String startedAt = Instant.now().toString();
            markRunRunning(scanRunId, queuedRun.getLibraryId(), startedAt);

            try {
                ScanCounters counters = queuedRun.getMode() == LibraryScanMode.REFRESH
                    ? refreshLibrary(queuedRun.getLibraryId(), scanRunId, startedAt)
                    : scanLibrary(queuedRun.getLibraryId(), queuedRun.getMode(), scanRunId, startedAt);
                if (libraryInterpretationService != null) {
                    libraryInterpretationService.rebuildLibraryItems(queuedRun.getLibraryId());
                }
                if (libraryCatalogService != null) {
                    libraryCatalogService.refreshLocalEnrichment(queuedRun.getLibraryId());
                }
                LibraryScanRun completedRun = completeRun(
                    scanRunId,
                    queuedRun.getLibraryId(),
                    counters,
                    Instant.now().toString()
                );
                if (libraryExtensionCoordinator != null) {
                    libraryExtensionCoordinator.afterSuccessfulScan(
                        completedRun,
                        listTrackedObjects(completedRun.getLibraryId())
                    );
                }
                return completedRun;
            } catch (Exception error) {
                return failRun(
                    scanRunId,
                    queuedRun.getLibraryId(),
                    Instant.now().toString(),
                    error.getMessage() == null ? "Library scan failed" : error.getMessage()
                );
            }
        } finally {
            processActiveScanRunIds.remove(scanRunId);
        }
    }

    private ScanCounters scanLibrary(
        String libraryId,
        LibraryScanMode mode,
        String scanRunId,
        String scanTimestamp
    ) {
        Library library = libraryService.getLibrary(libraryId);
        if (library == null) {
            throw sneakyThrow(new NyxException(
                ErrorCode.LIBRARY_NOT_FOUND,
                "Library not found: " + libraryId
            ));
        }
        Map<String, LibraryTrackedObject> activeStatusesBeforeScan = listTrackedObjects(libraryId)
            .stream()
            .collect(java.util.stream.Collectors.toMap(
                LibraryTrackedObject::getObjectId,
                Function.identity()
            ));
        List<SourceRootFiles> sourceRootFiles = new ArrayList<>();
        for (LibrarySourceRoot sourceRoot : library.sourceRoots().stream()
            .sorted(java.util.Comparator.comparingInt(LibrarySourceRoot::position))
            .toList()) {
            Path rootPath = Path.of(sourceRoot.path());
            requireSourceRoot(rootPath, sourceRoot.path());
            sourceRootFiles.add(new SourceRootFiles(sourceRoot.sourceRootId(), collectRegularFiles(rootPath)));
        }

        if (mode == LibraryScanMode.REBUILD) {
            markAllTrackedObjectsMissing(libraryId, scanRunId, scanTimestamp);
        }

        Set<String> seenObjectIds = new LinkedHashSet<>();
        Set<String> seenPaths = new LinkedHashSet<>();
        int importedCount = 0;
        int refreshedCount = 0;
        MediaObjectResolveOptions resolverOptions = new MediaObjectResolveOptions(
            allowedMediaKinds(library.type()),
            false
        );

        for (SourceRootFiles sourceRoot : sourceRootFiles) {
            for (Path file : sourceRoot.files()) {
                String normalizedPath = file.toAbsolutePath().normalize().toString();
                if (!seenPaths.add(normalizedPath)) {
                    continue;
                }
                MediaObject mediaObject = mediaObjectResolver.resolveOrCreate(file, resolverOptions);
                if (mediaObject == null) {
                    continue;
                }
                LibraryTrackedObject existingEntry = activeStatusesBeforeScan.get(mediaObject.objectId());
                upsertTrackedObject(
                    libraryId,
                    scanRunId,
                    scanTimestamp,
                    sourceRoot.sourceRootId(),
                    mediaObject
                );
                if (existingEntry == null) {
                    importedCount += 1;
                } else {
                    refreshedCount += 1;
                }
                seenObjectIds.add(mediaObject.objectId());
            }
        }

        List<String> missingObjectIds = markTrackedObjectsMissingNotSeen(
            libraryId,
            seenObjectIds,
            scanRunId,
            scanTimestamp
        );
        for (String objectId : missingObjectIds) {
            mediaObjectService.markMissing(objectId);
        }

        return new ScanCounters(
            importedCount + refreshedCount,
            importedCount,
            refreshedCount,
            missingObjectIds.size()
        );
    }

    private ScanCounters refreshLibrary(
        String libraryId,
        String scanRunId,
        String scanTimestamp
    ) {
        Library library = libraryService.getLibrary(libraryId);
        if (library == null) {
            throw sneakyThrow(new NyxException(
                ErrorCode.LIBRARY_NOT_FOUND,
                "Library not found: " + libraryId
            ));
        }
        List<LibraryTrackedObject> activeTrackedObjects = listTrackedObjects(libraryId)
            .stream()
            .filter(trackedObject -> trackedObject.getStatus() == LibraryTrackedObjectStatus.ACTIVE)
            .sorted(java.util.Comparator.comparing(LibraryTrackedObject::getPrimaryPath))
            .toList();
        MediaObjectResolveOptions resolverOptions = new MediaObjectResolveOptions(
            allowedMediaKinds(library.type()),
            false
        );

        int refreshedCount = 0;
        int missingCount = 0;
        for (LibraryTrackedObject trackedObject : activeTrackedObjects) {
            Path primaryPath = Path.of(trackedObject.getPrimaryPath());
            if (!Files.isRegularFile(primaryPath)) {
                missingCount += 1;
                continue;
            }
            MediaObject mediaObject = mediaObjectResolver.resolveOrCreate(primaryPath, resolverOptions);
            if (mediaObject == null) {
                missingCount += 1;
                continue;
            }
            upsertTrackedObject(
                libraryId,
                scanRunId,
                scanTimestamp,
                trackedObject.getSourceRootId(),
                mediaObject
            );
            refreshedCount += 1;
        }

        return new ScanCounters(refreshedCount, 0, refreshedCount, missingCount);
    }

    private LibraryScanRun createQueuedRun(String libraryId, LibraryScanMode mode) {
        ensureLibraryIsScannable(libraryId);
        recoverStaleActiveRuns(libraryId, Instant.now().toString());
        LibraryScanRun activeRun = activeRunForLibrary(libraryId);
        if (activeRun != null) {
            throw sneakyThrow(new NyxException(
                ErrorCode.SCHEMA_CONFLICT,
                "Library scan already active for library " + libraryId
            ));
        }

        String now = Instant.now().toString();
        String runId = UUID.randomUUID().toString();
        sqliteWriteTransaction(jdbi, handle -> {
            handle.createUpdate("""
                    INSERT INTO library_scan_runs(
                        scan_run_id, library_id, mode, status, created_at, started_at, completed_at,
                        error_message, files_scanned, imported_count, refreshed_count, missing_count
                    ) VALUES (
                        :scanRunId, :libraryId, :mode, :status, :createdAt, :startedAt, :completedAt,
                        :errorMessage, :filesScanned, :importedCount, :refreshedCount, :missingCount
                    )
                    """)
                .bind("scanRunId", runId)
                .bind("libraryId", libraryId)
                .bind("mode", mode.name())
                .bind("status", LibraryScanRunStatus.QUEUED.name())
                .bind("createdAt", now)
                .bindNull("startedAt", Types.VARCHAR)
                .bindNull("completedAt", Types.VARCHAR)
                .bindNull("errorMessage", Types.VARCHAR)
                .bind("filesScanned", 0)
                .bind("importedCount", 0)
                .bind("refreshedCount", 0)
                .bind("missingCount", 0)
                .execute();
            PersistedLibraryStateRow stateRow = loadLibraryStateRow(handle, libraryId);
            updateLibraryScanState(
                handle,
                libraryId,
                new LibraryScanState(
                    LibraryScanStatus.RUNNING,
                    now,
                    stateRow == null ? null : stateRow.lastScanCompletedAt(),
                    null,
                    null
                ),
                now
            );
            return null;
        });
        processActiveScanRunIds.add(runId);
        LibraryScanRun queuedRun = getRun(runId);
        if (queuedRun == null) {
            throw sneakyThrow(new NyxException(
                ErrorCode.INVALID_REQUEST,
                "Failed to load queued scan run"
            ));
        }
        return queuedRun;
    }

    private void markRunRunning(String scanRunId, String libraryId, String startedAt) {
        sqliteWriteTransaction(jdbi, handle -> {
            handle.createUpdate("""
                    UPDATE library_scan_runs
                    SET status = :status,
                        started_at = :startedAt
                    WHERE scan_run_id = :scanRunId
                    """)
                .bind("status", LibraryScanRunStatus.RUNNING.name())
                .bind("startedAt", startedAt)
                .bind("scanRunId", scanRunId)
                .execute();
            PersistedLibraryStateRow stateRow = loadLibraryStateRow(handle, libraryId);
            updateLibraryScanState(
                handle,
                libraryId,
                new LibraryScanState(
                    LibraryScanStatus.RUNNING,
                    startedAt,
                    stateRow == null ? null : stateRow.lastScanCompletedAt(),
                    null,
                    null
                ),
                startedAt
            );
            return null;
        });
    }

    private LibraryScanRun completeRun(
        String scanRunId,
        String libraryId,
        ScanCounters counters,
        String completedAt
    ) {
        sqliteWriteTransaction(jdbi, handle -> {
            handle.createUpdate("""
                    UPDATE library_scan_runs
                    SET status = :status,
                        completed_at = :completedAt,
                        error_message = :errorMessage,
                        files_scanned = :filesScanned,
                        imported_count = :importedCount,
                        refreshed_count = :refreshedCount,
                        missing_count = :missingCount
                    WHERE scan_run_id = :scanRunId
                    """)
                .bind("status", LibraryScanRunStatus.COMPLETED.name())
                .bind("completedAt", completedAt)
                .bindNull("errorMessage", Types.VARCHAR)
                .bind("filesScanned", counters.filesScanned())
                .bind("importedCount", counters.importedCount())
                .bind("refreshedCount", counters.refreshedCount())
                .bind("missingCount", counters.missingCount())
                .bind("scanRunId", scanRunId)
                .execute();
            PersistedLibraryStateRow stateRow = loadLibraryStateRow(handle, libraryId);
            updateLibraryScanState(
                handle,
                libraryId,
                new LibraryScanState(
                    LibraryScanStatus.IDLE,
                    stateRow == null ? null : stateRow.lastScanStartedAt(),
                    completedAt,
                    null,
                    null
                ),
                completedAt
            );
            return null;
        });
        LibraryScanRun completedRun = getRun(scanRunId);
        if (completedRun == null) {
            throw sneakyThrow(new NyxException(
                ErrorCode.INVALID_REQUEST,
                "Completed scan run disappeared: " + scanRunId
            ));
        }
        return completedRun;
    }

    private LibraryScanRun failRun(
        String scanRunId,
        String libraryId,
        String failedAt,
        String message
    ) {
        sqliteWriteTransaction(jdbi, handle -> {
            handle.createUpdate("""
                    UPDATE library_scan_runs
                    SET status = :status,
                        completed_at = :completedAt,
                        error_message = :errorMessage
                    WHERE scan_run_id = :scanRunId
                    """)
                .bind("status", LibraryScanRunStatus.FAILED.name())
                .bind("completedAt", failedAt)
                .bind("errorMessage", message)
                .bind("scanRunId", scanRunId)
                .execute();
            PersistedLibraryStateRow stateRow = loadLibraryStateRow(handle, libraryId);
            updateLibraryScanState(
                handle,
                libraryId,
                new LibraryScanState(
                    LibraryScanStatus.FAILED,
                    stateRow == null ? null : stateRow.lastScanStartedAt(),
                    stateRow == null ? null : stateRow.lastScanCompletedAt(),
                    failedAt,
                    message
                ),
                failedAt
            );
            return null;
        });
        LibraryScanRun failedRun = getRun(scanRunId);
        if (failedRun == null) {
            throw sneakyThrow(new NyxException(
                ErrorCode.INVALID_REQUEST,
                "Failed scan run disappeared: " + scanRunId
            ));
        }
        return failedRun;
    }

    private void upsertTrackedObject(
        String libraryId,
        String scanRunId,
        String scanTimestamp,
        String sourceRootId,
        MediaObject mediaObject
    ) {
        sqliteWriteTransaction(jdbi, handle -> {
            String existingEntryId = handle.createQuery("""
                    SELECT library_entry_id
                    FROM library_entries
                    WHERE library_id = :libraryId
                      AND object_id = :objectId
                    LIMIT 1
                    """)
                .bind("libraryId", libraryId)
                .bind("objectId", mediaObject.objectId())
                .mapTo(String.class)
                .findOne()
                .orElse(null);

            if (existingEntryId == null) {
                handle.createUpdate("""
                        INSERT INTO library_entries(
                            library_entry_id, library_id, object_id, source_root_id, media_kind,
                            primary_path, path_key, status, first_scanned_at, last_scanned_at, missing_at, last_scan_run_id
                        ) VALUES (
                            :libraryEntryId, :libraryId, :objectId, :sourceRootId, :mediaKind,
                            :primaryPath, :pathKey, :status, :firstScannedAt, :lastScannedAt, :missingAt, :lastScanRunId
                        )
                        """)
                    .bind("libraryEntryId", UUID.randomUUID().toString())
                    .bind("libraryId", libraryId)
                    .bind("objectId", mediaObject.objectId())
                    .bind("sourceRootId", sourceRootId)
                    .bind("mediaKind", mediaObject.mediaKind().name())
                    .bind("primaryPath", mediaObject.primaryPath())
                    .bind("pathKey", mediaObject.pathKey())
                    .bind("status", LibraryTrackedObjectStatus.ACTIVE.name())
                    .bind("firstScannedAt", scanTimestamp)
                    .bind("lastScannedAt", scanTimestamp)
                    .bindNull("missingAt", Types.VARCHAR)
                    .bind("lastScanRunId", scanRunId)
                    .execute();
            } else {
                handle.createUpdate("""
                        UPDATE library_entries
                        SET source_root_id = :sourceRootId,
                            media_kind = :mediaKind,
                            primary_path = :primaryPath,
                            path_key = :pathKey,
                            status = :status,
                            last_scanned_at = :lastScannedAt,
                            missing_at = :missingAt,
                            last_scan_run_id = :lastScanRunId
                        WHERE library_entry_id = :libraryEntryId
                        """)
                    .bind("sourceRootId", sourceRootId)
                    .bind("mediaKind", mediaObject.mediaKind().name())
                    .bind("primaryPath", mediaObject.primaryPath())
                    .bind("pathKey", mediaObject.pathKey())
                    .bind("status", LibraryTrackedObjectStatus.ACTIVE.name())
                    .bind("lastScannedAt", scanTimestamp)
                    .bindNull("missingAt", Types.VARCHAR)
                    .bind("lastScanRunId", scanRunId)
                    .bind("libraryEntryId", existingEntryId)
                    .execute();
            }
            return null;
        });
    }

    private List<String> markTrackedObjectsMissingNotSeen(
        String libraryId,
        Set<String> seenObjectIds,
        String scanRunId,
        String scanTimestamp
    ) {
        return sqliteWriteTransaction(jdbi, handle -> {
            List<EntryObjectRow> rows;
            if (seenObjectIds.isEmpty()) {
                rows = handle.createQuery("""
                        SELECT library_entry_id, object_id
                        FROM library_entries
                        WHERE library_id = :libraryId
                          AND status = :status
                        """)
                    .bind("libraryId", libraryId)
                    .bind("status", LibraryTrackedObjectStatus.ACTIVE.name())
                    .map((resultSet, context) -> new EntryObjectRow(
                        resultSet.getString("library_entry_id"),
                        resultSet.getString("object_id")
                    ))
                    .list();
            } else {
                rows = handle.createQuery("""
                        SELECT library_entry_id, object_id
                        FROM library_entries
                        WHERE library_id = :libraryId
                          AND status = :status
                          AND object_id NOT IN (<seenObjectIds>)
                        """)
                    .bind("libraryId", libraryId)
                    .bind("status", LibraryTrackedObjectStatus.ACTIVE.name())
                    .bindList("seenObjectIds", seenObjectIds)
                    .map((resultSet, context) -> new EntryObjectRow(
                        resultSet.getString("library_entry_id"),
                        resultSet.getString("object_id")
                    ))
                    .list();
            }

            for (EntryObjectRow row : rows) {
                handle.createUpdate("""
                        UPDATE library_entries
                        SET status = :status,
                            missing_at = :missingAt,
                            last_scan_run_id = :lastScanRunId
                        WHERE library_entry_id = :libraryEntryId
                        """)
                    .bind("status", LibraryTrackedObjectStatus.MISSING.name())
                    .bind("missingAt", scanTimestamp)
                    .bind("lastScanRunId", scanRunId)
                    .bind("libraryEntryId", row.libraryEntryId())
                    .execute();
            }
            return rows.stream().map(EntryObjectRow::objectId).toList();
        });
    }

    private void markAllTrackedObjectsMissing(String libraryId, String scanRunId, String scanTimestamp) {
        List<String> activeObjectIds = sqliteWriteTransaction(jdbi, handle -> {
            List<EntryObjectRow> rows = handle.createQuery("""
                    SELECT library_entry_id, object_id
                    FROM library_entries
                    WHERE library_id = :libraryId
                      AND status = :status
                    """)
                .bind("libraryId", libraryId)
                .bind("status", LibraryTrackedObjectStatus.ACTIVE.name())
                .map((resultSet, context) -> new EntryObjectRow(
                    resultSet.getString("library_entry_id"),
                    resultSet.getString("object_id")
                ))
                .list();
            for (EntryObjectRow row : rows) {
                handle.createUpdate("""
                        UPDATE library_entries
                        SET status = :status,
                            missing_at = :missingAt,
                            last_scan_run_id = :lastScanRunId
                        WHERE library_entry_id = :libraryEntryId
                        """)
                    .bind("status", LibraryTrackedObjectStatus.MISSING.name())
                    .bind("missingAt", scanTimestamp)
                    .bind("lastScanRunId", scanRunId)
                    .bind("libraryEntryId", row.libraryEntryId())
                    .execute();
            }
            return rows.stream().map(EntryObjectRow::objectId).toList();
        });
        for (String objectId : activeObjectIds) {
            mediaObjectService.markMissing(objectId);
        }
    }

    private LibraryScanRun activeRunForLibrary(String libraryId) {
        return withHandleUnchecked(jdbi, handle -> handle.createQuery("""
                SELECT scan_run_id, library_id, mode, status, created_at, started_at, completed_at,
                       error_message, files_scanned, imported_count, refreshed_count, missing_count
                FROM library_scan_runs
                WHERE library_id = :libraryId
                  AND status IN (<statuses>)
                ORDER BY created_at DESC
                LIMIT 1
                """)
            .bind("libraryId", libraryId)
            .bindList("statuses", ACTIVE_RUN_STATUSES)
            .map((resultSet, context) -> toLibraryScanRun(resultSet))
            .findOne()
            .orElse(null));
    }

    private void recoverStaleActiveRuns(String libraryId, String recoveredAt) {
        sqliteWriteTransaction(jdbi, handle -> {
            List<LibraryScanRun> activeRows = handle.createQuery("""
                    SELECT scan_run_id, library_id, mode, status, created_at, started_at, completed_at,
                           error_message, files_scanned, imported_count, refreshed_count, missing_count
                    FROM library_scan_runs
                    WHERE library_id = :libraryId
                      AND status IN (<statuses>)
                    """)
                .bind("libraryId", libraryId)
                .bindList("statuses", ACTIVE_RUN_STATUSES)
                .map((resultSet, context) -> toLibraryScanRun(resultSet))
                .list();

            List<LibraryScanRun> staleRows = activeRows.stream()
                .filter(row -> !processActiveScanRunIds.contains(row.getScanRunId()))
                .toList();
            if (staleRows.isEmpty()) {
                return null;
            }

            String message = "Library scan did not complete before service restart";
            for (LibraryScanRun row : staleRows) {
                handle.createUpdate("""
                        UPDATE library_scan_runs
                        SET status = :status,
                            completed_at = :completedAt,
                            error_message = :errorMessage
                        WHERE scan_run_id = :scanRunId
                        """)
                    .bind("status", LibraryScanRunStatus.FAILED.name())
                    .bind("completedAt", recoveredAt)
                    .bind("errorMessage", message)
                    .bind("scanRunId", row.getScanRunId())
                    .execute();
            }

            boolean hasProcessActiveRun = activeRows.stream()
                .anyMatch(row -> processActiveScanRunIds.contains(row.getScanRunId()));
            if (!hasProcessActiveRun) {
                PersistedLibraryStateRow libraryRow = loadLibraryStateRow(handle, libraryId);
                updateLibraryScanState(
                    handle,
                    libraryId,
                    new LibraryScanState(
                        LibraryScanStatus.FAILED,
                        libraryRow == null ? null : libraryRow.lastScanStartedAt(),
                        libraryRow == null ? null : libraryRow.lastScanCompletedAt(),
                        recoveredAt,
                        message
                    ),
                    recoveredAt
                );
            }
            return null;
        });
    }

    private void ensureLibraryIsScannable(String libraryId) {
        Library library = libraryService.getLibrary(libraryId);
        if (library == null) {
            throw sneakyThrow(new NyxException(
                ErrorCode.LIBRARY_NOT_FOUND,
                "Library not found: " + libraryId
            ));
        }
        if (library.sourceRoots().isEmpty()) {
            throw sneakyThrow(new NyxException(
                ErrorCode.INVALID_REQUEST,
                "Library has no source roots: " + libraryId
            ));
        }
    }

    private static void ensureLibraryExists(Handle handle, String libraryId) {
        boolean exists = handle.createQuery("""
                SELECT 1
                FROM libraries
                WHERE library_id = :libraryId
                LIMIT 1
                """)
            .bind("libraryId", libraryId)
            .mapTo(Integer.class)
            .findOne()
            .isPresent();
        if (!exists) {
            throw sneakyThrow(new NyxException(
                ErrorCode.LIBRARY_NOT_FOUND,
                "Library not found: " + libraryId
            ));
        }
    }

    private static List<Path> collectRegularFiles(Path rootPath) {
        try (var stream = Files.walk(rootPath)) {
            return stream
                .filter(Files::isRegularFile)
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
        } catch (IOException exception) {
            throw sneakyThrow(exception);
        }
    }

    private static void requireSourceRoot(Path rootPath, String rawPath) {
        if (!Files.exists(rootPath)) {
            throw sneakyThrow(new NyxException(
                ErrorCode.FILE_NOT_FOUND,
                "Library source root not found: " + rawPath
            ));
        }
        if (!Files.isDirectory(rootPath)) {
            throw sneakyThrow(new NyxException(
                ErrorCode.INVALID_REQUEST,
                "Library source root is not a directory: " + rawPath
            ));
        }
    }

    private static Set<MediaKind> allowedMediaKinds(LibraryType type) {
        return switch (type) {
            case MOVIE, SHOW, GENERIC_VIDEO -> Set.of(MediaKind.VIDEO);
            case MUSIC -> Set.of(MediaKind.AUDIO);
            case PHOTO -> Set.of(MediaKind.IMAGE);
        };
    }

    private <T> T withLibraryLock(String libraryId, Supplier<T> block) {
        ReentrantLock lock = libraryLocks.computeIfAbsent(libraryId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return block.get();
        } finally {
            lock.unlock();
        }
    }

    private static void updateLibraryScanState(
        Handle handle,
        String libraryId,
        LibraryScanState scanState,
        String updatedAt
    ) {
        handle.createUpdate("""
                UPDATE libraries
                SET scan_status = :scanStatus,
                    last_scan_started_at = :lastScanStartedAt,
                    last_scan_completed_at = :lastScanCompletedAt,
                    last_scan_failed_at = :lastScanFailedAt,
                    last_scan_error = :lastScanError,
                    updated_at = :updatedAt
                WHERE library_id = :libraryId
                """)
            .bind("scanStatus", scanState.status().name())
            .bind("lastScanStartedAt", scanState.lastScanStartedAt())
            .bind("lastScanCompletedAt", scanState.lastScanCompletedAt())
            .bind("lastScanFailedAt", scanState.lastScanFailedAt())
            .bind("lastScanError", scanState.lastScanError())
            .bind("updatedAt", updatedAt)
            .bind("libraryId", libraryId)
            .execute();
    }

    private static PersistedLibraryStateRow loadLibraryStateRow(Handle handle, String libraryId) {
        return handle.createQuery("""
                SELECT last_scan_started_at, last_scan_completed_at, last_scan_failed_at, last_scan_error
                FROM libraries
                WHERE library_id = :libraryId
                """)
            .bind("libraryId", libraryId)
            .map((resultSet, context) -> new PersistedLibraryStateRow(
                resultSet.getString("last_scan_started_at"),
                resultSet.getString("last_scan_completed_at"),
                resultSet.getString("last_scan_failed_at"),
                resultSet.getString("last_scan_error")
            ))
            .findOne()
            .orElse(null);
    }

    private static LibraryScanRun toLibraryScanRun(ResultSet resultSet) throws SQLException {
        return new LibraryScanRun(
            resultSet.getString("scan_run_id"),
            resultSet.getString("library_id"),
            LibraryScanMode.valueOf(resultSet.getString("mode")),
            LibraryScanRunStatus.valueOf(resultSet.getString("status")),
            resultSet.getString("created_at"),
            resultSet.getString("started_at"),
            resultSet.getString("completed_at"),
            resultSet.getString("error_message"),
            resultSet.getInt("files_scanned"),
            resultSet.getInt("imported_count"),
            resultSet.getInt("refreshed_count"),
            resultSet.getInt("missing_count")
        );
    }

    private static LibraryTrackedObject toLibraryTrackedObject(ResultSet resultSet) throws SQLException {
        return new LibraryTrackedObject(
            resultSet.getString("library_entry_id"),
            resultSet.getString("library_id"),
            resultSet.getString("object_id"),
            resultSet.getString("source_root_id"),
            MediaKind.valueOf(resultSet.getString("media_kind")),
            resultSet.getString("primary_path"),
            resultSet.getString("path_key"),
            LibraryTrackedObjectStatus.valueOf(resultSet.getString("status")),
            resultSet.getString("first_scanned_at"),
            resultSet.getString("last_scanned_at"),
            resultSet.getString("missing_at"),
            resultSet.getString("last_scan_run_id")
        );
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }

    private record ScanCounters(
        int filesScanned,
        int importedCount,
        int refreshedCount,
        int missingCount
    ) {}

    private record PersistedLibraryStateRow(
        String lastScanStartedAt,
        String lastScanCompletedAt,
        String lastScanFailedAt,
        String lastScanError
    ) {}

    private record SourceRootFiles(
        String sourceRootId,
        List<Path> files
    ) {}

    private record EntryObjectRow(
        String libraryEntryId,
        String objectId
    ) {}
}
