package com.nyx.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.common.DatabaseResources;
import com.nyx.media.contracts.CreateLibraryRequest;
import com.nyx.media.contracts.LibraryScanStatus;
import com.nyx.media.contracts.LibrarySourceRootWriteRequest;
import com.nyx.media.contracts.LibraryType;
import com.nyx.media.contracts.MediaObjectStatus;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryScanServiceTest {
    @TempDir
    Path tempDir;

    private final List<HikariDataSource> dataSources = new ArrayList<>();
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        backgroundExecutor.shutdownNow();
        ModuleMediaTestSupport.closeDataSources(dataSources);
    }

    private TestServices createServices() {
        DatabaseResources libraryResources = LibraryService.createDatabase(tempDir.resolve("libraries-db"));
        DatabaseResources mediaResources = MediaObjectService.createDatabase(tempDir.resolve("media-db"));
        dataSources.add(libraryResources.getDataSource());
        dataSources.add(mediaResources.getDataSource());

        MediaObjectService mediaObjectService = new MediaObjectService(mediaResources.getJdbi());
        MediaObjectResolver resolver = new MediaObjectResolver(
            mediaObjectService,
            new com.nyx.ffmpeg.ProbeService(),
            new AudioMetadataService(new com.nyx.ffmpeg.ProbeService())
        );
        LibraryService libraryService = new LibraryService(libraryResources.getJdbi());
        LibraryScanService libraryScanService = new LibraryScanService(
            libraryResources.getJdbi(),
            libraryService,
            mediaObjectService,
            resolver,
            backgroundExecutor
        );

        return new TestServices(
            libraryResources.getJdbi(),
            libraryService,
            mediaObjectService,
            libraryScanService
        );
    }

    private Path createVideoFile(Path root, String name, int size) throws Exception {
        Path path = root.resolve(name);
        Files.createDirectories(path.getParent());
        return Files.write(path, new byte[size]);
    }

    private Path createVideoFile(Path root, String name) throws Exception {
        return createVideoFile(root, name, 1_024);
    }

    @Test
    void scanImportRescanAndRemovalCleanupReuseMediaObjectIdentityAndMarkStaleEntriesMissing() throws Exception {
        TestServices services = createServices();
        Path libraryRoot = Files.createDirectories(tempDir.resolve("movies"));
        Path firstFile = createVideoFile(libraryRoot, "first.mp4", 1_024);
        Path removedFile = createVideoFile(libraryRoot, "removed.mp4", 2_048);
        var library = services.libraryService().createLibrary(
            new CreateLibraryRequest(
                "Movies",
                LibraryType.MOVIE,
                List.of(new LibrarySourceRootWriteRequest(libraryRoot.toString()))
            )
        );

        var importRun = services.libraryScanService().runScanNow(library.libraryId(), LibraryScanMode.IMPORT);
        String removedObjectId = services.mediaObjectService().getByPath(removedFile.toString()).objectId();

        assertEquals(LibraryScanRunStatus.COMPLETED, importRun.status());
        assertEquals(2, importRun.filesScanned());
        assertEquals(2, importRun.importedCount());
        assertEquals(0, importRun.refreshedCount());
        assertEquals(0, importRun.missingCount());

        Files.delete(removedFile);
        Path addedFile = createVideoFile(libraryRoot, "added.mp4", 4_096);

        var rescanRun = services.libraryScanService().runScanNow(library.libraryId(), LibraryScanMode.RESCAN);
        var trackedObjects = services.libraryScanService().listTrackedObjects(library.libraryId());
        Set<String> activePaths = trackedObjects.stream()
            .filter(object -> object.status() == LibraryTrackedObjectStatus.ACTIVE)
            .map(LibraryTrackedObject::primaryPath)
            .collect(Collectors.toSet());

        assertEquals(LibraryScanRunStatus.COMPLETED, rescanRun.status());
        assertEquals(2, rescanRun.filesScanned());
        assertEquals(1, rescanRun.importedCount());
        assertEquals(1, rescanRun.refreshedCount());
        assertEquals(1, rescanRun.missingCount());
        assertEquals(Set.of(firstFile.toString(), addedFile.toString()), activePaths);
        assertEquals(
            LibraryTrackedObjectStatus.MISSING,
            trackedObjects.stream()
                .filter(object -> object.objectId().equals(removedObjectId))
                .findFirst()
                .orElseThrow()
                .status()
        );
        assertEquals(
            MediaObjectStatus.MISSING,
            services.mediaObjectService().getByObjectId(removedObjectId).status()
        );
    }

    @Test
    void rebuildReactivatesTrackedObjectsWithoutLeavingDuplicateActiveEntries() throws Exception {
        TestServices services = createServices();
        Path libraryRoot = Files.createDirectories(tempDir.resolve("shows"));
        Path episode = createVideoFile(libraryRoot, "episode01.mp4");
        var library = services.libraryService().createLibrary(
            new CreateLibraryRequest(
                "Shows",
                LibraryType.SHOW,
                List.of(new LibrarySourceRootWriteRequest(libraryRoot.toString()))
            )
        );

        services.libraryScanService().runScanNow(library.libraryId(), LibraryScanMode.IMPORT);
        var rebuildRun = services.libraryScanService().runScanNow(library.libraryId(), LibraryScanMode.REBUILD);
        var trackedObjects = services.libraryScanService().listTrackedObjects(library.libraryId());
        var activeObjects = trackedObjects.stream()
            .filter(object -> object.status() == LibraryTrackedObjectStatus.ACTIVE)
            .toList();

        assertEquals(LibraryScanRunStatus.COMPLETED, rebuildRun.status());
        assertEquals(1, rebuildRun.filesScanned());
        assertEquals(1, activeObjects.size());
        assertEquals(episode.toString(), activeObjects.getFirst().primaryPath());
        assertEquals(1, activeObjects.stream().map(LibraryTrackedObject::objectId).distinct().count());
        assertNotNull(services.mediaObjectService().getByPath(episode.toString()));
    }

    @Test
    void failedRebuildWithMissingSourceRootPreservesActiveTrackedMembership() throws Exception {
        TestServices services = createServices();
        Path libraryRoot = Files.createDirectories(tempDir.resolve("missing-rebuild-root"));
        Path episode = createVideoFile(libraryRoot, "episode01.mp4");
        var library = services.libraryService().createLibrary(
            new CreateLibraryRequest(
                "Shows",
                LibraryType.SHOW,
                List.of(new LibrarySourceRootWriteRequest(libraryRoot.toString()))
            )
        );

        services.libraryScanService().runScanNow(library.libraryId(), LibraryScanMode.IMPORT);
        String objectId = services.mediaObjectService().getByPath(episode.toString()).objectId();

        Files.delete(episode);
        Files.delete(libraryRoot);

        var rebuildRun = services.libraryScanService().runScanNow(library.libraryId(), LibraryScanMode.REBUILD);
        var trackedObjects = services.libraryScanService().listTrackedObjects(library.libraryId());

        assertEquals(LibraryScanRunStatus.FAILED, rebuildRun.status());
        assertTrue(rebuildRun.errorMessage() != null && rebuildRun.errorMessage().contains("not found"));
        assertEquals(
            1,
            trackedObjects.stream().filter(object -> object.status() == LibraryTrackedObjectStatus.ACTIVE).count()
        );
        assertEquals(
            LibraryTrackedObjectStatus.ACTIVE,
            trackedObjects.stream()
                .filter(object -> object.objectId().equals(objectId))
                .findFirst()
                .orElseThrow()
                .status()
        );
        assertEquals(
            MediaObjectStatus.ACTIVE,
            services.mediaObjectService().getByObjectId(objectId).status()
        );
    }

    @Test
    void staleActiveScanRowsAreRecoveredBeforeStartingANewScan() {
        TestServices services = createServices();
        Path libraryRoot = tempDir.resolve("stale-active-scan");
        try {
            Files.createDirectories(libraryRoot);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
        var library = services.libraryService().createLibrary(
            new CreateLibraryRequest(
                "Movies",
                LibraryType.MOVIE,
                List.of(new LibrarySourceRootWriteRequest(libraryRoot.toString()))
            )
        );
        String staleRunId = "stale-run";

        services.libraryJdbi().useHandle(handle ->
            handle.createUpdate(
                """
                INSERT INTO library_scan_runs(
                    scan_run_id, library_id, mode, status, created_at, started_at, completed_at,
                    error_message, files_scanned, imported_count, refreshed_count, missing_count
                ) VALUES (
                    :scanRunId, :libraryId, :mode, :status, :createdAt, :startedAt, :completedAt,
                    :errorMessage, :filesScanned, :importedCount, :refreshedCount, :missingCount
                )
                """
            )
                .bind("scanRunId", staleRunId)
                .bind("libraryId", library.libraryId())
                .bind("mode", LibraryScanMode.IMPORT.name())
                .bind("status", LibraryScanRunStatus.RUNNING.name())
                .bind("createdAt", "2026-05-02T00:00:00Z")
                .bind("startedAt", "2026-05-02T00:00:00Z")
                .bindNull("completedAt", Types.VARCHAR)
                .bindNull("errorMessage", Types.VARCHAR)
                .bind("filesScanned", 0)
                .bind("importedCount", 0)
                .bind("refreshedCount", 0)
                .bind("missingCount", 0)
                .execute()
        );

        var run = services.libraryScanService().runScanNow(library.libraryId(), LibraryScanMode.IMPORT);
        var staleRun = services.libraryScanService().listRuns(library.libraryId()).stream()
            .filter(scanRun -> scanRun.scanRunId().equals(staleRunId))
            .findFirst()
            .orElseThrow();

        assertEquals(LibraryScanRunStatus.COMPLETED, run.status());
        assertEquals(LibraryScanRunStatus.FAILED, staleRun.status());
        assertTrue(staleRun.errorMessage() != null && staleRun.errorMessage().contains("did not complete"));
    }

    @Test
    void refreshOnlyRevisitsTrackedActiveObjectsWithoutImportingNewFilesOrCleaningStaleMembership() throws Exception {
        TestServices services = createServices();
        Path libraryRoot = Files.createDirectories(tempDir.resolve("refresh-library"));
        Path keepFile = createVideoFile(libraryRoot, "keep.mp4");
        Path staleFile = createVideoFile(libraryRoot, "stale.mp4");
        var library = services.libraryService().createLibrary(
            new CreateLibraryRequest(
                "Refresh Test",
                LibraryType.MOVIE,
                List.of(new LibrarySourceRootWriteRequest(libraryRoot.toString()))
            )
        );

        services.libraryScanService().runScanNow(library.libraryId(), LibraryScanMode.IMPORT);
        String staleObjectId = services.mediaObjectService().getByPath(staleFile.toString()).objectId();

        Files.delete(staleFile);
        Path addedFile = createVideoFile(libraryRoot, "added.mp4");

        var refreshRun = services.libraryScanService().runScanNow(library.libraryId(), LibraryScanMode.REFRESH);
        var trackedObjects = services.libraryScanService().listTrackedObjects(library.libraryId());

        assertEquals(LibraryScanRunStatus.COMPLETED, refreshRun.status());
        assertEquals(1, refreshRun.filesScanned());
        assertEquals(0, refreshRun.importedCount());
        assertEquals(1, refreshRun.refreshedCount());
        assertEquals(1, refreshRun.missingCount());
        assertEquals(
            2,
            trackedObjects.stream().filter(object -> object.status() == LibraryTrackedObjectStatus.ACTIVE).count()
        );
        assertEquals(
            0,
            trackedObjects.stream().filter(object -> object.status() == LibraryTrackedObjectStatus.MISSING).count()
        );
        assertTrue(trackedObjects.stream().anyMatch(object -> object.primaryPath().equals(keepFile.toString())));
        assertTrue(trackedObjects.stream().anyMatch(object -> object.primaryPath().equals(staleFile.toString())));
        assertEquals(
            LibraryTrackedObjectStatus.ACTIVE,
            trackedObjects.stream()
                .filter(object -> object.objectId().equals(staleObjectId))
                .findFirst()
                .orElseThrow()
                .status()
        );
        assertTrue(trackedObjects.stream().noneMatch(object -> object.primaryPath().equals(addedFile.toString())));
        assertNull(services.mediaObjectService().getByPath(addedFile.toString()));
    }

    @Test
    void scanRecordsFailureDetailsWhenASourceRootIsMissing() {
        TestServices services = createServices();
        Path missingRoot = tempDir.resolve("missing-root");
        var library = services.libraryService().createLibrary(
            new CreateLibraryRequest(
                "Photos",
                LibraryType.PHOTO,
                List.of(new LibrarySourceRootWriteRequest(missingRoot.toString()))
            )
        );

        var run = services.libraryScanService().runScanNow(library.libraryId(), LibraryScanMode.IMPORT);
        var updatedLibrary = services.libraryService().getLibrary(library.libraryId());

        assertEquals(LibraryScanRunStatus.FAILED, run.status());
        assertTrue(run.errorMessage() != null && run.errorMessage().contains("not found"));
        assertEquals(LibraryScanStatus.FAILED, updatedLibrary.scanState().status());
        assertTrue(
            updatedLibrary.scanState().lastScanError() != null
                && updatedLibrary.scanState().lastScanError().contains("not found")
        );
    }

    private record TestServices(
        Jdbi libraryJdbi,
        LibraryService libraryService,
        MediaObjectService mediaObjectService,
        LibraryScanService libraryScanService
    ) {
    }
}
