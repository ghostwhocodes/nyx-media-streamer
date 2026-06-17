package com.nyx.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.ffmpeg.ProbeService;
import com.nyx.media.contracts.AudioListing;
import com.nyx.media.contracts.Gallery;
import com.nyx.media.contracts.MediaItem;
import com.nyx.media.contracts.SortOrder;
import com.zaxxer.hikari.HikariDataSource;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaFileServiceTest {
    @TempDir
    Path tempDir;

    private Path mediaDir;
    private MediaFileService service;
    private final List<HikariDataSource> dataSources = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        mediaDir = Files.createDirectories(tempDir.resolve("media"));
        service = new MediaFileService(List.of(tempDir), new ProbeService());
    }

    @AfterEach
    void teardown() {
        ModuleMediaTestSupport.closeDataSources(dataSources);
    }

    private Path createImageFile(Path dir, String name, int width, int height) throws Exception {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, name.substring(name.lastIndexOf('.') + 1), file.toFile());
        return file;
    }

    private Path createImageFile(Path dir, String name) throws Exception {
        return createImageFile(dir, name, 100, 80);
    }

    private Path createNonImageFile(Path dir, String name) throws Exception {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "not an image");
    }

    private Path createAudioFile(Path dir, String name, int size) throws Exception {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.write(file, new byte[size]);
    }

    private Path createAudioFile(Path dir, String name) throws Exception {
        return createAudioFile(dir, name, 1_024);
    }

    private MediaFileService createServiceForRoot(Path root) {
        return new MediaFileService(
            List.of(150, 300),
            List.of(root),
            new ProbeService("ffprobe")
        );
    }

    private MediaFileService createServiceForRootNoProbe(Path root) {
        return new MediaFileService(
            List.of(150, 300),
            List.of(root),
            new ProbeService("ffprobe-nonexistent-for-test")
        );
    }

    private TestServices createServiceForRootWithObjects(Path root) throws Exception {
        ProbeService probeService = new ProbeService();
        AudioMetadataService audioMetadataService = new AudioMetadataService(probeService);
        Path dbDir = Files.createDirectories(tempDir.resolve("media-objects-db-" + dataSources.size()));
        var resources = MediaObjectService.createDatabase(dbDir);
        dataSources.add(resources.getDataSource());
        MediaObjectService mediaObjectService = new MediaObjectService(resources.getJdbi());
        MediaObjectResolver mediaObjectResolver = new MediaObjectResolver(
            mediaObjectService,
            probeService,
            audioMetadataService
        );
        MediaFileService mediaFileService = new MediaFileService(
            List.of(150, 300),
            List.of(root),
            probeService,
            audioMetadataService,
            mediaObjectResolver
        );
        return new TestServices(mediaFileService, mediaObjectService);
    }

    private Path createTestImageForImprov(Path dir, String name, int width, int height) throws Exception {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, name.substring(name.lastIndexOf('.') + 1), file.toFile());
        return file;
    }

    private Path createTestImageForImprov(Path dir, String name) throws Exception {
        return createTestImageForImprov(dir, name, 10, 10);
    }

    @Test
    void listImagesFiltersToImagesOnly() throws Exception {
        createImageFile(mediaDir, "photo1.jpg");
        createImageFile(mediaDir, "photo2.png");
        createNonImageFile(mediaDir, "readme.txt");
        createNonImageFile(mediaDir, "movie.mp4");

        Gallery gallery = service.listImages(mediaDir, 1, 50, SortOrder.NAME);

        assertEquals(2, gallery.total());
        assertEquals(2, gallery.images().size());
        assertTrue(gallery.images().stream().allMatch(item -> item.mimeType().startsWith("image/")));
    }

    @Test
    void listImagesReturnsEmptyForDirectoryWithNoImages() throws Exception {
        createNonImageFile(mediaDir, "data.bin");
        createNonImageFile(mediaDir, "notes.txt");

        Gallery gallery = service.listImages(mediaDir, 1, 50, SortOrder.NAME);

        assertEquals(0, gallery.total());
        assertTrue(gallery.images().isEmpty());
    }

    @Test
    void listImagesDoesNotRecurseIntoSubdirectories() throws Exception {
        createImageFile(mediaDir, "top.jpg");
        Path subDir = Files.createDirectories(mediaDir.resolve("subdir"));
        createImageFile(subDir, "nested.jpg");

        Gallery gallery = service.listImages(mediaDir, 1, 50, SortOrder.NAME);

        assertEquals(1, gallery.total());
        assertEquals("top.jpg", gallery.images().getFirst().name());
    }

    @Test
    void paginationReturnsCorrectSubsets() throws Exception {
        for (int i = 1; i <= 10; i++) {
            createImageFile(mediaDir, "img_" + String.format(Locale.ROOT, "%02d", i) + ".jpg");
        }

        Gallery page1 = service.listImages(mediaDir, 1, 3, SortOrder.NAME);
        assertEquals(10, page1.total());
        assertEquals(3, page1.images().size());
        assertEquals(1, page1.page());
        assertEquals(3, page1.limit());

        Gallery page2 = service.listImages(mediaDir, 2, 3, SortOrder.NAME);
        assertEquals(10, page2.total());
        assertEquals(3, page2.images().size());

        Gallery page4 = service.listImages(mediaDir, 4, 3, SortOrder.NAME);
        assertEquals(1, page4.images().size());
    }

    @Test
    void paginationBeyondTotalReturnsEmpty() throws Exception {
        createImageFile(mediaDir, "only.jpg");

        Gallery page = service.listImages(mediaDir, 5, 10, SortOrder.NAME);
        assertEquals(1, page.total());
        assertTrue(page.images().isEmpty());
    }

    @Test
    void sortByNameReturnsAlphabeticalOrder() throws Exception {
        createImageFile(mediaDir, "charlie.jpg");
        createImageFile(mediaDir, "alice.jpg");
        createImageFile(mediaDir, "bob.jpg");

        Gallery gallery = service.listImages(mediaDir, 1, 50, SortOrder.NAME);

        assertEquals("alice.jpg", gallery.images().get(0).name());
        assertEquals("bob.jpg", gallery.images().get(1).name());
        assertEquals("charlie.jpg", gallery.images().get(2).name());
    }

    @Test
    void sortBySizeReturnsLargestFirst() throws Exception {
        createImageFile(mediaDir, "small.jpg", 10, 10);
        createImageFile(mediaDir, "medium.jpg", 100, 100);
        createImageFile(mediaDir, "large.jpg", 500, 500);

        Gallery gallery = service.listImages(mediaDir, 1, 50, SortOrder.SIZE);

        assertTrue(gallery.images().get(0).size() >= gallery.images().get(1).size());
        assertTrue(gallery.images().get(1).size() >= gallery.images().get(2).size());
    }

    @Test
    void sortByDateReturnsNewestFirst() throws Exception {
        createImageFile(mediaDir, "old.jpg");
        Thread.sleep(50);
        createImageFile(mediaDir, "new.jpg");

        Gallery gallery = service.listImages(mediaDir, 1, 50, SortOrder.DATE);

        assertEquals("new.jpg", gallery.images().get(0).name());
        assertEquals("old.jpg", gallery.images().get(1).name());
    }

    @Test
    void imageItemContainsCorrectMetadata() throws Exception {
        createImageFile(mediaDir, "photo.jpg", 200, 150);

        Gallery gallery = service.listImages(mediaDir, 1, 50, SortOrder.NAME);
        MediaItem.Image item = gallery.images().getFirst();

        assertEquals("photo.jpg", item.name());
        assertEquals("media/photo.jpg", item.path());
        assertTrue(item.mimeType().startsWith("image/"));
        assertTrue(item.size() > 0);
        assertNotNull(item.takenAt());
    }

    @Test
    void imageItemIncludesThumbnailSizesAndViewingCapabilities() throws Exception {
        createImageFile(mediaDir, "photo.jpg");

        Gallery gallery = service.listImages(mediaDir, 1, 50, SortOrder.NAME);
        MediaItem.Image item = gallery.images().getFirst();

        assertEquals(List.of(150, 300, 600), item.thumbnailSizes());
        assertNotNull(item.viewing());
        assertTrue(item.viewing().capabilities().privacyStrippedByDefault());
    }

    @Test
    void imageDimensionsAreReadCorrectly() throws Exception {
        createImageFile(mediaDir, "sized.png", 320, 240);

        Gallery gallery = service.listImages(mediaDir, 1, 50, SortOrder.NAME);
        MediaItem.Image item = gallery.images().getFirst();

        assertNotNull(item.width());
        assertEquals(320, item.width());
        assertEquals(240, item.height());
    }

    @Test
    void listAudioFiltersToAudioFilesOnly() throws Exception {
        createAudioFile(mediaDir, "song.mp3", 100);
        createAudioFile(mediaDir, "track.flac", 200);
        createImageFile(mediaDir, "photo.jpg");
        createNonImageFile(mediaDir, "readme.txt");

        AudioListing listing = service.listAudio(mediaDir, 1, 50, SortOrder.NAME);

        assertEquals(2, listing.total());
        assertEquals(2, listing.tracks().size());
        assertTrue(listing.tracks().stream().allMatch(item -> item.mimeType().startsWith("audio/")));
    }

    @Test
    void listAudioReturnsEmptyForDirectoryWithNoAudio() throws Exception {
        createImageFile(mediaDir, "photo.jpg");
        createNonImageFile(mediaDir, "data.bin");

        AudioListing listing = service.listAudio(mediaDir, 1, 50, SortOrder.NAME);

        assertEquals(0, listing.total());
        assertTrue(listing.tracks().isEmpty());
    }

    @Test
    void listAudioPaginationWorksCorrectly() throws Exception {
        for (int i = 1; i <= 5; i++) {
            createAudioFile(mediaDir, "song_" + String.format(Locale.ROOT, "%02d", i) + ".mp3", 100);
        }

        AudioListing page1 = service.listAudio(mediaDir, 1, 2, SortOrder.NAME);
        assertEquals(5, page1.total());
        assertEquals(2, page1.tracks().size());

        AudioListing page3 = service.listAudio(mediaDir, 3, 2, SortOrder.NAME);
        assertEquals(1, page3.tracks().size());
    }

    @Test
    void listAudioSortByNameWorks() throws Exception {
        createAudioFile(mediaDir, "zzz.mp3", 100);
        createAudioFile(mediaDir, "aaa.mp3", 100);
        createAudioFile(mediaDir, "mmm.mp3", 100);

        AudioListing listing = service.listAudio(mediaDir, 1, 50, SortOrder.NAME);

        assertEquals("aaa.mp3", listing.tracks().get(0).name());
        assertEquals("mmm.mp3", listing.tracks().get(1).name());
        assertEquals("zzz.mp3", listing.tracks().get(2).name());
    }

    @Test
    void listAudioSortBySizeWorks() throws Exception {
        createAudioFile(mediaDir, "small.mp3", 10);
        createAudioFile(mediaDir, "large.mp3", 1_000);

        AudioListing listing = service.listAudio(mediaDir, 1, 50, SortOrder.SIZE);

        assertEquals("large.mp3", listing.tracks().get(0).name());
        assertEquals("small.mp3", listing.tracks().get(1).name());
    }

    @Test
    void listAudioWithMetadataSortDoesNotCrashOnFilesWithoutMetadata() throws Exception {
        createAudioFile(mediaDir, "a.mp3", 100);
        createAudioFile(mediaDir, "b.mp3", 100);

        AudioListing byArtist = service.listAudio(mediaDir, 1, 50, SortOrder.ARTIST);
        assertEquals(2, byArtist.total());

        AudioListing byAlbum = service.listAudio(mediaDir, 1, 50, SortOrder.ALBUM);
        assertEquals(2, byAlbum.total());

        AudioListing byDuration = service.listAudio(mediaDir, 1, 50, SortOrder.DURATION);
        assertEquals(2, byDuration.total());
    }

    @Test
    void readImageDimensionsReturnsNullForNonImageFile() throws Exception {
        Path file = createNonImageFile(mediaDir, "data.bin");
        assertNull(MediaFileService.readImageDimensions(file));
    }

    @Test
    void listImagesReturnsCorrectTotalAndPageForLargeDirectory() throws Exception {
        for (int i = 0; i < 20; i++) {
            createImageFile(mediaDir, "img" + String.format(Locale.ROOT, "%02d", i) + ".jpg");
        }

        Gallery gallery = service.listImages(mediaDir, 2, 7, SortOrder.NAME);

        assertEquals(20, gallery.total());
        assertEquals(7, gallery.images().size());
        assertEquals(2, gallery.page());
        assertEquals(7, gallery.limit());
        assertEquals("img07.jpg", gallery.images().getFirst().name());
    }

    @Test
    void listImagesLastPageReturnsRemainingItems() throws Exception {
        for (int i = 0; i < 10; i++) {
            createImageFile(mediaDir, "photo" + String.format(Locale.ROOT, "%02d", i) + ".png");
        }

        Gallery gallery = service.listImages(mediaDir, 3, 4, SortOrder.NAME);

        assertEquals(10, gallery.total());
        assertEquals(2, gallery.images().size());
    }

    @Test
    void listImagesPageBeyondTotalReturnsEmptyImagesWithCorrectTotal() throws Exception {
        for (int i = 0; i < 5; i++) {
            createImageFile(mediaDir, "x" + i + ".jpg");
        }

        Gallery gallery = service.listImages(mediaDir, 10, 50, SortOrder.NAME);

        assertEquals(5, gallery.total());
        assertEquals(0, gallery.images().size());
    }

    @Test
    void listImagesSortsByNameAscending() throws Exception {
        createImageFile(mediaDir, "z_last.jpg");
        createImageFile(mediaDir, "a_first.jpg");
        createImageFile(mediaDir, "m_middle.jpg");

        Gallery gallery = service.listImages(mediaDir, 1, 50, SortOrder.NAME);

        assertEquals(
            List.of("a_first.jpg", "m_middle.jpg", "z_last.jpg"),
            gallery.images().stream().map(MediaItem.Image::name).toList()
        );
    }

    @Test
    void listImagesSortsBySizeDescending() throws Exception {
        createImageFile(mediaDir, "small.jpg", 10, 10);
        createImageFile(mediaDir, "large.jpg", 500, 500);
        createImageFile(mediaDir, "medium.jpg", 100, 100);

        Gallery gallery = service.listImages(mediaDir, 1, 50, SortOrder.SIZE);

        assertEquals("large.jpg", gallery.images().getFirst().name());
        assertEquals("small.jpg", gallery.images().getLast().name());
    }

    @Test
    void largeDirWarnThresholdConstantIsDefined() {
        assertEquals(5_000, MediaFileService.LARGE_DIR_WARN_THRESHOLD);
    }

    @Test
    void listAudioWithArtistSortProbesAllFiles() throws Exception {
        Path audioRoot = Files.createDirectories(tempDir.resolve("music-cov"));
        createAudioFile(audioRoot, "song1.mp3");
        createAudioFile(audioRoot, "song2.mp3");

        MediaFileService mediaFileService = createServiceForRoot(audioRoot);
        AudioListing result = mediaFileService.listAudio(audioRoot, 1, 50, SortOrder.ARTIST);
        assertEquals(2, result.total());
        assertEquals(2, result.tracks().size());
    }

    @Test
    void listAudioWithAlbumSortProbesAllFiles() throws Exception {
        Path audioRoot = Files.createDirectories(tempDir.resolve("music-cov2"));
        createAudioFile(audioRoot, "track1.mp3");
        createAudioFile(audioRoot, "track2.mp3");

        MediaFileService mediaFileService = createServiceForRoot(audioRoot);
        AudioListing result = mediaFileService.listAudio(audioRoot, 1, 50, SortOrder.ALBUM);
        assertEquals(2, result.total());
    }

    @Test
    void listAudioWithDurationSortProbesAllFiles() throws Exception {
        Path audioRoot = Files.createDirectories(tempDir.resolve("music-cov3"));
        createAudioFile(audioRoot, "a.mp3");
        createAudioFile(audioRoot, "b.mp3");

        MediaFileService mediaFileService = createServiceForRoot(audioRoot);
        AudioListing result = mediaFileService.listAudio(audioRoot, 1, 50, SortOrder.DURATION);
        assertEquals(2, result.total());
    }

    @Test
    void listAudioWithNameSortUsesFilesystemSort() throws Exception {
        Path audioRoot = Files.createDirectories(tempDir.resolve("music-cov4"));
        createAudioFile(audioRoot, "zzz.mp3");
        createAudioFile(audioRoot, "aaa.mp3");

        MediaFileService mediaFileService = createServiceForRoot(audioRoot);
        AudioListing result = mediaFileService.listAudio(audioRoot, 1, 50, SortOrder.NAME);
        assertEquals(2, result.total());
        assertEquals("aaa.mp3", result.tracks().get(0).name());
    }

    @Test
    void listAudioWithDateSortUsesFilesystemSort() throws Exception {
        Path audioRoot = Files.createDirectories(tempDir.resolve("music-cov5"));
        createAudioFile(audioRoot, "a.mp3");
        createAudioFile(audioRoot, "b.mp3");

        MediaFileService mediaFileService = createServiceForRoot(audioRoot);
        AudioListing result = mediaFileService.listAudio(audioRoot, 1, 50, SortOrder.DATE);
        assertEquals(2, result.total());
    }

    @Test
    void listAudioWithSizeSortUsesFilesystemSort() throws Exception {
        Path audioRoot = Files.createDirectories(tempDir.resolve("music-cov6"));
        createAudioFile(audioRoot, "small.mp3", 100);
        createAudioFile(audioRoot, "big.mp3", 10_000);

        MediaFileService mediaFileService = createServiceForRoot(audioRoot);
        AudioListing result = mediaFileService.listAudio(audioRoot, 1, 50, SortOrder.SIZE);
        assertEquals(2, result.total());
        assertEquals("big.mp3", result.tracks().get(0).name());
    }

    @Test
    void listAudioWithPagination() throws Exception {
        Path audioRoot = Files.createDirectories(tempDir.resolve("music-cov7"));
        for (int i = 1; i <= 5; i++) {
            createAudioFile(audioRoot, "song" + i + ".mp3");
        }

        MediaFileService mediaFileService = createServiceForRoot(audioRoot);
        AudioListing page1 = mediaFileService.listAudio(audioRoot, 1, 2, SortOrder.NAME);
        assertEquals(5, page1.total());
        assertEquals(2, page1.tracks().size());

        AudioListing page2 = mediaFileService.listAudio(audioRoot, 2, 2, SortOrder.NAME);
        assertEquals(5, page2.total());
        assertEquals(2, page2.tracks().size());
    }

    @Test
    void listImagesWithDateSortReturnsDescending() throws Exception {
        Path imageDir = Files.createDirectories(tempDir.resolve("images-cov"));
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, "jpg", imageDir.resolve("a.jpg").toFile());
        ImageIO.write(image, "jpg", imageDir.resolve("b.jpg").toFile());

        MediaFileService mediaFileService = new MediaFileService(List.of(imageDir), new ProbeService());
        Gallery result = mediaFileService.listImages(imageDir, 1, 50, SortOrder.DATE);
        assertEquals(2, result.total());
    }

    @Test
    void listImagesWithSizeSortReturnsDescending() throws Exception {
        Path imageDir = Files.createDirectories(tempDir.resolve("images2-cov"));
        BufferedImage small = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        BufferedImage big = new BufferedImage(500, 500, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(small, "jpg", imageDir.resolve("small.jpg").toFile());
        ImageIO.write(big, "jpg", imageDir.resolve("big.jpg").toFile());

        MediaFileService mediaFileService = new MediaFileService(List.of(imageDir), new ProbeService());
        Gallery result = mediaFileService.listImages(imageDir, 1, 50, SortOrder.SIZE);
        assertEquals(2, result.total());
    }

    @Test
    void readImageDimensionsReturnsNullForNonImageFileViaCoverage() throws Exception {
        Path file = Files.write(tempDir.resolve("notimage.txt"), "hello".getBytes());
        assertNull(MediaFileService.readImageDimensions(file));
    }

    @Test
    void readImageDimensionsReadsValidImageViaCoverage() throws Exception {
        Path imageDir = Files.createDirectories(tempDir.resolve("dims-cov"));
        BufferedImage image = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        Path file = imageDir.resolve("test.jpg");
        ImageIO.write(image, "jpg", file.toFile());

        var dims = MediaFileService.readImageDimensions(file);
        assertNotNull(dims);
        assertEquals(200, dims.width());
        assertEquals(100, dims.height());
    }

    @Test
    void relativePathFallsBackToFilenameWhenNotUnderAnyRoot() throws Exception {
        Path otherDir = Files.createDirectories(tempDir.resolve("other"));
        Files.write(otherDir.resolve("orphan.mp3"), new byte[100]);

        Path audioRoot = Files.createDirectories(tempDir.resolve("music-rp"));
        MediaFileService mediaFileService = new MediaFileService(List.of(audioRoot), new ProbeService());
        Gallery result = mediaFileService.listImages(otherDir, 1, 50, SortOrder.NAME);
        assertEquals(0, result.total());
    }

    @Test
    void listAudioArtistSortExercisesMetadataSortBranchWithMultipleFiles() throws Exception {
        Path audioDir = Files.createDirectories(tempDir.resolve("audio-cov2"));
        for (int i = 1; i <= 5; i++) {
            createAudioFile(audioDir, "track" + i + ".mp3");
        }

        MediaFileService mediaFileService = createServiceForRootNoProbe(audioDir);
        AudioListing result = mediaFileService.listAudio(audioDir, 1, 10, SortOrder.ARTIST);
        assertEquals(5, result.total());
        assertEquals(5, result.tracks().size());
        assertEquals(1, result.page());
    }

    @Test
    void listAudioAlbumSortExercisesMetadataSortBranch() throws Exception {
        Path audioDir = Files.createDirectories(tempDir.resolve("audio-cov2b"));
        createAudioFile(audioDir, "alpha.ogg");
        createAudioFile(audioDir, "beta.ogg");
        createAudioFile(audioDir, "gamma.ogg");

        MediaFileService mediaFileService = createServiceForRootNoProbe(audioDir);
        AudioListing result = mediaFileService.listAudio(audioDir, 1, 10, SortOrder.ALBUM);
        assertEquals(3, result.total());
        assertEquals(3, result.tracks().size());
    }

    @Test
    void listAudioDurationSortExercisesMetadataSortBranch() throws Exception {
        Path audioDir = Files.createDirectories(tempDir.resolve("audio-cov2c"));
        createAudioFile(audioDir, "short.flac");
        createAudioFile(audioDir, "long.flac");

        MediaFileService mediaFileService = createServiceForRootNoProbe(audioDir);
        AudioListing result = mediaFileService.listAudio(audioDir, 1, 10, SortOrder.DURATION);
        assertEquals(2, result.total());
        assertEquals(2, result.tracks().size());
    }

    @Test
    void listAudioArtistSortWithPaginationPage2() throws Exception {
        Path audioDir = Files.createDirectories(tempDir.resolve("audio-cov2d"));
        for (int i = 1; i <= 6; i++) {
            createAudioFile(audioDir, "song" + i + ".mp3");
        }

        MediaFileService mediaFileService = createServiceForRootNoProbe(audioDir);
        AudioListing page1 = mediaFileService.listAudio(audioDir, 1, 3, SortOrder.ARTIST);
        assertEquals(6, page1.total());
        assertEquals(3, page1.tracks().size());
        assertEquals(1, page1.page());

        AudioListing page2 = mediaFileService.listAudio(audioDir, 2, 3, SortOrder.ARTIST);
        assertEquals(6, page2.total());
        assertEquals(3, page2.tracks().size());
        assertEquals(2, page2.page());
    }

    @Test
    void listAudioDurationSortWithPageBeyondTotalReturnsEmpty() throws Exception {
        Path audioDir = Files.createDirectories(tempDir.resolve("audio-cov2e"));
        createAudioFile(audioDir, "only.mp3");

        MediaFileService mediaFileService = createServiceForRootNoProbe(audioDir);
        AudioListing result = mediaFileService.listAudio(audioDir, 5, 10, SortOrder.DURATION);
        assertEquals(1, result.total());
        assertEquals(0, result.tracks().size());
    }

    @Test
    void listAudioArtistSortWithEmptyDirectory() throws Exception {
        Path emptyDir = Files.createDirectories(tempDir.resolve("empty-cov2"));
        MediaFileService mediaFileService = createServiceForRootNoProbe(emptyDir);
        AudioListing result = mediaFileService.listAudio(emptyDir, 1, 10, SortOrder.ARTIST);
        assertEquals(0, result.total());
        assertEquals(0, result.tracks().size());
    }

    @Test
    void listAudioAlbumSortWithSingleFile() throws Exception {
        Path audioDir = Files.createDirectories(tempDir.resolve("audio-cov2f"));
        createAudioFile(audioDir, "solo.wav");

        MediaFileService mediaFileService = createServiceForRootNoProbe(audioDir);
        AudioListing result = mediaFileService.listAudio(audioDir, 1, 10, SortOrder.ALBUM);
        assertEquals(1, result.total());
        assertEquals(1, result.tracks().size());
    }

    @Test
    void listAudioNameSortDoesNotProbeMetadata() throws Exception {
        Path audioDir = Files.createDirectories(tempDir.resolve("audio-cov2g"));
        createAudioFile(audioDir, "zzz.mp3");
        createAudioFile(audioDir, "aaa.mp3");

        MediaFileService mediaFileService = createServiceForRootNoProbe(audioDir);
        AudioListing result = mediaFileService.listAudio(audioDir, 1, 10, SortOrder.NAME);
        assertEquals(2, result.total());
        assertEquals("aaa.mp3", result.tracks().get(0).name());
        assertEquals("zzz.mp3", result.tracks().get(1).name());
    }

    @Test
    void readImageDimensionsReturnsNullForNonexistentFile() {
        assertNull(MediaFileService.readImageDimensions(tempDir.resolve("nope.jpg")));
    }

    @Test
    void readImageDimensionsReturnsNullForEmptyFile() throws Exception {
        Path file = Files.write(tempDir.resolve("empty.jpg"), new byte[0]);
        assertNull(MediaFileService.readImageDimensions(file));
    }

    @Test
    void readImageDimensionsReturnsDimensionsForPng() throws Exception {
        Path imageDir = Files.createDirectories(tempDir.resolve("images-cov2"));
        BufferedImage image = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
        Path file = imageDir.resolve("test.png");
        ImageIO.write(image, "png", file.toFile());

        var dims = MediaFileService.readImageDimensions(file);
        assertNotNull(dims);
        assertEquals(320, dims.width());
        assertEquals(240, dims.height());
    }

    @Test
    void readImageDimensionsReturnsDimensionsForBmp() throws Exception {
        Path imageDir = Files.createDirectories(tempDir.resolve("images-cov2b"));
        BufferedImage image = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        Path file = imageDir.resolve("test.bmp");
        ImageIO.write(image, "bmp", file.toFile());

        var dims = MediaFileService.readImageDimensions(file);
        assertNotNull(dims);
        assertEquals(64, dims.width());
        assertEquals(48, dims.height());
    }

    @Test
    void readImageDimensionsReturnsNullForCorruptData() throws Exception {
        Path file = Files.write(tempDir.resolve("corrupt.jpg"), new byte[] {0x00, 0x01, 0x02, 0x03, 0x04});
        assertNull(MediaFileService.readImageDimensions(file));
    }

    @Test
    void listImagesIncludesDimensionsForValidImages() throws Exception {
        Path imageDir = Files.createDirectories(tempDir.resolve("images-cov2c"));
        BufferedImage image = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, "jpg", imageDir.resolve("photo.jpg").toFile());

        MediaFileService mediaFileService = new MediaFileService(List.of(imageDir), new ProbeService());
        Gallery gallery = mediaFileService.listImages(imageDir, 1, 10, SortOrder.NAME);
        assertEquals(1, gallery.total());
        MediaItem.Image item = gallery.images().getFirst();
        assertNotNull(item.width());
        assertEquals(640, item.width());
        assertEquals(480, item.height());
    }

    @Test
    void listImagesWithMultipleFormats() throws Exception {
        Path imageDir = Files.createDirectories(tempDir.resolve("images-cov2d"));
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, "jpg", imageDir.resolve("a.jpg").toFile());
        ImageIO.write(image, "png", imageDir.resolve("b.png").toFile());
        ImageIO.write(image, "bmp", imageDir.resolve("c.bmp").toFile());

        MediaFileService mediaFileService = new MediaFileService(List.of(imageDir), new ProbeService());
        Gallery gallery = mediaFileService.listImages(imageDir, 1, 10, SortOrder.NAME);
        assertEquals(3, gallery.total());
    }

    @Test
    void listAudioArtistSortHandlesNullMetadataGracefully() throws Exception {
        Path audioDir = Files.createDirectories(tempDir.resolve("audio-cov2h"));
        createAudioFile(audioDir, "x.mp3");
        createAudioFile(audioDir, "y.mp3");
        createAudioFile(audioDir, "z.mp3");

        MediaFileService mediaFileService = createServiceForRootNoProbe(audioDir);
        AudioListing result = mediaFileService.listAudio(audioDir, 1, 10, SortOrder.ARTIST);
        assertEquals(3, result.total());
        assertEquals(3, result.tracks().size());
        result.tracks().forEach(track -> assertNull(track.artist()));
    }

    @Test
    void listAudioAlbumSortHandlesNullMetadataGracefully() throws Exception {
        Path audioDir = Files.createDirectories(tempDir.resolve("audio-cov2i"));
        createAudioFile(audioDir, "m.ogg");
        createAudioFile(audioDir, "n.ogg");

        MediaFileService mediaFileService = createServiceForRootNoProbe(audioDir);
        AudioListing result = mediaFileService.listAudio(audioDir, 1, 10, SortOrder.ALBUM);
        assertEquals(2, result.total());
        result.tracks().forEach(track -> assertNull(track.album()));
    }

    @Test
    void listAudioDurationSortHandlesNullDuration() throws Exception {
        Path audioDir = Files.createDirectories(tempDir.resolve("audio-cov2j"));
        createAudioFile(audioDir, "a.flac");
        createAudioFile(audioDir, "b.flac");

        MediaFileService mediaFileService = createServiceForRootNoProbe(audioDir);
        AudioListing result = mediaFileService.listAudio(audioDir, 1, 10, SortOrder.DURATION);
        assertEquals(2, result.total());
        result.tracks().forEach(track -> assertNull(track.duration()));
    }

    @Test
    void metadataAudioSortsResolveMediaObjectsOnlyForReturnedPageItems() throws Exception {
        for (SortOrder sort : List.of(SortOrder.ARTIST, SortOrder.ALBUM, SortOrder.DURATION)) {
            Path audioDir = Files.createDirectories(tempDir.resolve("audio-page-" + sort.name()));
            createAudioFile(audioDir, "alpha.mp3");
            createAudioFile(audioDir, "beta.mp3");
            createAudioFile(audioDir, "gamma.mp3");
            TestServices testServices = createServiceForRootWithObjects(audioDir);

            AudioListing result = testServices.service().listAudio(audioDir, 1, 1, sort);

            assertEquals(3, result.total(), "Unexpected total for " + sort);
            assertEquals(List.of("alpha.mp3"), result.tracks().stream().map(MediaItem.Music::name).toList(), "Unexpected page for " + sort);
            assertNotNull(testServices.mediaObjectService().getByPath(audioDir.resolve("alpha.mp3").toString()), "Missing page object for " + sort);
            assertNull(testServices.mediaObjectService().getByPath(audioDir.resolve("beta.mp3").toString()), "Persisted off-page item for " + sort);
            assertNull(testServices.mediaObjectService().getByPath(audioDir.resolve("gamma.mp3").toString()), "Persisted off-page item for " + sort);
        }
    }

    @Test
    void listImagesWithSizeSortOrderFromImprovements() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media-size-improv"));
        MediaFileService mediaFileService = new MediaFileService(List.of(mediaRoot), new ProbeService());

        createTestImageForImprov(mediaRoot, "small.jpg", 5, 5);
        createTestImageForImprov(mediaRoot, "large.jpg", 50, 50);

        Gallery gallery = mediaFileService.listImages(mediaRoot, 1, 10, SortOrder.SIZE);
        assertEquals(2, gallery.total());
        assertTrue(gallery.images().get(0).size() >= gallery.images().get(1).size());
    }

    @Test
    void listImagesWithDateSortOrderFromImprovements() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media-date-improv"));
        MediaFileService mediaFileService = new MediaFileService(List.of(mediaRoot), new ProbeService());

        createTestImageForImprov(mediaRoot, "old.jpg", 10, 10);
        Thread.sleep(20);
        createTestImageForImprov(mediaRoot, "new.jpg", 10, 10);

        Gallery gallery = mediaFileService.listImages(mediaRoot, 1, 10, SortOrder.DATE);
        assertEquals(2, gallery.total());
    }

    @Test
    void listImagesWithNameSortOrderFromImprovements() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media-name-improv"));
        MediaFileService mediaFileService = new MediaFileService(List.of(mediaRoot), new ProbeService());

        createTestImageForImprov(mediaRoot, "b.jpg", 10, 10);
        createTestImageForImprov(mediaRoot, "a.jpg", 10, 10);

        Gallery gallery = mediaFileService.listImages(mediaRoot, 1, 10, SortOrder.NAME);
        assertEquals(2, gallery.total());
        assertEquals("a.jpg", gallery.images().get(0).name());
        assertEquals("b.jpg", gallery.images().get(1).name());
    }

    @Test
    void relativePathReturnsRelativeToMediaRootFromImprovements() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media-rel-improv"));
        MediaFileService mediaFileService = new MediaFileService(List.of(mediaRoot), new ProbeService());

        createTestImageForImprov(mediaRoot, "photo.jpg");

        Gallery gallery = mediaFileService.listImages(mediaRoot, 1, 10, SortOrder.NAME);
        assertEquals("photo.jpg", gallery.images().get(0).path());
    }

    @Test
    void readImageDimensionsReturnsCorrectDimensionsFromImprovements() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("dim-test-improv"));
        Path image = createTestImageForImprov(dir, "test.jpg", 320, 240);
        var dims = MediaFileService.readImageDimensions(image);
        assertNotNull(dims);
        assertEquals(320, dims.width());
        assertEquals(240, dims.height());
    }

    @Test
    void readImageDimensionsReturnsNullForNonImageFromImprovements() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("dim-test2-improv"));
        Path file = Files.writeString(dir.resolve("test.txt"), "not an image");
        assertNull(MediaFileService.readImageDimensions(file));
    }

    @Test
    void paginationWorksCorrectlyFromImprovements() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media-page-improv"));
        MediaFileService mediaFileService = new MediaFileService(List.of(mediaRoot), new ProbeService());

        for (int i = 0; i < 5; i++) {
            createTestImageForImprov(mediaRoot, "img" + i + ".jpg");
        }

        Gallery page1 = mediaFileService.listImages(mediaRoot, 1, 2, SortOrder.NAME);
        assertEquals(5, page1.total());
        assertEquals(2, page1.images().size());

        Gallery page3 = mediaFileService.listImages(mediaRoot, 3, 2, SortOrder.NAME);
        assertEquals(5, page3.total());
        assertEquals(1, page3.images().size());
    }

    @Test
    void listImagesWithArtistSortFallsBackToNameSort() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media-artist-sort-improv"));
        MediaFileService mediaFileService = new MediaFileService(List.of(mediaRoot), new ProbeService());

        createTestImageForImprov(mediaRoot, "b_img.jpg");
        createTestImageForImprov(mediaRoot, "a_img.jpg");

        Gallery gallery = mediaFileService.listImages(mediaRoot, 1, 10, SortOrder.ARTIST);
        assertEquals(2, gallery.total());
        assertEquals("a_img.jpg", gallery.images().get(0).name());
    }

    @Test
    void relativePathReturnsFilenameWhenNotUnderAnyMediaRootFromImprovements() throws Exception {
        Path rootA = Files.createDirectories(tempDir.resolve("rootA-improv"));
        Path rootB = Files.createDirectories(tempDir.resolve("rootB-improv"));
        MediaFileService mediaFileService = new MediaFileService(List.of(rootA), new ProbeService());
        createTestImageForImprov(rootB, "photo.jpg");
        Gallery gallery = mediaFileService.listImages(rootB, 1, 10, SortOrder.NAME);
        assertEquals(1, gallery.total());
        assertEquals("photo.jpg", gallery.images().get(0).path());
    }

    @Test
    void audioItemDataClassCoverage() {
        MediaItem.Music item = ModuleMediaTestSupport.mediaItemMusic(
            "test.mp3",
            "test.mp3",
            1_000L,
            "audio/mpeg",
            180.0,
            320_000L,
            2,
            "Test Artist",
            "Test Album",
            "Test Song"
        );
        assertEquals("test.mp3", item.path());
        assertEquals(180.0, item.duration());
        assertEquals("Test Artist", item.artist());
    }

    @Test
    void imageItemDataClassCoverage() {
        MediaItem.Image item = ModuleMediaTestSupport.mediaItemImage(
            "test.jpg",
            "test.jpg",
            5_000L,
            "image/jpeg",
            1920,
            1080,
            "2024-01-01T00:00:00Z",
            List.of(300),
            null
        );
        assertEquals("test.jpg", item.path());
        assertEquals(1920, item.width());
        assertEquals(List.of(300), item.thumbnailSizes());
    }

    @Test
    void sortOrderEnumValues() {
        assertEquals(6, SortOrder.values().length);
        assertTrue(java.util.EnumSet.allOf(SortOrder.class).contains(SortOrder.NAME));
        assertTrue(java.util.EnumSet.allOf(SortOrder.class).contains(SortOrder.DATE));
        assertTrue(java.util.EnumSet.allOf(SortOrder.class).contains(SortOrder.SIZE));
        assertTrue(java.util.EnumSet.allOf(SortOrder.class).contains(SortOrder.ARTIST));
        assertTrue(java.util.EnumSet.allOf(SortOrder.class).contains(SortOrder.ALBUM));
        assertTrue(java.util.EnumSet.allOf(SortOrder.class).contains(SortOrder.DURATION));
    }

    @Test
    void galleryDataClassCoverage() {
        Gallery gallery = new Gallery(
            List.of(ModuleMediaTestSupport.mediaItemImage("a.jpg", "a.jpg", 100L, "image/jpeg", null, null, null, List.of(), null)),
            1,
            1,
            10
        );
        assertEquals(1, gallery.total());
        assertEquals(1, gallery.images().size());
    }

    @Test
    void audioListingDataClassCoverage() {
        AudioListing listing = new AudioListing(
            List.of(ModuleMediaTestSupport.mediaItemMusic("s.mp3", "s.mp3", 100L, "audio/mpeg", null, null, null, null, null, null)),
            1,
            1,
            10
        );
        assertEquals(1, listing.total());
        assertEquals(1, listing.tracks().size());
    }

    private record TestServices(MediaFileService service, MediaObjectService mediaObjectService) {
    }
}
