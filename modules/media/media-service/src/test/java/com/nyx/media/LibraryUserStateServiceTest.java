package com.nyx.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.common.DatabaseResources;
import com.nyx.media.contracts.CreateLibraryRequest;
import com.nyx.media.contracts.LibraryItemType;
import com.nyx.media.contracts.LibrarySourceRootWriteRequest;
import com.nyx.media.contracts.LibraryType;
import com.nyx.media.contracts.MediaKind;
import com.nyx.media.contracts.UserMediaStateWriteRequest;
import com.nyx.playback.contracts.MediaSessionPlaybackEvent;
import com.nyx.playback.contracts.MediaSessionPlaybackReport;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryUserStateServiceTest {
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
        UserMediaStateService userMediaStateService = new UserMediaStateService(mediaResources.getJdbi());
        LibraryUserStateService libraryUserStateService = new LibraryUserStateService(
            catalogService,
            userMediaStateService
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

        return new TestServices(
            libraryService,
            catalogService,
            userMediaStateService,
            libraryUserStateService,
            scanService
        );
    }

    private Path writeMediaFile(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        return Files.write(path, new byte[2_048]);
    }

    @Test
    void libraryUserStateAggregatesParentSummariesAndFiltersFavoritesByLibrary() throws Exception {
        TestServices services = createServices();
        Path showRoot = Files.createDirectories(tempDir.resolve("shows"));
        Path movieRoot = Files.createDirectories(tempDir.resolve("movies"));
        writeMediaFile(showRoot.resolve("Space Show/Season 01/Space.Show.S01E01.mkv"));
        writeMediaFile(showRoot.resolve("Space Show/Season 01/Space.Show.S01E02.mkv"));
        writeMediaFile(movieRoot.resolve("Movie.mp4"));

        var showLibrary = services.libraryService().createLibrary(
            new CreateLibraryRequest(
                "Shows",
                LibraryType.SHOW,
                List.of(new LibrarySourceRootWriteRequest(showRoot.toString()))
            )
        );
        var movieLibrary = services.libraryService().createLibrary(
            new CreateLibraryRequest(
                "Movies",
                LibraryType.MOVIE,
                List.of(new LibrarySourceRootWriteRequest(movieRoot.toString()))
            )
        );

        services.scanService().runScanNow(showLibrary.libraryId(), LibraryScanMode.IMPORT);
        services.scanService().runScanNow(movieLibrary.libraryId(), LibraryScanMode.IMPORT);

        var showRootItems = services.catalogService().listLibraryItems(showLibrary.libraryId()).items();
        var showItem = showRootItems.stream()
            .filter(item -> item.type() == LibraryItemType.SHOW)
            .findFirst()
            .orElseThrow();
        var seasonItem = services.catalogService()
            .listLibraryItems(showLibrary.libraryId(), showItem.libraryItemId())
            .items()
            .getFirst();
        var episodes = services.catalogService()
            .listLibraryItems(showLibrary.libraryId(), seasonItem.libraryItemId())
            .items()
            .stream()
            .sorted(Comparator.comparing(item -> item.episodeNumber()))
            .toList();
        var firstEpisode = episodes.getFirst();
        var secondEpisode = episodes.getLast();
        var movieItem = services.catalogService().listLibraryItems(movieLibrary.libraryId()).items().getFirst();

        services.userMediaStateService().putState(
            "alice",
            firstEpisode.sourceObjectId(),
            new UserMediaStateWriteRequest(12_000L, false, true, 8)
        );
        services.userMediaStateService().putState(
            "alice",
            secondEpisode.sourceObjectId(),
            new UserMediaStateWriteRequest(null, true, false, null)
        );
        services.userMediaStateService().putState(
            "alice",
            movieItem.sourceObjectId(),
            new UserMediaStateWriteRequest(5_000L, false, true, null)
        );

        var showState = services.libraryUserStateService().getItemState(
            showLibrary.libraryId(),
            "alice",
            showItem.libraryItemId()
        );
        assertNotNull(showState);
        assertEquals(2, showState.state().leafCount());
        assertEquals(1, showState.state().watchedLeafCount());
        assertEquals(1, showState.state().favoriteLeafCount());
        assertEquals(1, showState.state().inProgressLeafCount());
        assertFalse(showState.state().watched());
        assertTrue(showState.state().favorite());
        assertEquals(12_000L, showState.state().resumePositionMillis());
        assertEquals(firstEpisode.libraryItemId(), showState.state().resumeItemId());

        var seasonEntries = services.libraryUserStateService().listItemStates(
            showLibrary.libraryId(),
            "alice",
            seasonItem.libraryItemId(),
            1,
            10
        );
        assertEquals(2, seasonEntries.total());
        assertTrue(seasonEntries.items().stream().allMatch(entry -> entry.state().leafCount() == 1));

        var showFavorites = services.libraryUserStateService().listFavorites(
            showLibrary.libraryId(),
            "alice",
            1,
            10
        );
        assertEquals(1, showFavorites.total());
        assertEquals(firstEpisode.libraryItemId(), showFavorites.items().getFirst().item().libraryItemId());

        var movieFavorites = services.libraryUserStateService().listFavorites(
            movieLibrary.libraryId(),
            "alice",
            1,
            10
        );
        assertEquals(1, movieFavorites.total());
        assertEquals(movieItem.libraryItemId(), movieFavorites.items().getFirst().item().libraryItemId());

        var continueWatching = services.libraryUserStateService().listContinueWatching(
            showLibrary.libraryId(),
            "alice",
            1,
            10
        );
        assertEquals(1, continueWatching.total());
        assertEquals(firstEpisode.libraryItemId(), continueWatching.items().getFirst().item().libraryItemId());
        assertEquals(12_000L, continueWatching.items().getFirst().state().resumePositionMillis());
    }

    @Test
    void libraryUserStateReflectsPlaybackReportProjectionThroughSharedObjectState() throws Exception {
        TestServices services = createServices();
        Path showRoot = Files.createDirectories(tempDir.resolve("shows"));
        writeMediaFile(showRoot.resolve("Signal Show/Season 01/Signal.Show.S01E01.mkv"));

        var library = services.libraryService().createLibrary(
            new CreateLibraryRequest(
                "Shows",
                LibraryType.SHOW,
                List.of(new LibrarySourceRootWriteRequest(showRoot.toString()))
            )
        );

        services.scanService().runScanNow(library.libraryId(), LibraryScanMode.IMPORT);

        var showItem = services.catalogService().listLibraryItems(library.libraryId()).items().stream()
            .filter(item -> item.type() == LibraryItemType.SHOW)
            .findFirst()
            .orElseThrow();
        var seasonItem = services.catalogService()
            .listLibraryItems(library.libraryId(), showItem.libraryItemId())
            .items()
            .getFirst();
        var episodeItem = services.catalogService()
            .listLibraryItems(library.libraryId(), seasonItem.libraryItemId())
            .items()
            .getFirst();
        String objectId = episodeItem.sourceObjectId();

        services.userMediaStateService().projectPlaybackState(
            "alice",
            objectId,
            new MediaSessionPlaybackReport(
                MediaSessionPlaybackEvent.HEARTBEAT,
                objectId,
                MediaKind.VIDEO,
                42_000L,
                100_000L,
                "2026-04-15T10:00:00Z",
                null,
                null,
                null
            )
        );

        var inProgressState = services.libraryUserStateService().getItemState(
            library.libraryId(),
            "alice",
            showItem.libraryItemId()
        );
        assertNotNull(inProgressState);
        assertFalse(inProgressState.state().watched());
        assertEquals(1, inProgressState.state().inProgressLeafCount());
        assertEquals(42_000L, inProgressState.state().resumePositionMillis());
        assertEquals(episodeItem.libraryItemId(), inProgressState.state().resumeItemId());

        var resume = services.libraryUserStateService().listResume(
            library.libraryId(),
            "alice",
            1,
            10
        );
        assertEquals(1, resume.total());
        assertEquals(episodeItem.libraryItemId(), resume.items().getFirst().item().libraryItemId());

        services.userMediaStateService().projectPlaybackState(
            "alice",
            objectId,
            new MediaSessionPlaybackReport(
                MediaSessionPlaybackEvent.COMPLETED,
                objectId,
                MediaKind.VIDEO,
                100_000L,
                100_000L,
                "2026-04-15T10:30:00Z",
                null,
                null,
                null
            )
        );

        var completedState = services.libraryUserStateService().getItemState(
            library.libraryId(),
            "alice",
            showItem.libraryItemId()
        );
        assertNotNull(completedState);
        assertTrue(completedState.state().watched());
        assertEquals(1, completedState.state().watchedLeafCount());
        assertEquals(1, completedState.state().playCount());
        assertEquals(0, completedState.state().inProgressLeafCount());
        assertNull(completedState.state().resumePositionMillis());

        var continueWatching = services.libraryUserStateService().listContinueWatching(
            library.libraryId(),
            "alice",
            1,
            10
        );
        assertEquals(0, continueWatching.total());

        var watched = services.libraryUserStateService().listWatched(
            library.libraryId(),
            "alice",
            1,
            10
        );
        assertEquals(1, watched.total());
        assertEquals(episodeItem.libraryItemId(), watched.items().getFirst().item().libraryItemId());

        var resumeAfterComplete = services.libraryUserStateService().listResume(
            library.libraryId(),
            "alice",
            1,
            10
        );
        assertEquals(0, resumeAfterComplete.total());
    }

    private record TestServices(
        LibraryService libraryService,
        LibraryCatalogService catalogService,
        UserMediaStateService userMediaStateService,
        LibraryUserStateService libraryUserStateService,
        LibraryScanService scanService
    ) {
    }
}
