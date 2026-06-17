package com.nyx.media;

import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.media.contracts.Library;
import com.nyx.media.contracts.LibraryItem;
import java.util.List;

public final class LibraryExtensionCoordinator {
    private final LibraryService libraryService;
    private final LibraryCatalogService libraryCatalogService;
    private final List<LibraryMetadataProvider> metadataProviders;
    private final List<LibraryArtworkProvider> artworkProviders;
    private final List<LibraryCollectionBuilder> collectionBuilders;
    private final List<LibraryScanHook> scanHooks;
    private final List<LibraryScheduledJob> scheduledJobs;

    public LibraryExtensionCoordinator(LibraryService libraryService, LibraryCatalogService libraryCatalogService) {
        this(libraryService, libraryCatalogService, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public LibraryExtensionCoordinator(
        LibraryService libraryService,
        LibraryCatalogService libraryCatalogService,
        List<LibraryMetadataProvider> metadataProviders,
        List<LibraryArtworkProvider> artworkProviders,
        List<LibraryCollectionBuilder> collectionBuilders,
        List<LibraryScanHook> scanHooks,
        List<LibraryScheduledJob> scheduledJobs
    ) {
        this.libraryService = libraryService;
        this.libraryCatalogService = libraryCatalogService;
        this.metadataProviders = metadataProviders == null ? List.of() : List.copyOf(metadataProviders);
        this.artworkProviders = artworkProviders == null ? List.of() : List.copyOf(artworkProviders);
        this.collectionBuilders = collectionBuilders == null ? List.of() : List.copyOf(collectionBuilders);
        this.scanHooks = scanHooks == null ? List.of() : List.copyOf(scanHooks);
        this.scheduledJobs = scheduledJobs == null ? List.of() : List.copyOf(scheduledJobs);
    }

    public RegisteredLibraryExtensions registeredExtensions() {
        return new RegisteredLibraryExtensions(
            metadataProviders.stream().map(LibraryMetadataProvider::getProviderId).toList(),
            artworkProviders.stream().map(LibraryArtworkProvider::getProviderId).toList(),
            collectionBuilders.stream().map(LibraryCollectionBuilder::getBuilderId).toList(),
            scanHooks.stream().map(LibraryScanHook::getHookId).toList(),
            scheduledJobs.stream().map(LibraryScheduledJob::getJobId).toList()
        );
    }

    public LibraryExtensionRunSummary afterSuccessfulScan(LibraryScanRun scanRun, List<LibraryTrackedObject> trackedObjects) {
        return invokeExtensions(scanRun.getLibraryId(), LibraryJobTrigger.SCAN, scanRun, trackedObjects);
    }

    public LibraryExtensionRunSummary runRepair(String libraryId) {
        return invokeExtensions(libraryId, LibraryJobTrigger.REPAIR, null, List.of());
    }

    private LibraryExtensionRunSummary invokeExtensions(
        String libraryId,
        LibraryJobTrigger trigger,
        LibraryScanRun scanRun,
        List<LibraryTrackedObject> trackedObjects
    ) {
        String normalizedLibraryId = libraryId.trim();
        Library library = libraryService.getLibrary(normalizedLibraryId);
        if (library == null) {
            throw sneakyNyxException(ErrorCode.LIBRARY_NOT_FOUND, "Library not found: " + normalizedLibraryId);
        }
        List<LibraryItem> items = libraryCatalogService.listAllLibraryItems(normalizedLibraryId);

        for (LibraryMetadataProvider metadataProvider : metadataProviders) {
            metadataProvider.refresh(library, items, libraryCatalogService);
        }
        for (LibraryArtworkProvider artworkProvider : artworkProviders) {
            artworkProvider.refresh(library, items, libraryCatalogService);
        }
        for (LibraryCollectionBuilder collectionBuilder : collectionBuilders) {
            collectionBuilder.rebuildCollections(library, items, libraryCatalogService);
        }
        if (scanRun != null) {
            for (LibraryScanHook scanHook : scanHooks) {
                scanHook.afterSuccessfulScan(library, scanRun, trackedObjects, items);
            }
        }
        for (LibraryScheduledJob scheduledJob : scheduledJobs) {
            scheduledJob.run(library, items, trigger);
        }

        return new LibraryExtensionRunSummary(
            metadataProviders.stream().map(LibraryMetadataProvider::getProviderId).toList(),
            artworkProviders.stream().map(LibraryArtworkProvider::getProviderId).toList(),
            collectionBuilders.stream().map(LibraryCollectionBuilder::getBuilderId).toList(),
            scanRun != null ? scanHooks.stream().map(LibraryScanHook::getHookId).toList() : List.of(),
            scheduledJobs.stream().map(LibraryScheduledJob::getJobId).toList()
        );
    }

    private static RuntimeException sneakyNyxException(ErrorCode errorCode, String message) {
        return LibraryExtensionCoordinator.<RuntimeException>sneakyThrow(new NyxException(errorCode, message));
    }

    private static <T> T sneakyThrow(Throwable throwable) {
        LibraryExtensionCoordinator.<RuntimeException>throwUnchecked(throwable);
        throw new AssertionError("Unreachable");
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void throwUnchecked(Throwable throwable) throws E {
        throw (E) throwable;
    }
}
