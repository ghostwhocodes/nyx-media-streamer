package com.nyx.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.common.DatabaseResources;
import com.nyx.media.contracts.CreateLibraryCollectionRequest;
import com.nyx.media.contracts.CreateLibraryRequest;
import com.nyx.media.contracts.LibraryArtworkKind;
import com.nyx.media.contracts.LibraryArtworkSource;
import com.nyx.media.contracts.LibrarySourceRootWriteRequest;
import com.nyx.media.contracts.LibraryType;
import com.nyx.media.contracts.ReplaceLibraryItemArtworkRequest;
import com.nyx.media.contracts.ReplaceLibraryItemMetadataRequest;
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

class LibraryCatalogServiceTest {
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
        LibraryService libraryService = new LibraryService(libraryResources.getJdbi());
        LibraryInterpretationService interpretationService = new LibraryInterpretationService(
            libraryResources.getJdbi(),
            libraryService,
            mediaObjectService
        );
        LibraryCatalogService catalogService = new LibraryCatalogService(
            libraryResources.getJdbi(),
            libraryService
        );
        MediaObjectResolver resolver = new MediaObjectResolver(
            mediaObjectService,
            new com.nyx.ffmpeg.ProbeService(),
            new AudioMetadataService(new com.nyx.ffmpeg.ProbeService())
        );

        LibraryScanService scanService = new LibraryScanService(
            libraryResources.getJdbi(),
            libraryService,
            mediaObjectService,
            resolver,
            backgroundExecutor,
            interpretationService,
            catalogService
        );

        return new TestServices(libraryService, catalogService, scanService);
    }

    private Path writeFile(Path path, int size) throws Exception {
        Files.createDirectories(path.getParent());
        return Files.write(path, new byte[size]);
    }

    private Path writeFile(Path path) throws Exception {
        return writeFile(path, 2_048);
    }

    @Test
    void catalogAppliesImportedMetadataAndArtworkAndPreservesManualOverrides() throws Exception {
        TestServices services = createServices();
        Path root = Files.createDirectories(tempDir.resolve("movies"));
        Path movieDir = Files.createDirectories(root.resolve("Example Movie"));
        writeFile(movieDir.resolve("Example.Movie.2024.mkv"));
        writeFile(movieDir.resolve("poster.jpg"));
        writeFile(movieDir.resolve("fanart.jpg"));
        writeFile(movieDir.resolve("thumb.jpg"));
        Files.writeString(
            movieDir.resolve("Example.Movie.2024.nfo"),
            """
            <movie>
              <title>Imported Title</title>
              <sorttitle>Imported Sort</sorttitle>
              <plot>Imported overview</plot>
              <tag>Drama</tag>
              <tag>Local</tag>
            </movie>
            """
        );
        Path manualPoster = writeFile(movieDir.resolve("manual-poster.png"));

        var library = services.libraryService().createLibrary(
            new CreateLibraryRequest(
                "Movies",
                LibraryType.MOVIE,
                List.of(new LibrarySourceRootWriteRequest(root.toString()))
            )
        );

        services.scanService().runScanNow(library.libraryId(), LibraryScanMode.IMPORT);

        var importedItem = services.catalogService().listLibraryItems(library.libraryId()).items().getFirst();
        assertEquals("Imported Title", importedItem.displayTitle());
        assertEquals("Imported Sort", importedItem.sortTitle());
        assertEquals("Imported overview", importedItem.overview());
        assertEquals(List.of("Drama", "Local"), importedItem.tags());
        assertEquals(3, importedItem.artwork().size());
        assertTrue(importedItem.artwork().stream().allMatch(artwork -> artwork.source() == LibraryArtworkSource.FOLDER));

        services.catalogService().replaceManualMetadata(
            library.libraryId(),
            importedItem.libraryItemId(),
            new ReplaceLibraryItemMetadataRequest(
                "Manual Title",
                "Manual Sort",
                "Manual overview",
                List.of("Favorite", "Drama")
            )
        );
        services.catalogService().replaceManualArtwork(
            library.libraryId(),
            importedItem.libraryItemId(),
            new ReplaceLibraryItemArtworkRequest(manualPoster.toString(), null, null)
        );

        var manualItem = services.catalogService().getLibraryItem(library.libraryId(), importedItem.libraryItemId());
        assertNotNull(manualItem);
        assertEquals("Manual Title", manualItem.displayTitle());
        assertEquals("Manual Sort", manualItem.sortTitle());
        assertEquals("Manual overview", manualItem.overview());
        assertEquals(List.of("Favorite", "Drama"), manualItem.tags());
        var poster = manualItem.artwork().stream()
            .filter(artwork -> artwork.kind() == LibraryArtworkKind.POSTER)
            .findFirst()
            .orElseThrow();
        assertEquals(LibraryArtworkSource.MANUAL, poster.source());
        assertEquals(manualPoster.toString(), poster.path());

        services.catalogService().clearManualMetadata(library.libraryId(), importedItem.libraryItemId());
        services.catalogService().clearManualArtwork(library.libraryId(), importedItem.libraryItemId());

        var revertedItem = services.catalogService().getLibraryItem(library.libraryId(), importedItem.libraryItemId());
        assertNotNull(revertedItem);
        assertEquals("Imported Title", revertedItem.displayTitle());
        assertEquals(List.of("Drama", "Local"), revertedItem.tags());
        assertEquals(
            LibraryArtworkSource.FOLDER,
            revertedItem.artwork().stream()
                .filter(artwork -> artwork.kind() == LibraryArtworkKind.POSTER)
                .findFirst()
                .orElseThrow()
                .source()
        );
    }

    @Test
    void collectionsGroupLibraryItemsAndSurfaceMembershipOnItems() throws Exception {
        TestServices services = createServices();
        Path root = Files.createDirectories(tempDir.resolve("movies"));
        writeFile(root.resolve("Alpha.mp4"));
        writeFile(root.resolve("Beta.mp4"));
        var library = services.libraryService().createLibrary(
            new CreateLibraryRequest(
                "Movies",
                LibraryType.MOVIE,
                List.of(new LibrarySourceRootWriteRequest(root.toString()))
            )
        );

        services.scanService().runScanNow(library.libraryId(), LibraryScanMode.IMPORT);
        var items = services.catalogService().listLibraryItems(library.libraryId()).items();
        var alpha = items.stream().filter(item -> item.title().equals("Alpha")).findFirst().orElseThrow();
        var beta = items.stream().filter(item -> item.title().equals("Beta")).findFirst().orElseThrow();

        var collection = services.catalogService().createCollection(
            library.libraryId(),
            new CreateLibraryCollectionRequest(
                "Weekend Movies",
                null,
                List.of(alpha.libraryItemId(), beta.libraryItemId())
            )
        );

        assertEquals(2, collection.itemCount());
        assertEquals(List.of(alpha.libraryItemId(), beta.libraryItemId()), collection.itemIds());

        var listing = services.catalogService().listCollections(library.libraryId());
        assertEquals(1, listing.total());
        assertEquals(collection.collectionId(), listing.collections().getFirst().collectionId());

        var alphaAfter = services.catalogService().getLibraryItem(library.libraryId(), alpha.libraryItemId());
        assertNotNull(alphaAfter);
        assertEquals(collection.collectionId(), alphaAfter.collections().getFirst().collectionId());
        assertEquals("Weekend Movies", alphaAfter.collections().getFirst().title());
    }

    private record TestServices(
        LibraryService libraryService,
        LibraryCatalogService catalogService,
        LibraryScanService scanService
    ) {
    }
}
