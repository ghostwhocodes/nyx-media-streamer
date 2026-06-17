package com.nyx.browse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.common.PathSecurity;
import com.nyx.common.VirtualPathResolver;
import com.nyx.config.MediaRootConfig;
import com.nyx.ffmpeg.ProbeService;
import com.nyx.json.NyxJson;
import com.nyx.media.AudioMetadataService;
import com.nyx.media.MediaObjectResolver;
import com.nyx.media.MediaObjectService;
import com.nyx.media.MediaThumbnailService;
import com.nyx.media.ModuleMediaTestSupport;
import com.nyx.media.contracts.MediaItem;
import com.zaxxer.hikari.HikariDataSource;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrowseServiceTest {
    @TempDir
    Path tempDir;

    private Path moviesRoot;
    private Path musicRoot;
    private Path mediaRoot;
    private BrowseService service;
    private final List<HikariDataSource> dataSources = new ArrayList<>();
    private final ObjectMapper json = NyxJson.newMapper();

    @BeforeEach
    void setup() throws Exception {
        moviesRoot = Files.createDirectories(tempDir.resolve("movies"));
        musicRoot = Files.createDirectories(tempDir.resolve("music"));
        mediaRoot = Files.createDirectories(tempDir.resolve("testmedia"));
        service = createService(List.of(moviesRoot, musicRoot), null);
    }

    @AfterEach
    void teardown() {
        ModuleMediaTestSupport.closeDataSources(dataSources);
    }

    private Path createImage(Path dir, String name) throws Exception {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        BufferedImage image = new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, name.substring(name.lastIndexOf('.') + 1), file.toFile());
        return file;
    }

    private Path createAudio(Path dir, String name) throws Exception {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.write(file, new byte[1_024]);
    }

    private Path createVideo(Path dir, String name) throws Exception {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.write(file, new byte[2_048]);
    }

    private BrowseService createService(List<Path> roots, ProbeService probeService) {
        List<MediaRootConfig> configs = roots.stream().map(MediaRootConfig::new).toList();
        VirtualPathResolver resolver = new VirtualPathResolver(configs);
        PathSecurity pathSecurity = new PathSecurity(roots);
        return new BrowseService(resolver, pathSecurity, List.of(150, 300), probeService);
    }

    private TestServices createServiceWithObjects(List<Path> roots) throws Exception {
        List<MediaRootConfig> configs = roots.stream().map(MediaRootConfig::new).toList();
        VirtualPathResolver resolver = new VirtualPathResolver(configs);
        PathSecurity pathSecurity = new PathSecurity(roots);
        ProbeService probeService = new ProbeService();
        AudioMetadataService audioMetadataService = new AudioMetadataService(probeService);
        Path dbDir = Files.createDirectories(tempDir.resolve("objects-db-" + dataSources.size()));
        var resources = MediaObjectService.createDatabase(dbDir);
        dataSources.add(resources.getDataSource());
        MediaObjectService mediaObjectService = new MediaObjectService(resources.getJdbi());
        MediaObjectResolver mediaObjectResolver = new MediaObjectResolver(
            mediaObjectService,
            probeService,
            audioMetadataService,
            new MediaThumbnailService(resources.getJdbi())
        );
        BrowseService browseService = new BrowseService(
            resolver,
            pathSecurity,
            List.of(150, 300),
            probeService,
            audioMetadataService,
            mediaObjectResolver
        );
        return new TestServices(browseService, mediaObjectService);
    }

    private Path createRealJpeg(Path dir, String name, int width, int height) throws Exception {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, "jpg", file.toFile());
        return file;
    }

    private Path createRealPng(Path dir, String name, int width, int height) throws Exception {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(image, "png", file.toFile());
        return file;
    }

    private void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                });
        }
    }

    @Test
    void listRootsReturnsAllConfiguredRootsAsFolders() {
        var roots = service.listRoots();
        assertEquals(2, roots.size());
        assertEquals("movies", roots.get(0).name());
        assertEquals("movies", roots.get(0).path());
        assertEquals("music", roots.get(1).name());
        assertEquals("music", roots.get(1).path());
    }

    @Test
    void browseWithEmptyPathReturnsRoots() {
        var listing = service.browse("", 1, 50);
        assertEquals(2, listing.total());
        assertTrue(listing.items().stream().allMatch(MediaItem.Folder.class::isInstance));
    }

    @Test
    void browseRootListsDirectoryContents() throws Exception {
        createImage(moviesRoot, "poster.jpg");
        createVideo(moviesRoot, "film.mp4");
        Files.createDirectories(moviesRoot.resolve("subfolder"));

        var listing = service.browse("movies", 1, 50);
        assertEquals(3, listing.total());

        Set<String> types = listing.items().stream().map(item -> item.getClass().getSimpleName()).collect(java.util.stream.Collectors.toSet());
        assertTrue(types.contains("Folder"));
        assertTrue(types.contains("Image"));
        assertTrue(types.contains("Video"));
    }

    @Test
    void browseNestedDirectoryWorks() throws Exception {
        Path sub = Files.createDirectories(moviesRoot.resolve("action"));
        createVideo(sub, "movie.mp4");

        var listing = service.browse("movies/action", 1, 50);
        assertEquals(1, listing.total());
        assertInstanceOf(MediaItem.Video.class, listing.items().getFirst());
        assertEquals("movies/action/movie.mp4", listing.items().getFirst().path());
    }

    @Test
    void browsePaginationWorks() throws Exception {
        for (int i = 1; i <= 5; i++) {
            createImage(moviesRoot, "img_" + i + ".jpg");
        }

        var page1 = service.browse("movies", 1, 2);
        assertEquals(5, page1.total());
        assertEquals(2, page1.items().size());

        var page3 = service.browse("movies", 3, 2);
        assertEquals(1, page3.items().size());
    }

    @Test
    void browseSortByNamePutsFoldersFirst() throws Exception {
        createVideo(moviesRoot, "zzz.mp4");
        Files.createDirectories(moviesRoot.resolve("aaa-folder"));
        createImage(moviesRoot, "bbb.jpg");

        var listing = service.browse("movies", 1, 50, BrowseSortOrder.NAME);
        assertInstanceOf(MediaItem.Folder.class, listing.items().getFirst());
    }

    @Test
    void browseClassifiesImagesWithThumbnails() throws Exception {
        createImage(moviesRoot, "photo.jpg");

        var listing = service.browse("movies", 1, 50);
        MediaItem.Image imageItem = listing.items().stream()
            .filter(MediaItem.Image.class::isInstance)
            .map(MediaItem.Image.class::cast)
            .findFirst()
            .orElseThrow();

        assertEquals("photo.jpg", imageItem.name());
        assertEquals("movies/photo.jpg", imageItem.path());
        assertEquals(List.of(150, 300), imageItem.thumbnailSizes());
        assertNotNull(imageItem.viewing());
        assertTrue(imageItem.viewing().capabilities().privacyStrippedByDefault());
    }

    @Test
    void browseClassifiesAudioAsMusic() throws Exception {
        createAudio(musicRoot, "song.mp3");

        var listing = service.browse("music", 1, 50);
        MediaItem.Music musicItem = listing.items().stream()
            .filter(MediaItem.Music.class::isInstance)
            .map(MediaItem.Music.class::cast)
            .findFirst()
            .orElseThrow();

        assertEquals("song.mp3", musicItem.name());
        assertEquals("music/song.mp3", musicItem.path());
        assertEquals("audio/mpeg", musicItem.mimeType());
    }

    @Test
    void browseClassifiesVideosWithPreviewAndTrickplayDiscoveryMetadata() throws Exception {
        createVideo(moviesRoot, "film.mp4");

        var listing = service.browse("movies", 1, 50);
        MediaItem.Video videoItem = listing.items().stream()
            .filter(MediaItem.Video.class::isInstance)
            .map(MediaItem.Video.class::cast)
            .findFirst()
            .orElseThrow();

        assertEquals("film.mp4", videoItem.name());
        assertEquals("movies/film.mp4", videoItem.path());
        assertNotNull(videoItem.viewing());
        assertNotNull(videoItem.viewing().trickplay());
        assertTrue(videoItem.viewing().trickplay().cacheableByDefault());
    }

    @Test
    void browseKeepsVideoDiscoveryMetadataRouteNeutralForReservedCharacters() throws Exception {
        createVideo(moviesRoot, "Fast & Furious #1.mp4");

        var listing = service.browse("movies", 1, 50);
        MediaItem.Video videoItem = listing.items().stream()
            .filter(MediaItem.Video.class::isInstance)
            .map(MediaItem.Video.class::cast)
            .findFirst()
            .orElseThrow();

        assertEquals("movies/Fast & Furious #1.mp4", videoItem.path());
        assertNotNull(videoItem.viewing());
        assertNotNull(videoItem.viewing().trickplay());
    }

    @Test
    void browseIgnoresUnknownFileTypes() throws Exception {
        Files.writeString(moviesRoot.resolve("readme.txt"), "hello");
        createVideo(moviesRoot, "film.mp4");

        var listing = service.browse("movies", 1, 50);
        assertEquals(1, listing.total());
    }

    @Test
    void searchFilesFindsMatchingFilesAcrossRoots() throws Exception {
        createVideo(moviesRoot, "test_movie.mp4");
        createAudio(musicRoot, "test_song.mp3");

        var result = service.searchFiles("test", 1, 50);
        assertEquals(2, result.total());
        assertEquals("test", result.query());
    }

    @Test
    void searchFilesIsCaseInsensitive() throws Exception {
        createVideo(moviesRoot, "UPPERCASE.MP4");

        var result = service.searchFiles("uppercase", 1, 50);
        assertEquals(1, result.total());
    }

    @Test
    void searchFilesWithTypeFilterReturnsOnlyMatchingType() throws Exception {
        createVideo(moviesRoot, "test.mp4");
        createImage(moviesRoot, "test.jpg");
        createAudio(musicRoot, "test.mp3");

        var imageResult = service.searchFiles("test", 1, 50, MediaTypeFilter.IMAGE);
        assertEquals(1, imageResult.total());
        assertInstanceOf(MediaItem.Image.class, imageResult.items().getFirst());

        var musicResult = service.searchFiles("test", 1, 50, MediaTypeFilter.MUSIC);
        assertEquals(1, musicResult.total());
        assertInstanceOf(MediaItem.Music.class, musicResult.items().getFirst());

        var videoResult = service.searchFiles("test", 1, 50, MediaTypeFilter.VIDEO);
        assertEquals(1, videoResult.total());
        assertInstanceOf(MediaItem.Video.class, videoResult.items().getFirst());
    }

    @Test
    void searchFilesPaginationWorks() throws Exception {
        for (int i = 1; i <= 5; i++) {
            createVideo(moviesRoot, "match_" + i + ".mp4");
        }

        var result = service.searchFiles("match", 1, 2);
        assertEquals(5, result.total());
        assertEquals(2, result.items().size());
    }

    @Test
    void searchFilesReturnsEmptyForNoMatches() throws Exception {
        createVideo(moviesRoot, "film.mp4");

        var result = service.searchFiles("nonexistent", 1, 50);
        assertEquals(0, result.total());
        assertTrue(result.items().isEmpty());
    }

    @Test
    void searchFilesReturnsVirtualPaths() throws Exception {
        Path sub = Files.createDirectories(moviesRoot.resolve("action"));
        createVideo(sub, "hero.mp4");

        var result = service.searchFiles("hero", 1, 50);
        assertEquals(1, result.total());
        assertEquals("movies/action/hero.mp4", result.items().getFirst().path());
    }

    @Test
    void browseResolvesMediaObjectsOnlyForReturnedPageItems() throws Exception {
        createVideo(moviesRoot, "alpha.mp4");
        createVideo(moviesRoot, "beta.mp4");
        createVideo(moviesRoot, "gamma.mp4");
        TestServices testServices = createServiceWithObjects(List.of(moviesRoot, musicRoot));

        var listing = testServices.service().browse("movies", 1, 1, BrowseSortOrder.NAME);

        assertEquals(3, listing.total());
        assertEquals(List.of("alpha.mp4"), listing.items().stream().map(MediaItem::name).toList());
        assertNotNull(testServices.mediaObjectService().getByPath(moviesRoot.resolve("alpha.mp4").toString()));
        assertNull(testServices.mediaObjectService().getByPath(moviesRoot.resolve("beta.mp4").toString()));
        assertNull(testServices.mediaObjectService().getByPath(moviesRoot.resolve("gamma.mp4").toString()));
    }

    @Test
    void searchFilesAppliesFilteringAndPaginationBeforeMediaObjectResolution() throws Exception {
        createAudio(musicRoot, "match-a.mp3");
        createAudio(musicRoot, "match-b.mp3");
        createImage(moviesRoot, "match-cover.jpg");
        TestServices testServices = createServiceWithObjects(List.of(moviesRoot, musicRoot));

        var result = testServices.service().searchFiles("match", 1, 1, MediaTypeFilter.MUSIC);

        assertEquals(2, result.total());
        assertEquals(List.of("match-a.mp3"), result.items().stream().map(MediaItem::name).toList());
        assertNotNull(testServices.mediaObjectService().getByPath(musicRoot.resolve("match-a.mp3").toString()));
        assertNull(testServices.mediaObjectService().getByPath(musicRoot.resolve("match-b.mp3").toString()));
        assertNull(testServices.mediaObjectService().getByPath(moviesRoot.resolve("match-cover.jpg").toString()));
    }

    @Test
    void browseSortByDatePutsFoldersFirst() throws Exception {
        createVideo(moviesRoot, "early.mp4");
        createImage(moviesRoot, "late.jpg");
        Files.createDirectories(moviesRoot.resolve("subfolder"));

        var listing = createService(List.of(moviesRoot, musicRoot), null).browse("movies", 1, 50, BrowseSortOrder.DATE);
        assertTrue(listing.items().getFirst() instanceof MediaItem.Folder, "Folders should come first in DATE sort");
    }

    @Test
    void browseSortBySizePutsFoldersFirstAndSortsBySizeDescending() throws Exception {
        createVideo(moviesRoot, "small.mp4");
        createImage(moviesRoot, "big.jpg");
        Files.createDirectories(moviesRoot.resolve("subfolder"));

        var listing = createService(List.of(moviesRoot, musicRoot), null).browse("movies", 1, 50, BrowseSortOrder.SIZE);
        assertTrue(listing.items().getFirst() instanceof MediaItem.Folder, "Folders should come first in SIZE sort");
    }

    @Test
    void searchFilesImageFilterReturnsOnlyImages() throws Exception {
        createImage(moviesRoot, "photo.jpg");
        createVideo(moviesRoot, "video.mp4");
        createAudio(musicRoot, "song.mp3");

        var result = createService(List.of(moviesRoot, musicRoot), null).searchFiles("", 1, 50, MediaTypeFilter.IMAGE);
        assertTrue(result.items().stream().allMatch(MediaItem.Image.class::isInstance));
    }

    @Test
    void searchFilesVideoFilterReturnsOnlyVideos() throws Exception {
        createImage(moviesRoot, "photo.jpg");
        createVideo(moviesRoot, "video.mp4");

        var result = createService(List.of(moviesRoot, musicRoot), null).searchFiles("video", 1, 50, MediaTypeFilter.VIDEO);
        assertTrue(result.items().stream().allMatch(MediaItem.Video.class::isInstance));
    }

    @Test
    void searchFilesMusicFilterReturnsOnlyMusic() throws Exception {
        createAudio(musicRoot, "song.mp3");
        createVideo(moviesRoot, "video.mp4");

        var result = createService(List.of(moviesRoot, musicRoot), null).searchFiles("", 1, 50, MediaTypeFilter.MUSIC);
        assertTrue(result.items().stream().allMatch(MediaItem.Music.class::isInstance));
    }

    @Test
    void searchFilesHandlesRootWithDeletedDirectoryGracefully() throws Exception {
        Path ephemeralRoot = Files.createDirectories(tempDir.resolve("ephemeral"));
        VirtualPathResolver resolver = new VirtualPathResolver(List.of(
            new MediaRootConfig(moviesRoot),
            new MediaRootConfig(ephemeralRoot)
        ));
        PathSecurity pathSecurity = new PathSecurity(List.of(moviesRoot, ephemeralRoot));
        BrowseService browseService = new BrowseService(resolver, pathSecurity, List.of(150, 300), null);

        createVideo(moviesRoot, "found.mp4");
        deleteRecursively(ephemeralRoot);

        var result = browseService.searchFiles("found", 1, 50);
        assertEquals(1, result.total());
    }

    @Test
    void browseWithProbeServicePopulatesAudioMetadata() throws Exception {
        BrowseService browseService = createService(List.of(moviesRoot, musicRoot), new ProbeService());
        createAudio(musicRoot, "song.mp3");

        var listing = browseService.browse("music", 1, 50);
        assertTrue(listing.items().stream().anyMatch(MediaItem.Music.class::isInstance));
    }

    @Test
    void browseWithNullProbeServiceStillClassifiesAudio() throws Exception {
        BrowseService browseService = createService(List.of(moviesRoot, musicRoot), null);
        createAudio(musicRoot, "track.mp3");

        var listing = browseService.browse("music", 1, 50);
        List<MediaItem.Music> musicItems = listing.items().stream()
            .filter(MediaItem.Music.class::isInstance)
            .map(MediaItem.Music.class::cast)
            .toList();
        assertEquals(1, musicItems.size());
        assertNull(musicItems.getFirst().duration());
    }

    @Test
    void browseReturnsFoldersForDirectories() throws Exception {
        Files.createDirectories(mediaRoot.resolve("photos"));
        Files.createDirectories(mediaRoot.resolve("videos"));

        BrowseService browseService = createService(List.of(mediaRoot), null);
        var listing = browseService.browse("testmedia", 1, 50);

        List<MediaItem.Folder> folders = listing.items().stream()
            .filter(MediaItem.Folder.class::isInstance)
            .map(MediaItem.Folder.class::cast)
            .toList();
        assertEquals(2, folders.size(), "Should find 2 subdirectories");
        Set<String> folderNames = folders.stream().map(MediaItem.Folder::name).collect(java.util.stream.Collectors.toSet());
        assertTrue(folderNames.contains("photos"), "Should contain 'photos' folder");
        assertTrue(folderNames.contains("videos"), "Should contain 'videos' folder");
        folders.forEach(folder -> assertEquals(0L, folder.size(), "Folder size should be 0"));
    }

    @Test
    void browseClassifiesImageFile() throws Exception {
        createRealJpeg(mediaRoot, "sunset.jpg", 200, 150);

        BrowseService browseService = createService(List.of(mediaRoot), null);
        var listing = browseService.browse("testmedia", 1, 50);

        List<MediaItem.Image> images = listing.items().stream()
            .filter(MediaItem.Image.class::isInstance)
            .map(MediaItem.Image.class::cast)
            .toList();
        assertEquals(1, images.size(), "Should find exactly 1 image");
        MediaItem.Image image = images.getFirst();
        assertEquals("sunset.jpg", image.name());
        assertEquals("image/jpeg", image.mimeType());
        assertEquals(200, image.width(), "Width should be read from image dimensions");
        assertEquals(150, image.height(), "Height should be read from image dimensions");
        assertTrue(image.size() > 0, "Image file size should be positive");
        assertNotNull(image.takenAt(), "takenAt should be populated from file mtime");
        assertEquals(List.of(150, 300), image.thumbnailSizes(), "Should have 2 thumbnail sizes (150, 300)");
    }

    @Test
    void browseClassifiesVideoFile() throws Exception {
        Files.write(mediaRoot.resolve("clip.mp4"), new byte[4_096]);

        BrowseService browseService = createService(List.of(mediaRoot), null);
        var listing = browseService.browse("testmedia", 1, 50);

        List<MediaItem.Video> videos = listing.items().stream()
            .filter(MediaItem.Video.class::isInstance)
            .map(MediaItem.Video.class::cast)
            .toList();
        assertEquals(1, videos.size(), "Should find exactly 1 video");
        MediaItem.Video video = videos.getFirst();
        assertEquals("clip.mp4", video.name());
        assertEquals("video/mp4", video.mimeType());
        assertEquals(4_096L, video.size());
    }

    @Test
    void browseClassifiesAudioFileWithoutProbeService() throws Exception {
        Files.write(mediaRoot.resolve("track.mp3"), new byte[2_048]);

        BrowseService browseService = createService(List.of(mediaRoot), null);
        var listing = browseService.browse("testmedia", 1, 50);

        List<MediaItem.Music> musicItems = listing.items().stream()
            .filter(MediaItem.Music.class::isInstance)
            .map(MediaItem.Music.class::cast)
            .toList();
        assertEquals(1, musicItems.size(), "Should find exactly 1 music item");
        MediaItem.Music music = musicItems.getFirst();
        assertEquals("track.mp3", music.name());
        assertEquals("audio/mpeg", music.mimeType());
        assertEquals(2_048L, music.size());
        assertNull(music.duration(), "Duration should be null without ProbeService");
        assertNull(music.bitrate(), "Bitrate should be null without ProbeService");
        assertNull(music.channels(), "Channels should be null without ProbeService");
        assertNull(music.artist(), "Artist should be null without ProbeService");
        assertNull(music.album(), "Album should be null without ProbeService");
        assertNull(music.title(), "Title should be null without ProbeService");
    }

    @Test
    void browseWithDateSortReturnsDateOrderedResults() throws Exception {
        Files.createDirectories(mediaRoot.resolve("aFolder"));
        Path videoFile = Files.write(mediaRoot.resolve("beta.mp4"), new byte[100]);
        createRealJpeg(mediaRoot, "alpha.jpg", 100, 80);

        FileTime now = FileTime.fromMillis(System.currentTimeMillis());
        FileTime earlier = FileTime.fromMillis(System.currentTimeMillis() - 60_000);
        Files.setLastModifiedTime(mediaRoot.resolve("alpha.jpg"), now);
        Files.setLastModifiedTime(videoFile, earlier);

        BrowseService browseService = createService(List.of(mediaRoot), null);
        var listing = browseService.browse("testmedia", 1, 50, BrowseSortOrder.DATE);

        assertTrue(listing.items().getFirst() instanceof MediaItem.Folder, "Folder should be first in DATE sort");
        List<MediaItem> nonFolders = listing.items().stream()
            .filter(item -> !(item instanceof MediaItem.Folder))
            .toList();
        assertTrue(nonFolders.size() >= 2, "Should have at least 2 non-folder items");
        assertEquals("alpha.jpg", nonFolders.get(0).name(), "Newer file should come first in DATE descending");
        assertEquals("beta.mp4", nonFolders.get(1).name(), "Older file should come second in DATE descending");
    }

    @Test
    void browseWithSizeSortReturnsSizeOrderedResults() throws Exception {
        Files.createDirectories(mediaRoot.resolve("subdir"));
        Files.write(mediaRoot.resolve("small.mp4"), new byte[100]);
        Files.write(mediaRoot.resolve("large.mp4"), new byte[5_000]);
        Files.write(mediaRoot.resolve("medium.mp4"), new byte[2_000]);

        BrowseService browseService = createService(List.of(mediaRoot), null);
        var listing = browseService.browse("testmedia", 1, 50, BrowseSortOrder.SIZE);

        assertTrue(listing.items().getFirst() instanceof MediaItem.Folder, "Folder should be first in SIZE sort");
        List<MediaItem> nonFolders = listing.items().stream()
            .filter(item -> !(item instanceof MediaItem.Folder))
            .toList();
        assertEquals(3, nonFolders.size());
        assertTrue(nonFolders.get(0).size() >= nonFolders.get(1).size(), "First non-folder should have largest size");
        assertTrue(nonFolders.get(1).size() >= nonFolders.get(2).size(), "Second non-folder should be larger than third");
        assertEquals("large.mp4", nonFolders.get(0).name());
        assertEquals("medium.mp4", nonFolders.get(1).name());
        assertEquals("small.mp4", nonFolders.get(2).name());
    }

    @Test
    void searchFilesFindsFilesByName() throws Exception {
        Files.write(mediaRoot.resolve("report.mp4"), new byte[100]);
        Files.write(mediaRoot.resolve("sunset.mp4"), new byte[200]);
        createRealJpeg(mediaRoot, "sunset_photo.jpg", 100, 80);

        BrowseService browseService = createService(List.of(mediaRoot), null);
        var result = browseService.searchFiles("sunset", 1, 50);

        assertEquals(2, result.total(), "Should find 2 files matching 'sunset'");
        assertEquals("sunset", result.query());
        assertTrue(result.items().stream().allMatch(item -> item.name().toLowerCase().contains("sunset")));
    }

    @Test
    void searchFilesWithTypeFilterImage() throws Exception {
        createRealJpeg(mediaRoot, "photo_a.jpg", 100, 80);
        createRealPng(mediaRoot, "photo_b.png", 64, 48);
        Files.write(mediaRoot.resolve("photo_c.mp4"), new byte[100]);
        Files.write(mediaRoot.resolve("photo_d.mp3"), new byte[100]);

        BrowseService browseService = createService(List.of(mediaRoot), null);
        var result = browseService.searchFiles("photo", 1, 50, MediaTypeFilter.IMAGE);

        assertEquals(2, result.total(), "Should find exactly 2 image files");
        assertTrue(result.items().stream().allMatch(MediaItem.Image.class::isInstance), "All results should be Image items");
        Set<String> names = result.items().stream().map(MediaItem::name).collect(java.util.stream.Collectors.toSet());
        assertTrue(names.contains("photo_a.jpg"));
        assertTrue(names.contains("photo_b.png"));
    }

    @Test
    void searchFilesWithTypeFilterMusic() throws Exception {
        Files.write(mediaRoot.resolve("melody.mp3"), new byte[100]);
        Files.write(mediaRoot.resolve("melody.flac"), new byte[200]);
        createRealJpeg(mediaRoot, "melody_cover.jpg", 100, 80);
        Files.write(mediaRoot.resolve("melody_clip.mp4"), new byte[300]);

        BrowseService browseService = createService(List.of(mediaRoot), null);
        var result = browseService.searchFiles("melody", 1, 50, MediaTypeFilter.MUSIC);

        assertEquals(2, result.total(), "Should find exactly 2 music files");
        assertTrue(result.items().stream().allMatch(MediaItem.Music.class::isInstance), "All results should be Music items");
        Set<String> names = result.items().stream().map(MediaItem::name).collect(java.util.stream.Collectors.toSet());
        assertTrue(names.contains("melody.mp3"));
        assertTrue(names.contains("melody.flac"));
    }

    @Test
    void mediaItemMusicSerializationRoundtrip() throws Exception {
        MediaItem.Music original = ModuleMediaTestSupport.mediaItemMusic(
            "awesome_track.mp3",
            "testmedia/awesome_track.mp3",
            5_242_880L,
            "audio/mpeg",
            245.7,
            320_000L,
            2,
            "Test Artist",
            "Test Album",
            "Awesome Track"
        );

        String encoded = json.writeValueAsString(original);
        MediaItem decoded = json.readValue(encoded, MediaItem.class);
        MediaItem.Music decodedMusic = assertInstanceOf(MediaItem.Music.class, decoded);

        assertEquals(original, decoded, "Decoded Music item should equal original");
        assertEquals("awesome_track.mp3", decodedMusic.name());
        assertEquals(5_242_880L, decodedMusic.size());
        assertEquals(245.7, decodedMusic.duration());
        assertEquals(320_000L, decodedMusic.bitrate());
        assertEquals(2, decodedMusic.channels());
        assertEquals("Test Artist", decodedMusic.artist());
        assertEquals("Test Album", decodedMusic.album());
        assertEquals("Awesome Track", decodedMusic.title());
        assertTrue(encoded.contains("\"type\":\"music\"") || encoded.contains("music"), "JSON should contain type discriminator");
    }

    @Test
    void mediaItemImageSerializationRoundtrip() throws Exception {
        MediaItem.Image original = ModuleMediaTestSupport.mediaItemImage(
            "vacation.jpg",
            "testmedia/vacation.jpg",
            3_145_728L,
            "image/jpeg",
            1920,
            1080,
            "2025-06-15T14:30:00Z",
            List.of(150, 300),
            ModuleMediaTestSupport.imageViewingMetadata()
        );

        String encoded = json.writeValueAsString(original);
        MediaItem decoded = json.readValue(encoded, MediaItem.class);
        MediaItem.Image decodedImage = assertInstanceOf(MediaItem.Image.class, decoded);

        assertEquals(original, decoded, "Decoded Image item should equal original");
        assertEquals("vacation.jpg", decodedImage.name());
        assertEquals(3_145_728L, decodedImage.size());
        assertEquals("image/jpeg", decodedImage.mimeType());
        assertEquals(1920, decodedImage.width());
        assertEquals(1080, decodedImage.height());
        assertEquals("2025-06-15T14:30:00Z", decodedImage.takenAt());
        assertEquals(List.of(150, 300), decodedImage.thumbnailSizes());
        assertNotNull(decodedImage.viewing().defaultTransform());
        assertNotNull(decodedImage.viewing().capabilities());
    }

    @Test
    void mediaItemVideoSerializationRoundtripIncludesViewingMetadata() throws Exception {
        MediaItem.Video original = ModuleMediaTestSupport.mediaItemVideo(
            "feature.mp4",
            "movies/feature.mp4",
            734_003_200L,
            "video/mp4",
            ModuleMediaTestSupport.videoViewingMetadata(
                ModuleMediaTestSupport.trickplayDiscoveryMetadata()
            )
        );

        String encoded = json.writeValueAsString(original);
        MediaItem decoded = json.readValue(encoded, MediaItem.class);
        MediaItem.Video decodedVideo = assertInstanceOf(MediaItem.Video.class, decoded);

        assertEquals(original, decoded);
        assertNotNull(decodedVideo.viewing().trickplay().defaultRequest());
        assertTrue(decodedVideo.viewing().trickplay().cacheableByDefault());
    }

    private record TestServices(BrowseService service, MediaObjectService mediaObjectService) {
    }
}
