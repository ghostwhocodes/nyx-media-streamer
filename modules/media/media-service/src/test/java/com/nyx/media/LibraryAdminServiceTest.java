package com.nyx.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.nyx.ffmpeg.ProbeService;
import com.nyx.media.contracts.CreateLibraryRequest;
import com.nyx.media.contracts.LibrarySourceRootWriteRequest;
import com.nyx.media.contracts.LibraryType;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryAdminServiceTest {
    @TempDir
    Path tempDir;

    private final List<HikariDataSource> dataSources = new ArrayList<>();
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        backgroundExecutor.shutdownNow();
        ModuleMediaTestSupport.closeDataSources(dataSources);
    }

    private TestServices createServices(
        boolean wireDerivedStateIntoScan,
        List<LibraryMetadataProvider> metadataProviders,
        List<LibraryArtworkProvider> artworkProviders,
        List<LibraryCollectionBuilder> collectionBuilders,
        List<LibraryScanHook> scanHooks,
        List<LibraryScheduledJob> scheduledJobs
    ) {
        var libraryResources = LibraryService.createDatabase(tempDir.resolve("libraries-db"));
        var mediaResources = MediaObjectService.createDatabase(tempDir.resolve("media-db"));
        dataSources.add(libraryResources.getDataSource());
        dataSources.add(mediaResources.getDataSource());

        MediaObjectService mediaObjectService = new MediaObjectService(mediaResources.getJdbi());
        LibraryService libraryService = new LibraryService(libraryResources.getJdbi());
        LibraryInterpretationService interpretationService = new LibraryInterpretationService(
            libraryResources.getJdbi(),
            libraryService,
            mediaObjectService
        );
        LibraryCatalogService catalogService = new LibraryCatalogService(libraryResources.getJdbi(), libraryService);
        LibraryExtensionCoordinator extensionCoordinator = new LibraryExtensionCoordinator(
            libraryService,
            catalogService,
            metadataProviders,
            artworkProviders,
            collectionBuilders,
            scanHooks,
            scheduledJobs
        );
        MediaObjectResolver resolver = new MediaObjectResolver(
            mediaObjectService,
            new ProbeService(),
            new AudioMetadataService(new ProbeService())
        );
        LibraryScanService scanService = new LibraryScanService(
            libraryResources.getJdbi(),
            libraryService,
            mediaObjectService,
            resolver,
            backgroundExecutor,
            wireDerivedStateIntoScan ? interpretationService : null,
            wireDerivedStateIntoScan ? catalogService : null,
            wireDerivedStateIntoScan ? extensionCoordinator : null
        );
        LibraryAdminService adminService = new LibraryAdminService(
            libraryService,
            scanService,
            interpretationService,
            catalogService,
            extensionCoordinator
        );
        return new TestServices(libraryService, scanService, adminService);
    }

    private TestServices createServices() {
        return createServices(true, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void libraryAdminDiagnosticsExposeGenericItemsAndInvokeRegisteredExtensions() throws Exception {
        Counter metadataInvocations = new Counter();
        Counter artworkInvocations = new Counter();
        Counter collectionInvocations = new Counter();
        Counter hookInvocations = new Counter();
        Counter scheduledJobInvocations = new Counter();

        TestServices services = createServices(
            true,
            List.of(new LibraryMetadataProvider() {
                @Override
                public String getProviderId() {
                    return "test-metadata";
                }

                @Override
                public void refresh(
                    com.nyx.media.contracts.Library library,
                    List<com.nyx.media.contracts.LibraryItem> items,
                    LibraryCatalogService catalogService
                ) {
                    metadataInvocations.increment();
                }
            }),
            List.of(new LibraryArtworkProvider() {
                @Override
                public String getProviderId() {
                    return "test-artwork";
                }

                @Override
                public void refresh(
                    com.nyx.media.contracts.Library library,
                    List<com.nyx.media.contracts.LibraryItem> items,
                    LibraryCatalogService catalogService
                ) {
                    artworkInvocations.increment();
                }
            }),
            List.of(new LibraryCollectionBuilder() {
                @Override
                public String getBuilderId() {
                    return "test-collections";
                }

                @Override
                public void rebuildCollections(
                    com.nyx.media.contracts.Library library,
                    List<com.nyx.media.contracts.LibraryItem> items,
                    LibraryCatalogService catalogService
                ) {
                    collectionInvocations.increment();
                }
            }),
            List.of(new LibraryScanHook() {
                @Override
                public String getHookId() {
                    return "test-scan-hook";
                }

                @Override
                public void afterSuccessfulScan(
                    com.nyx.media.contracts.Library library,
                    LibraryScanRun scanRun,
                    List<LibraryTrackedObject> trackedObjects,
                    List<com.nyx.media.contracts.LibraryItem> items
                ) {
                    hookInvocations.increment();
                }
            }),
            List.of(new LibraryScheduledJob() {
                @Override
                public String getJobId() {
                    return "test-scheduled-job";
                }

                @Override
                public void run(
                    com.nyx.media.contracts.Library library,
                    List<com.nyx.media.contracts.LibraryItem> items,
                    LibraryJobTrigger trigger
                ) {
                    scheduledJobInvocations.increment();
                }
            })
        );

        Path root = Files.createDirectories(tempDir.resolve("videos"));
        ModuleMediaTestSupport.writeMediaFile(root.resolve("concert.mp4"));

        var library = services.libraryService.createLibrary(
            new CreateLibraryRequest(
                "Videos",
                LibraryType.GENERIC_VIDEO,
                List.of(new LibrarySourceRootWriteRequest(root.toString()))
            )
        );

        services.scanService.runScanNow(library.libraryId(), LibraryScanMode.IMPORT);

        var diagnostics = services.adminService.getDiagnostics(library.libraryId());
        assertEquals(1, diagnostics.items().total());
        assertEquals(1, diagnostics.items().generic());
        assertEquals(1, diagnostics.genericItems().size());
        assertEquals(0, diagnostics.items().unmatched());
        assertEquals(List.of("test-metadata"), diagnostics.registeredExtensions().metadataProviders());
        assertEquals(List.of("test-artwork"), diagnostics.registeredExtensions().artworkProviders());
        assertEquals(List.of("test-collections"), diagnostics.registeredExtensions().collectionBuilders());
        assertEquals(List.of("test-scan-hook"), diagnostics.registeredExtensions().scanHooks());
        assertEquals(List.of("test-scheduled-job"), diagnostics.registeredExtensions().scheduledJobs());

        assertEquals(1, metadataInvocations.value);
        assertEquals(1, artworkInvocations.value);
        assertEquals(1, collectionInvocations.value);
        assertEquals(1, hookInvocations.value);
        assertEquals(1, scheduledJobInvocations.value);

        var repair = services.adminService.rebuildDerivedState(library.libraryId());
        assertEquals(1, repair.itemCount());
        assertEquals(List.of("test-metadata"), repair.extensionSummary().metadataProvidersInvoked());
        assertEquals(List.of("test-artwork"), repair.extensionSummary().artworkProvidersInvoked());
        assertEquals(List.of("test-collections"), repair.extensionSummary().collectionBuildersInvoked());
        assertEquals(List.of(), repair.extensionSummary().scanHooksInvoked());
        assertEquals(List.of("test-scheduled-job"), repair.extensionSummary().scheduledJobsInvoked());

        assertEquals(2, metadataInvocations.value);
        assertEquals(2, artworkInvocations.value);
        assertEquals(2, collectionInvocations.value);
        assertEquals(1, hookInvocations.value);
        assertEquals(2, scheduledJobInvocations.value);
    }

    private record TestServices(
        LibraryService libraryService,
        LibraryScanService scanService,
        LibraryAdminService adminService
    ) {
    }

    private static final class Counter {
        private int value;

        void increment() {
            value += 1;
        }
    }
}
