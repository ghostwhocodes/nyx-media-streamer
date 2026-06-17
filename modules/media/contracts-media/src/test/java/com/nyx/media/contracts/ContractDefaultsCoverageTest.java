package com.nyx.media.contracts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractDefaultsCoverageTest {
    @Test
    void mediaItemListingsExposeDefaultsAcrossBrowseShapes() {
        MediaItem.Video video = new MediaItem.Video("movie.mp4", "Movies/movie.mp4", 4_096, "video/mp4");
        MediaItem.Image image = new MediaItem.Image("poster.jpg", "Movies/poster.jpg", 1_024, "image/jpeg");
        MediaItem.Music music = new MediaItem.Music("song.mp3", "Music/song.mp3", 2_048, "audio/mpeg");
        FileSearchResult searchResult = new FileSearchResult(List.of(video, image, music), 3, 2, 25, "mov");
        Gallery gallery = new Gallery(List.of(image), 1, 1, 10);
        AudioListing audioListing = new AudioListing(List.of(music), 1, 1, 20);
        ImageDimensions dimensions = new ImageDimensions(320, 180);

        assertEquals(MediaKind.VIDEO, video.mediaKind());
        assertNull(video.objectId());
        assertNull(video.primaryThumbnail());
        assertNull(video.viewing());
        assertNull(video.modifiedAt());
        assertEquals(MediaKind.IMAGE, image.mediaKind());
        assertTrue(image.thumbnailSizes().isEmpty());
        assertNull(image.viewing());
        assertEquals(MediaKind.AUDIO, music.mediaKind());
        assertNull(music.duration());
        assertNull(music.artist());
        assertEquals("mov", searchResult.query());
        assertEquals(3, searchResult.items().size());
        assertEquals("poster.jpg", gallery.images().get(0).name());
        assertEquals(1, gallery.total());
        assertEquals("song.mp3", audioListing.tracks().get(0).name());
        assertEquals(20, audioListing.limit());
        assertEquals(320, dimensions.width());
        assertEquals(180, dimensions.height());
        assertEquals(SortOrder.DURATION, SortOrder.valueOf("DURATION"));
    }

    @Test
    void libraryWriteContractsKeepDefaultsAndDerivedCountsStable() {
        ReplaceLibraryItemMetadataRequest defaultMetadata = new ReplaceLibraryItemMetadataRequest();
        ReplaceLibraryItemArtworkRequest defaultArtwork = new ReplaceLibraryItemArtworkRequest();
        LibraryItemUserState defaultState = new LibraryItemUserState();
        LibraryCollection collection = new LibraryCollection(
            "collection-1",
            "library-1",
            "Weekend Movies",
            List.of("item-1", "item-2"),
            "2026-04-15T12:00:00Z",
            "2026-04-15T12:00:00Z"
        );
        LibraryCollectionListing listing = new LibraryCollectionListing(List.of(collection), 1);
        CreateLibraryCollectionRequest createRequest = new CreateLibraryCollectionRequest("Favorites");
        UpdateLibraryCollectionRequest updateRequest = new UpdateLibraryCollectionRequest("Favorites Updated");

        assertNull(defaultMetadata.displayTitle());
        assertTrue(defaultMetadata.tags().isEmpty());
        assertNull(defaultArtwork.posterPath());
        assertNull(defaultArtwork.backgroundPath());
        assertNull(defaultState.resumePositionMillis());
        assertEquals(0, defaultState.playCount());
        assertNull(defaultState.resumeItemId());
        assertEquals(2, collection.itemCount());
        assertEquals("collection-1", listing.collections().get(0).collectionId());
        assertTrue(createRequest.itemIds().isEmpty());
        assertNull(createRequest.sortTitle());
        assertEquals("Favorites Updated", updateRequest.title());
        assertTrue(updateRequest.itemIds().isEmpty());
    }

    @Test
    void imageViewingContractsSurfaceDefaultCapabilityMetadata() {
        ImageTransformResult result = new ImageTransformResult();
        ImageTransformCapabilities capabilities = new ImageTransformCapabilities();
        ImageViewingMetadata viewingMetadata = new ImageViewingMetadata();

        assertNull(result.width());
        assertNull(result.mimeType());
        assertEquals(ImageTransformFit.CONTAIN, result.fit());
        assertTrue(result.cacheable());
        assertTrue(result.privacyStripped());
        assertTrue(capabilities.supportsWidth());
        assertTrue(capabilities.supportsHeight());
        assertTrue(capabilities.supportedFits().contains(ImageTransformFit.COVER));
        assertTrue(capabilities.supportedFits().contains(ImageTransformFit.FILL));
        assertEquals(ImageTransformFit.CONTAIN, viewingMetadata.defaultTransform().fit());
        assertTrue(viewingMetadata.capabilities().privacyStrippedByDefault());
    }

    @Test
    void trickplayDiscoveryContractsKeepAdditiveDefaults() {
        TrickplayManifest manifest = new TrickplayManifest(90_000L, 10_000L);
        TrickplayTimelineEntry timelineEntry = new TrickplayTimelineEntry(10_000L, 1);
        TrickplayDiscoveryMetadata discovery = new TrickplayDiscoveryMetadata();
        VideoViewingMetadata viewingMetadata = new VideoViewingMetadata();

        assertTrue(manifest.assets().isEmpty());
        assertTrue(manifest.timeline().isEmpty());
        assertTrue(manifest.cacheable());
        assertEquals(TrickplayAssetKind.STORYBOARD_SHEET, timelineEntry.kind());
        assertEquals(0, timelineEntry.column());
        assertEquals(0, timelineEntry.row());
        assertTrue(discovery.defaultRequest().assetKinds().contains(TrickplayAssetKind.PREVIEW_STRIP));
        assertTrue(discovery.cacheableByDefault());
        assertNull(viewingMetadata.trickplay());
    }

    @Test
    void javaCollectionContractsDefensivelyCopyMutableInputs() {
        ArrayList<LibrarySourceRootWriteRequest> sourceRoots = new ArrayList<>(List.of(
            new LibrarySourceRootWriteRequest("/srv/media/movies", "Movies")
        ));
        CreateLibraryRequest createRequest = new CreateLibraryRequest("Movies", null, LibraryType.MOVIE, sourceRoots);
        UpdateLibraryRequest updateRequest = new UpdateLibraryRequest("Movies", "Updated", LibraryType.MOVIE, sourceRoots);
        CreateLibraryCollectionRequest createCollection = new CreateLibraryCollectionRequest(
            "Favorites",
            "Favorites",
            new ArrayList<>(List.of("item-1"))
        );
        UpdateLibraryCollectionRequest updateCollection = new UpdateLibraryCollectionRequest(
            "Favorites Updated",
            "Favorites Updated",
            new ArrayList<>(List.of("item-2"))
        );
        ImageTransformCapabilities capabilities = new ImageTransformCapabilities(
            null,
            null,
            null,
            null,
            null,
            new LinkedHashSet<>(Set.of(ImageTransformFit.COVER)),
            null
        );
        TrickplayRequest trickplayRequest = new TrickplayRequest(
            new LinkedHashSet<>(Set.of(TrickplayAssetKind.PREVIEW_STRIP)),
            5_000L,
            320,
            180,
            null
        );

        sourceRoots.clear();

        assertEquals("", createRequest.description());
        assertEquals(1, createRequest.sourceRoots().size());
        assertEquals(1, updateRequest.sourceRoots().size());
        assertEquals(Set.of(ImageTransformFit.COVER), capabilities.supportedFits());
        assertTrue(capabilities.supportsWidth());
        assertTrue(capabilities.supportsHeight());
        assertTrue(capabilities.supportsMaxWidth());
        assertTrue(capabilities.supportsMaxHeight());
        assertTrue(capabilities.supportsQuality());
        assertTrue(capabilities.privacyStrippedByDefault());
        assertEquals(Set.of(TrickplayAssetKind.PREVIEW_STRIP), trickplayRequest.assetKinds());
        assertNotNull(trickplayRequest.tileLayout());
        assertEquals("item-1", createCollection.itemIds().get(0));
        assertEquals("item-2", updateCollection.itemIds().get(0));
        assertThrows(UnsupportedOperationException.class, () -> createRequest.sourceRoots().add(new LibrarySourceRootWriteRequest("/tmp")));
        assertThrows(UnsupportedOperationException.class, () -> updateRequest.sourceRoots().add(new LibrarySourceRootWriteRequest("/tmp")));
        assertThrows(UnsupportedOperationException.class, () -> createCollection.itemIds().add("item-3"));
        assertThrows(UnsupportedOperationException.class, () -> updateCollection.itemIds().add("item-4"));
        assertThrows(UnsupportedOperationException.class, () -> capabilities.supportedFits().add(ImageTransformFit.FILL));
        assertThrows(UnsupportedOperationException.class, () -> trickplayRequest.assetKinds().add(TrickplayAssetKind.STORYBOARD_SHEET));
    }

    @Test
    void javaContractDefaultsPreserveLegacyFallbacks() {
        TrickplayAsset legacyAsset = new TrickplayAsset(
            TrickplayAssetKind.STORYBOARD_SHEET,
            "/trickplay/0.jpg",
            "image/jpeg",
            new ImageDimensions(1280, 720),
            new ImageDimensions(320, 180),
            10_000L,
            0L,
            150_000L,
            16
        );
        TrickplayAsset explicitAsset = new TrickplayAsset(
            TrickplayAssetKind.PREVIEW_STRIP,
            "/trickplay/strip.jpg",
            "image/jpeg",
            new ImageDimensions(1920, 180),
            new ImageDimensions(320, 180),
            5_000L,
            0L,
            25_000L,
            6,
            new TrickplayTileLayout(6, 1),
            null
        );
        TrickplayManifest manifest = new TrickplayManifest(
            90_000L,
            null,
            10_000L,
            new ArrayList<>(List.of(legacyAsset, explicitAsset)),
            new ArrayList<>(List.of(
                new TrickplayTimelineEntry(0L, 0),
                new TrickplayTimelineEntry(10_000L, null, 1, 0, 0)
            )),
            null
        );
        TrickplayDiscoveryMetadata discovery = new TrickplayDiscoveryMetadata(null, null);
        ImageTransformResult result = new ImageTransformResult(320, 180, "image/jpeg", 90, null, null, null);
        Map<String, Integer> immutableMap = ContractCollections.immutableMap(new LinkedHashMap<>(Map.of("a", 1)));

        assertTrue(legacyAsset.cacheable());
        assertTrue(explicitAsset.cacheable());
        assertTrue(manifest.cacheable());
        assertNotNull(manifest.request());
        assertEquals(TrickplayAssetKind.STORYBOARD_SHEET, manifest.timeline().get(1).kind());
        assertTrue(discovery.cacheableByDefault());
        assertNotNull(discovery.defaultRequest());
        assertEquals(ImageTransformFit.CONTAIN, result.fit());
        assertTrue(result.cacheable());
        assertTrue(result.privacyStripped());
        assertEquals(1, immutableMap.size());
        assertTrue(ContractCollections.immutableMap(null).isEmpty());
        assertTrue(ContractCollections.immutableMap(Map.of()).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> manifest.assets().add(legacyAsset));
        assertThrows(UnsupportedOperationException.class, () -> manifest.timeline().add(new TrickplayTimelineEntry(20_000L, 2)));
        assertThrows(UnsupportedOperationException.class, () -> immutableMap.put("b", 2));
    }

    @Test
    void javaMediaContractsKeepConvenienceConstructorsAndStatusDefaults() {
        MediaThumbnail minimalThumbnail = new MediaThumbnail(
            "thumb-1",
            "object-1",
            MediaThumbnailKind.THUMBNAIL,
            "jpg",
            "thumbs/object-1.jpg",
            "2026-05-01T10:00:00Z",
            "2026-05-01T10:00:00Z"
        );
        MediaThumbnail explicitThumbnail = new MediaThumbnail(
            "thumb-2",
            "object-1",
            MediaThumbnailKind.PREVIEW_FRAME,
            320,
            180,
            "jpg",
            "thumbs/object-2.jpg",
            5_000L,
            null,
            null,
            "2026-05-01T10:05:00Z",
            "2026-05-01T10:05:00Z"
        );
        MediaThumbnailReference thumbnailReference = new MediaThumbnailReference(
            "thumb-1",
            MediaThumbnailKind.THUMBNAIL,
            MediaThumbnailStatus.READY
        );
        Library legacyLibrary = new Library("library-1", "Movies", LibraryType.MOVIE, "2026-05-01T09:00:00Z", "2026-05-01T09:00:00Z");
        Library defaultedLibrary = new Library(
            "library-2",
            "Shows",
            null,
            LibraryType.SHOW,
            new ArrayList<>(List.of(new LibrarySourceRoot("root-1", "/srv/media/shows", "Shows", 1, "c", "u"))),
            null,
            "2026-05-01T09:10:00Z",
            "2026-05-01T09:10:00Z"
        );
        LibraryScanState defaultedScanState = new LibraryScanState(null, "2026-05-01T09:15:00Z", null, null, null);
        MediaObject legacyObject = new MediaObject(
            "object-1",
            MediaKind.VIDEO,
            "/srv/media/movies/movie.mkv",
            "Movies/movie.mkv",
            "video/x-matroska",
            42_000_000L,
            "2026-05-01T09:20:00Z",
            "Movie",
            "2026-05-01T09:20:00Z",
            "2026-05-01T09:20:00Z"
        );
        MediaObject defaultedObject = new MediaObject(
            "object-2",
            MediaKind.AUDIO,
            "/srv/media/music/song.mp3",
            "Music/song.mp3",
            "audio/mpeg",
            3_500_000L,
            "2026-05-01T09:25:00Z",
            "",
            null,
            "Song",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "2026-05-01T09:25:00Z",
            "2026-05-01T09:25:00Z",
            null
        );
        UserMediaStateWriteRequest writeRequest = new UserMediaStateWriteRequest();
        MediaObjectCorrelation correlation = new MediaObjectCorrelation(
            "corr-1",
            "object-1",
            "PRIMARY_PATH",
            "2026-05-01T09:30:00Z",
            "2026-05-01T09:30:00Z"
        );

        assertFalse(minimalThumbnail.isPrimary());
        assertEquals(MediaThumbnailStatus.PENDING, minimalThumbnail.status());
        assertFalse(explicitThumbnail.isPrimary());
        assertEquals(MediaThumbnailStatus.PENDING, explicitThumbnail.status());
        assertNull(thumbnailReference.url());
        assertEquals("", legacyLibrary.description());
        assertTrue(legacyLibrary.sourceRoots().isEmpty());
        assertEquals(LibraryScanStatus.IDLE, legacyLibrary.scanState().status());
        assertEquals("", defaultedLibrary.description());
        assertEquals(LibraryScanStatus.IDLE, defaultedLibrary.scanState().status());
        assertEquals(LibraryScanStatus.IDLE, defaultedScanState.status());
        assertEquals(MediaObjectContracts.MEDIA_OBJECT_HASH_ALGORITHM_NONE, legacyObject.hashAlgorithm());
        assertEquals(MediaObjectStatus.ACTIVE, legacyObject.status());
        assertEquals(MediaObjectContracts.MEDIA_OBJECT_HASH_ALGORITHM_NONE, defaultedObject.hashAlgorithm());
        assertEquals(MediaObjectStatus.ACTIVE, defaultedObject.status());
        assertNull(writeRequest.resumePositionMillis());
        assertFalse(writeRequest.watched());
        assertFalse(writeRequest.favorite());
        assertNull(writeRequest.rating());
        assertEquals("corr-1", correlation.correlationId());
        assertThrows(UnsupportedOperationException.class, () -> defaultedLibrary.sourceRoots().add(
            new LibrarySourceRoot("root-2", "/srv/media/shows-2", "Shows 2", 2, "c", "u")
        ));
    }

    @Test
    void userStateAndListingConvenienceConstructorsStayStable() {
        UserMediaState state = new UserMediaState("user-1", "object-1");
        UserMediaStateListing stateListing = new UserMediaStateListing(3, 2, 25);
        LibraryItemListing itemListing = new LibraryItemListing(4);
        LibraryCollectionListing collectionListing = new LibraryCollectionListing(5);
        ImageViewingMetadata viewingMetadata = new ImageViewingMetadata(null, null);

        assertEquals("user-1", state.getUserId());
        assertEquals("object-1", state.getObjectId());
        assertFalse(state.getWatched());
        assertFalse(state.getFavorite());
        assertEquals(0, state.getPlayCount());
        assertTrue(stateListing.items().isEmpty());
        assertEquals(3, stateListing.total());
        assertEquals(2, stateListing.page());
        assertEquals(25, stateListing.limit());
        assertTrue(itemListing.items().isEmpty());
        assertEquals(4, itemListing.total());
        assertTrue(collectionListing.collections().isEmpty());
        assertEquals(5, collectionListing.total());
        assertNotNull(viewingMetadata.defaultTransform());
        assertNotNull(viewingMetadata.capabilities());
    }
}
