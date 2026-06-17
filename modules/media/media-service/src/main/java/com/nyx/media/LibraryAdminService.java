package com.nyx.media;

import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.media.contracts.Library;
import com.nyx.media.contracts.LibraryItem;
import com.nyx.media.contracts.LibraryItemType;
import java.util.List;
import java.util.Set;

public final class LibraryAdminService {
    private final LibraryService libraryService;
    private final LibraryScanService libraryScanService;
    private final LibraryInterpretationService libraryInterpretationService;
    private final LibraryCatalogService libraryCatalogService;
    private final LibraryExtensionCoordinator libraryExtensionCoordinator;

    public LibraryAdminService(
        LibraryService libraryService,
        LibraryScanService libraryScanService,
        LibraryInterpretationService libraryInterpretationService,
        LibraryCatalogService libraryCatalogService
    ) {
        this(
            libraryService,
            libraryScanService,
            libraryInterpretationService,
            libraryCatalogService,
            null
        );
    }

    public LibraryAdminService(
        LibraryService libraryService,
        LibraryScanService libraryScanService,
        LibraryInterpretationService libraryInterpretationService,
        LibraryCatalogService libraryCatalogService,
        LibraryExtensionCoordinator libraryExtensionCoordinator
    ) {
        this.libraryService = libraryService;
        this.libraryScanService = libraryScanService;
        this.libraryInterpretationService = libraryInterpretationService;
        this.libraryCatalogService = libraryCatalogService;
        this.libraryExtensionCoordinator = libraryExtensionCoordinator;
    }

    public LibraryAdminDiagnostics getDiagnostics(String libraryId) {
        String normalizedLibraryId = libraryId.trim();
        Library library = libraryService.getLibrary(normalizedLibraryId);
        if (library == null) {
            sneakyThrow(new NyxException(
                ErrorCode.LIBRARY_NOT_FOUND,
                "Library not found: " + normalizedLibraryId
            ));
        }
        LibraryScanRun latestRun = libraryScanService.listRuns(normalizedLibraryId, 1)
            .stream()
            .findFirst()
            .orElse(null);
        List<LibraryTrackedObject> trackedObjects = libraryScanService.listTrackedObjects(normalizedLibraryId);
        List<LibraryItem> items = libraryCatalogService.listAllLibraryItems(normalizedLibraryId);
        Set<String> referencedObjectIds = items.stream()
            .map(LibraryItem::sourceObjectId)
            .filter(value -> value != null && !value.isBlank())
            .collect(java.util.stream.Collectors.toSet());
        List<LibraryOrphanedRecord> orphanedRecords = trackedObjects.stream()
            .filter(tracked -> tracked.getStatus() == LibraryTrackedObjectStatus.ACTIVE)
            .filter(tracked -> !referencedObjectIds.contains(tracked.getObjectId()))
            .map(tracked -> new LibraryOrphanedRecord(
                tracked.getLibraryEntryId(),
                tracked.getObjectId(),
                tracked.getPrimaryPath(),
                tracked.getStatus()
            ))
            .toList();
        List<LibraryItem> unmatchedItems = items.stream()
            .filter(item -> item.type() == LibraryItemType.UNMATCHED)
            .toList();
        List<LibraryItem> genericItems = items.stream()
            .filter(item -> item.type() == LibraryItemType.VIDEO)
            .toList();

        return new LibraryAdminDiagnostics(
            library,
            latestRun,
            new LibraryTrackedObjectCounts(
                (int) trackedObjects.stream().filter(tracked -> tracked.getStatus() == LibraryTrackedObjectStatus.ACTIVE).count(),
                (int) trackedObjects.stream().filter(tracked -> tracked.getStatus() == LibraryTrackedObjectStatus.MISSING).count(),
                orphanedRecords.size()
            ),
            new LibraryItemDiagnostics(
                items.size(),
                unmatchedItems.size(),
                genericItems.size()
            ),
            unmatchedItems,
            genericItems,
            orphanedRecords,
            libraryExtensionCoordinator == null ? new RegisteredLibraryExtensions() : libraryExtensionCoordinator.registeredExtensions()
        );
    }

    public LibraryRepairResult rebuildDerivedState(String libraryId) {
        String normalizedLibraryId = libraryId.trim();
        Library library = libraryService.getLibrary(normalizedLibraryId);
        if (library == null) {
            sneakyThrow(new NyxException(
                ErrorCode.LIBRARY_NOT_FOUND,
                "Library not found: " + normalizedLibraryId
            ));
        }
        libraryInterpretationService.rebuildLibraryItems(library.libraryId());
        libraryCatalogService.refreshLocalEnrichment(library.libraryId());
        LibraryExtensionRunSummary extensionSummary = libraryExtensionCoordinator == null
            ? new LibraryExtensionRunSummary()
            : libraryExtensionCoordinator.runRepair(library.libraryId());
        int itemCount = libraryCatalogService.listAllLibraryItems(library.libraryId()).size();
        return new LibraryRepairResult(
            library.libraryId(),
            itemCount,
            extensionSummary
        );
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }
}
