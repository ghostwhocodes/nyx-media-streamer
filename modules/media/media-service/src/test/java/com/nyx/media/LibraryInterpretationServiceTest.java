package com.nyx.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.common.DatabaseResources;
import com.nyx.media.contracts.CreateLibraryRequest;
import com.nyx.media.contracts.LibraryItemType;
import com.nyx.media.contracts.LibrarySourceRootWriteRequest;
import com.nyx.media.contracts.LibraryType;
import com.nyx.media.contracts.MediaKind;
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

class LibraryInterpretationServiceTest {
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
        return createServices(null);
    }

    private TestServices createServices(List<LibraryItemBuilder> itemBuilders) {
        DatabaseResources libraryResources = LibraryService.createDatabase(tempDir.resolve("libraries-db"));
        DatabaseResources mediaResources = MediaObjectService.createDatabase(tempDir.resolve("media-db"));
        dataSources.add(libraryResources.getDataSource());
        dataSources.add(mediaResources.getDataSource());

        MediaObjectService mediaObjectService = new MediaObjectService(mediaResources.getJdbi());
        LibraryService libraryService = new LibraryService(libraryResources.getJdbi());
        LibraryInterpretationService interpretationService = itemBuilders == null
            ? new LibraryInterpretationService(
                libraryResources.getJdbi(),
                libraryService,
                mediaObjectService
            )
            : new LibraryInterpretationService(
                libraryResources.getJdbi(),
                libraryService,
                mediaObjectService,
                java.time.Clock.systemUTC(),
                itemBuilders
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
            null
        );

        return new TestServices(libraryService, interpretationService, scanService);
    }

    private Path writeMediaFile(Path path, int size) throws Exception {
        Files.createDirectories(path.getParent());
        return Files.write(path, new byte[size]);
    }

    private Path writeMediaFile(Path path) throws Exception {
        return writeMediaFile(path, 2_048);
    }

    @Test
    void movieItemsKeepStableLibraryIdsAcrossRescans() throws Exception {
        TestServices services = createServices();
        Path root = Files.createDirectories(tempDir.resolve("movies"));
        writeMediaFile(root.resolve("Movie.Title.2024.mp4"));
        var library = services.libraryService().createLibrary(
            new CreateLibraryRequest(
                "Movies",
                LibraryType.MOVIE,
                List.of(new LibrarySourceRootWriteRequest(root.toString()))
            )
        );

        services.scanService().runScanNow(library.libraryId(), LibraryScanMode.IMPORT);
        var firstListing = services.interpretationService().listLibraryItems(library.libraryId());
        services.scanService().runScanNow(library.libraryId(), LibraryScanMode.RESCAN);
        var secondListing = services.interpretationService().listLibraryItems(library.libraryId());

        assertEquals(1, firstListing.total());
        assertEquals(1, secondListing.total());
        assertEquals(LibraryItemType.MOVIE, firstListing.items().getFirst().type());
        assertEquals(firstListing.items().getFirst().libraryItemId(), secondListing.items().getFirst().libraryItemId());
    }

    @Test
    void customItemBuilderCanReplaceOpinionatedLibraryInterpretation() throws Exception {
        LibraryItemBuilder builder = new LibraryItemBuilder() {
            @Override
            public String getBuilderId() {
                return "test-custom-builder";
            }

            @Override
            public boolean supports(com.nyx.media.contracts.Library library) {
                return library.type() == LibraryType.MOVIE;
            }

            @Override
            public List<LibraryItemDescriptor> buildItems(LibraryItemBuildContext context) {
                return List.of(new LibraryItemDescriptor(
                    context.identityKey(),
                    null,
                    context.libraryEntryId(),
                    context.objectId(),
                    LibraryItemType.VIDEO,
                    "Custom Builder Item",
                    MediaKind.VIDEO,
                    context.primaryPath(),
                    null,
                    null,
                    null,
                    null
                ));
            }
        };
        TestServices services = createServices(List.of(builder));
        Path root = Files.createDirectories(tempDir.resolve("custom-builder-movies"));
        writeMediaFile(root.resolve("Movie.Title.2024.mp4"));
        var library = services.libraryService().createLibrary(
            new CreateLibraryRequest(
                "Movies",
                LibraryType.MOVIE,
                List.of(new LibrarySourceRootWriteRequest(root.toString()))
            )
        );

        services.scanService().runScanNow(library.libraryId(), LibraryScanMode.IMPORT);

        var listing = services.interpretationService().listLibraryItems(library.libraryId());
        assertEquals(1, listing.total());
        assertEquals(LibraryItemType.VIDEO, listing.items().getFirst().type());
        assertEquals("Custom Builder Item", listing.items().getFirst().title());
    }

    @Test
    void showLibrariesBuildShowSeasonEpisodeHierarchyAndKeepAmbiguousFilesUnmatched() throws Exception {
        TestServices services = createServices();
        Path root = Files.createDirectories(tempDir.resolve("shows"));
        writeMediaFile(root.resolve("Space Show/Season 01/Space.Show.S01E01.mkv"));
        writeMediaFile(root.resolve("bonus-featurette.mkv"));
        var library = services.libraryService().createLibrary(
            new CreateLibraryRequest(
                "Shows",
                LibraryType.SHOW,
                List.of(new LibrarySourceRootWriteRequest(root.toString()))
            )
        );

        services.scanService().runScanNow(library.libraryId(), LibraryScanMode.IMPORT);

        var rootItems = services.interpretationService().listLibraryItems(library.libraryId());
        var showItem = rootItems.items().stream()
            .filter(item -> item.type() == LibraryItemType.SHOW)
            .findFirst()
            .orElseThrow();
        var unmatchedItem = rootItems.items().stream()
            .filter(item -> item.type() == LibraryItemType.UNMATCHED)
            .findFirst()
            .orElseThrow();
        var seasonItems = services.interpretationService().listLibraryItems(library.libraryId(), showItem.libraryItemId());
        var episodeItems = services.interpretationService().listLibraryItems(
            library.libraryId(),
            seasonItems.items().getFirst().libraryItemId()
        );

        assertEquals(2, rootItems.total());
        assertEquals("Space Show", showItem.title());
        assertTrue(
            unmatchedItem.unmatchedReason() != null
                && unmatchedItem.unmatchedReason().contains("season or episode pattern missing")
        );
        assertEquals(LibraryItemType.SEASON, seasonItems.items().getFirst().type());
        assertEquals(LibraryItemType.EPISODE, episodeItems.items().getFirst().type());
        assertEquals(1, episodeItems.items().getFirst().seasonNumber());
        assertEquals(1, episodeItems.items().getFirst().episodeNumber());
    }

    @Test
    void showLibrariesRootedAtASingleShowPreserveTheShowDirectoryAsPrimaryPath() throws Exception {
        TestServices services = createServices();
        Path showRoot = Files.createDirectories(tempDir.resolve("shows/Space Show"));
        Path seasonRoot = Files.createDirectories(showRoot.resolve("Season 01"));
        writeMediaFile(seasonRoot.resolve("Space.Show.S01E01.mkv"));
        var library = services.libraryService().createLibrary(
            new CreateLibraryRequest(
                "Shows",
                LibraryType.SHOW,
                List.of(new LibrarySourceRootWriteRequest(showRoot.toString()))
            )
        );

        services.scanService().runScanNow(library.libraryId(), LibraryScanMode.IMPORT);

        var rootItems = services.interpretationService().listLibraryItems(library.libraryId());
        var showItem = rootItems.items().getFirst();
        var seasonItems = services.interpretationService().listLibraryItems(library.libraryId(), showItem.libraryItemId());
        var seasonItem = seasonItems.items().getFirst();

        assertEquals(LibraryItemType.SHOW, showItem.type());
        assertEquals(showRoot.toString(), showItem.primaryPath());
        assertEquals(LibraryItemType.SEASON, seasonItem.type());
        assertEquals(seasonRoot.toString(), seasonItem.primaryPath());
    }

    @Test
    void musicLibrariesGroupTracksUnderAlbumsWhenLocalAlbumInferenceIsAvailable() throws Exception {
        TestServices services = createServices();
        Path root = Files.createDirectories(tempDir.resolve("music"));
        writeMediaFile(root.resolve("Great Album/01 - Intro.flac"));
        var library = services.libraryService().createLibrary(
            new CreateLibraryRequest(
                "Music",
                LibraryType.MUSIC,
                List.of(new LibrarySourceRootWriteRequest(root.toString()))
            )
        );

        services.scanService().runScanNow(library.libraryId(), LibraryScanMode.IMPORT);

        var rootItems = services.interpretationService().listLibraryItems(library.libraryId());
        var album = rootItems.items().getFirst();
        var trackItems = services.interpretationService().listLibraryItems(library.libraryId(), album.libraryItemId());

        assertEquals(LibraryItemType.ALBUM, album.type());
        assertEquals("Great Album", album.title());
        assertEquals(1, trackItems.total());
        assertEquals(LibraryItemType.TRACK, trackItems.items().getFirst().type());
        assertEquals(1, trackItems.items().getFirst().trackNumber());
    }

    @Test
    void musicLibrariesRootedAtASingleAlbumInferTheAlbumFromTheSourceRootFolder() throws Exception {
        TestServices services = createServices();
        Path albumRoot = Files.createDirectories(tempDir.resolve("music/Great Album"));
        writeMediaFile(albumRoot.resolve("01 - Intro.flac"));
        var library = services.libraryService().createLibrary(
            new CreateLibraryRequest(
                "Music",
                LibraryType.MUSIC,
                List.of(new LibrarySourceRootWriteRequest(albumRoot.toString()))
            )
        );

        services.scanService().runScanNow(library.libraryId(), LibraryScanMode.IMPORT);

        var rootItems = services.interpretationService().listLibraryItems(library.libraryId());
        var album = rootItems.items().getFirst();
        var trackItems = services.interpretationService().listLibraryItems(library.libraryId(), album.libraryItemId());

        assertEquals(LibraryItemType.ALBUM, album.type());
        assertEquals("Great Album", album.title());
        assertEquals(albumRoot.toString(), album.primaryPath());
        assertEquals(1, trackItems.total());
        assertEquals(LibraryItemType.TRACK, trackItems.items().getFirst().type());
    }

    @Test
    void photoAndGenericVideoLibrariesEmitTypedLeafItems() throws Exception {
        TestServices services = createServices();
        Path photoRoot = Files.createDirectories(tempDir.resolve("photos"));
        Path videoRoot = Files.createDirectories(tempDir.resolve("videos"));
        writeMediaFile(photoRoot.resolve("beach.jpg"));
        writeMediaFile(videoRoot.resolve("clip.mp4"));

        var photoLibrary = services.libraryService().createLibrary(
            new CreateLibraryRequest(
                "Photos",
                LibraryType.PHOTO,
                List.of(new LibrarySourceRootWriteRequest(photoRoot.toString()))
            )
        );
        var videoLibrary = services.libraryService().createLibrary(
            new CreateLibraryRequest(
                "Videos",
                LibraryType.GENERIC_VIDEO,
                List.of(new LibrarySourceRootWriteRequest(videoRoot.toString()))
            )
        );

        services.scanService().runScanNow(photoLibrary.libraryId(), LibraryScanMode.IMPORT);
        services.scanService().runScanNow(videoLibrary.libraryId(), LibraryScanMode.IMPORT);

        var photoItems = services.interpretationService().listLibraryItems(photoLibrary.libraryId());
        var videoItems = services.interpretationService().listLibraryItems(videoLibrary.libraryId());

        assertEquals(LibraryItemType.PHOTO, photoItems.items().getFirst().type());
        assertEquals(LibraryItemType.VIDEO, videoItems.items().getFirst().type());
        assertNotNull(photoItems.items().getFirst().primaryPath());
        assertNotNull(videoItems.items().getFirst().primaryPath());
    }

    private record TestServices(
        LibraryService libraryService,
        LibraryInterpretationService interpretationService,
        LibraryScanService scanService
    ) {
    }
}
