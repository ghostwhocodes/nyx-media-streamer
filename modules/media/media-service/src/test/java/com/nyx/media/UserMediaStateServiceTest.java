package com.nyx.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.common.DatabaseResources;
import com.nyx.config.DatabaseConfig;
import com.nyx.media.contracts.MediaKind;
import com.nyx.media.contracts.MediaObjectContracts;
import com.nyx.media.contracts.MediaObjectStatus;
import com.nyx.media.contracts.UserMediaState;
import com.nyx.media.contracts.UserMediaStateWriteRequest;
import com.nyx.playback.contracts.MediaSessionPlaybackEvent;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UserMediaStateServiceTest {
    @TempDir
    Path tempDir;

    private final List<HikariDataSource> dataSources = new ArrayList<>();

    @AfterEach
    void teardown() {
        ModuleMediaTestSupport.closeDataSources(dataSources);
    }

    private TestServices createServices(int poolSize) {
        DatabaseResources resources = MediaObjectService.createDatabase(tempDir, new DatabaseConfig(tempDir, poolSize));
        dataSources.add(resources.getDataSource());
        return new TestServices(new MediaObjectService(resources.getJdbi()), new UserMediaStateService(resources.getJdbi()));
    }

    private TestServices createServices() {
        return createServices(1);
    }

    private com.nyx.media.contracts.MediaObject createObject(MediaObjectService mediaObjectService, String name, MediaKind mediaKind) {
        return mediaObjectService.upsertPrimaryPath(
            new MediaObjectUpsertRequest(
                mediaKind,
                tempDir.resolve(name).toString(),
                switch (mediaKind) {
                    case VIDEO -> "video/mp4";
                    case AUDIO -> "audio/flac";
                    case IMAGE -> "image/jpeg";
                    case OTHER -> "application/octet-stream";
                },
                1_024,
                "2026-04-10T12:00:00Z",
                name,
                mediaKind == MediaKind.VIDEO || mediaKind == MediaKind.AUDIO ? 120_000L : null,
                mediaKind == MediaKind.VIDEO || mediaKind == MediaKind.IMAGE ? 1920 : null,
                mediaKind == MediaKind.VIDEO || mediaKind == MediaKind.IMAGE ? 1080 : null,
                mediaKind == MediaKind.AUDIO ? 2 : null,
                null,
                null,
                null,
                null,
                MediaObjectContracts.MEDIA_OBJECT_HASH_ALGORITHM_NONE,
                null,
                MediaObjectStatus.ACTIVE
            )
        );
    }

    @Test
    void getStateReturnsDefaultObjectKeyedStateBeforeMutation() {
        TestServices services = createServices();
        var mediaObject = createObject(services.mediaObjectService(), "movie.mp4", MediaKind.VIDEO);

        var state = services.stateService().getState("alice", mediaObject.objectId());

        assertEquals("alice", state.userId());
        assertEquals(mediaObject.objectId(), state.objectId());
        assertNull(state.resumePositionMillis());
        assertFalse(state.watched());
        assertFalse(state.favorite());
        assertNull(state.rating());
        assertEquals(0, state.playCount());
        assertNull(state.lastInteractionAt());
    }

    @Test
    void putStatePersistsSharedFavoritesAndContinueWatchingViewsAcrossMediaKinds() {
        TestServices services = createServices();
        var audio = createObject(services.mediaObjectService(), "song.flac", MediaKind.AUDIO);
        var video = createObject(services.mediaObjectService(), "movie.mp4", MediaKind.VIDEO);
        var image = createObject(services.mediaObjectService(), "photo.jpg", MediaKind.IMAGE);

        var audioState = services.stateService().putState(
            "alice",
            audio.objectId(),
            new UserMediaStateWriteRequest(15_000L, false, true, 7)
        );
        var videoState = services.stateService().putState(
            "alice",
            video.objectId(),
            new UserMediaStateWriteRequest(90_000L, false, false, null)
        );
        var imageState = services.stateService().putState(
            "alice",
            image.objectId(),
            new UserMediaStateWriteRequest(null, true, true, 9)
        );

        var favorites = services.stateService().listFavorites("alice", 1, 10);
        var continueWatching = services.stateService().listContinueWatching("alice", 1, 10);

        assertEquals(15_000L, audioState.resumePositionMillis());
        assertTrue(audioState.favorite());
        assertEquals(7, audioState.rating());
        assertEquals(90_000L, videoState.resumePositionMillis());
        assertTrue(imageState.watched());
        assertEquals(1, imageState.playCount());
        assertNotNull(imageState.watchedAt());
        assertNull(imageState.resumePositionMillis());

        assertEquals(
            Set.of(audio.objectId(), image.objectId()),
            favorites.items().stream().map(entry -> entry.media().objectId()).collect(Collectors.toSet())
        );
        assertEquals(
            Set.of(MediaKind.AUDIO, MediaKind.IMAGE),
            favorites.items().stream().map(entry -> entry.media().mediaKind()).collect(Collectors.toSet())
        );
        assertEquals(
            Set.of(audio.objectId(), video.objectId()),
            continueWatching.items().stream().map(entry -> entry.media().objectId()).collect(Collectors.toSet())
        );
        assertEquals(
            Set.of(MediaKind.AUDIO, MediaKind.VIDEO),
            continueWatching.items().stream().map(entry -> entry.media().mediaKind()).collect(Collectors.toSet())
        );
        assertTrue(favorites.items().stream().allMatch(entry -> entry.media().path() != null));
    }

    @Test
    void stateRemainsAttachedToTheOldObjectWhenAPathIsReplaced() {
        TestServices services = createServices();
        var original = createObject(services.mediaObjectService(), "movie.mp4", MediaKind.VIDEO);
        services.stateService().putState(
            "alice",
            original.objectId(),
            new UserMediaStateWriteRequest(42_000L, false, true, 8)
        );

        var replacement = services.mediaObjectService().upsertPrimaryPath(
            new MediaObjectUpsertRequest(
                MediaKind.VIDEO,
                tempDir.resolve("movie.mp4").toString(),
                "video/mp4",
                9_999,
                "2026-04-10T12:30:00Z",
                "movie.mp4",
                150_000L,
                1920,
                1080,
                null,
                null,
                null,
                null,
                null,
                MediaObjectContracts.MEDIA_OBJECT_HASH_ALGORITHM_NONE,
                null,
                MediaObjectStatus.ACTIVE
            )
        );

        var originalState = services.stateService().getState("alice", original.objectId());
        var replacementState = services.stateService().getState("alice", replacement.objectId());
        var favorites = services.stateService().listFavorites("alice", 1, 10);
        var favoriteEntry = favorites.items().getFirst();

        assertNotEquals(original.objectId(), replacement.objectId());
        assertEquals(42_000L, originalState.resumePositionMillis());
        assertTrue(originalState.favorite());
        assertFalse(replacementState.favorite());
        assertNull(replacementState.resumePositionMillis());
        assertEquals(original.objectId(), favoriteEntry.media().objectId());
        assertEquals(MediaKind.VIDEO, favoriteEntry.media().mediaKind());
        assertNull(favoriteEntry.media().path());
        assertEquals(MediaObjectStatus.MISSING, favoriteEntry.media().status());
    }

    @Test
    void putStateIsSafeForConcurrentFirstWrites() throws Exception {
        TestServices services = createServices(4);
        var mediaObject = createObject(services.mediaObjectService(), "movie-race.mp4", MediaKind.VIDEO);
        UserMediaStateWriteRequest request = new UserMediaStateWriteRequest(null, true, true, 8);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var futures = executor.invokeAll(List.<Callable<UserMediaState>>of(
                () -> services.stateService().putState("alice", mediaObject.objectId(), request),
                () -> services.stateService().putState("alice", mediaObject.objectId(), request)
            ));
            List<UserMediaState> results = new ArrayList<>(futures.size());
            for (var future : futures) {
                results.add(future.get());
            }

            var stored = services.stateService().getState("alice", mediaObject.objectId());

            assertEquals(2, results.size());
            assertTrue(results.stream().allMatch(result -> result.objectId().equals(mediaObject.objectId())));
            assertTrue(stored.watched());
            assertTrue(stored.favorite());
            assertEquals(8, stored.rating());
            assertEquals(1, stored.playCount());
            assertNotNull(stored.watchedAt());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void projectPlaybackStatePreservesManualFavoriteAndRatingWhileUpdatingResumeAndWatchedState() {
        TestServices services = createServices();
        var mediaObject = createObject(services.mediaObjectService(), "movie-playstate.mp4", MediaKind.VIDEO);

        services.stateService().putState(
            "alice",
            mediaObject.objectId(),
            new UserMediaStateWriteRequest(null, false, true, 8)
        );

        var heartbeat = services.stateService().projectPlaybackState(
            "alice",
            mediaObject.objectId(),
            ModuleMediaTestSupport.mediaSessionPlaybackReport(
                MediaSessionPlaybackEvent.HEARTBEAT,
                60_000L,
                120_000L,
                "2026-04-12T10:00:00Z"
            )
        );
        var completed = services.stateService().projectPlaybackState(
            "alice",
            mediaObject.objectId(),
            ModuleMediaTestSupport.mediaSessionPlaybackReport(
                MediaSessionPlaybackEvent.COMPLETED,
                null,
                null,
                "2026-04-12T10:05:00Z"
            )
        );

        assertEquals(60_000L, heartbeat.resumePositionMillis());
        assertFalse(heartbeat.watched());
        assertTrue(heartbeat.favorite());
        assertEquals(8, heartbeat.rating());
        assertEquals("2026-04-12T10:00:00Z", heartbeat.lastPlayedAt());

        assertTrue(completed.watched());
        assertNull(completed.resumePositionMillis());
        assertEquals(1, completed.playCount());
        assertEquals("2026-04-12T10:05:00Z", completed.watchedAt());
        assertTrue(completed.favorite());
        assertEquals(8, completed.rating());
    }

    @Test
    void projectPlaybackStateAppliesStoppedThresholdRulesAndOnlyIncrementsPlayCountOnFirstWatchedTransition() {
        TestServices services = createServices();
        var mediaObject = createObject(services.mediaObjectService(), "movie-threshold.mp4", MediaKind.VIDEO);

        var stopped = services.stateService().projectPlaybackState(
            "alice",
            mediaObject.objectId(),
            ModuleMediaTestSupport.mediaSessionPlaybackReport(
                MediaSessionPlaybackEvent.STOPPED,
                94_000L,
                100_000L,
                "2026-04-12T11:00:00Z"
            )
        );
        var threshold = services.stateService().projectPlaybackState(
            "alice",
            mediaObject.objectId(),
            ModuleMediaTestSupport.mediaSessionPlaybackReport(
                MediaSessionPlaybackEvent.HEARTBEAT,
                95_000L,
                100_000L,
                "2026-04-12T11:05:00Z"
            )
        );
        var repeated = services.stateService().projectPlaybackState(
            "alice",
            mediaObject.objectId(),
            ModuleMediaTestSupport.mediaSessionPlaybackReport(
                MediaSessionPlaybackEvent.HEARTBEAT,
                99_000L,
                100_000L,
                "2026-04-12T11:10:00Z"
            )
        );

        assertEquals(94_000L, stopped.resumePositionMillis());
        assertFalse(stopped.watched());
        assertEquals(0, stopped.playCount());

        assertTrue(threshold.watched());
        assertNull(threshold.resumePositionMillis());
        assertEquals(1, threshold.playCount());
        assertEquals("2026-04-12T11:05:00Z", threshold.watchedAt());

        assertTrue(repeated.watched());
        assertNull(repeated.resumePositionMillis());
        assertEquals(1, repeated.playCount());
        assertEquals("2026-04-12T11:05:00Z", repeated.watchedAt());
    }

    private record TestServices(MediaObjectService mediaObjectService, UserMediaStateService stateService) {
    }
}
