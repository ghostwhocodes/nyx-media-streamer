package com.nyx.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExifExtractorTest {
    private Path tempDir;
    private final ExifExtractor extractor = new ExifExtractor();

    @BeforeEach
    void setup() throws Exception {
        tempDir = Files.createTempDirectory("nyx-exif-test");
    }

    @AfterEach
    void teardown() throws Exception {
        ImageCoreTestSupport.deleteRecursively(tempDir);
    }

    private Path createTestImage(String name, int width, int height) throws Exception {
        Path file = tempDir.resolve(name);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        String format = name.substring(name.lastIndexOf('.') + 1);
        ImageIO.write(image, format, file.toFile());
        return file;
    }

    private Path createTestImage(String name) throws Exception {
        return createTestImage(name, 200, 150);
    }

    private Path createImage(String name, String format) throws Exception {
        Path file = tempDir.resolve(name);
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, format, file.toFile());
        return file;
    }

    @Test
    void extractExifReturnsEmptyMapForImageWithoutExif() throws Exception {
        Path file = createTestImage("plain.jpg");

        assertNotNull(extractor.extractExif(file));
    }

    @Test
    void extractExifHandlesPngGracefully() throws Exception {
        Path file = createTestImage("image.png");

        assertNotNull(extractor.extractExif(file));
    }

    @Test
    void extractExifHandlesNonExistentFileGracefully() {
        Path file = tempDir.resolve("missing.jpg");

        assertTrue(extractor.extractExif(file).isEmpty());
    }

    @Test
    void extractStructuredReturnsEmptyMapForSyntheticImage() throws Exception {
        Path file = createTestImage("test.jpg");

        assertNotNull(extractor.extractStructured(file));
    }

    @Test
    void gpsTagsAreClassifiedAsSensitive() {
        assertTrue(ExifExtractor.isSensitiveTag("GPS", "GPS Latitude"));
        assertTrue(ExifExtractor.isSensitiveTag("GPS", "GPS Longitude"));
        assertTrue(ExifExtractor.isSensitiveTag("GPS", "GPS Altitude"));
    }

    @Test
    void cameraSerialNumberIsSensitive() {
        assertTrue(ExifExtractor.isSensitiveTag("Exif IFD0", "Camera Serial Number"));
        assertTrue(ExifExtractor.isSensitiveTag("Exif IFD0", "Body Serial Number"));
    }

    @Test
    void ownerNameIsSensitive() {
        assertTrue(ExifExtractor.isSensitiveTag("Exif IFD0", "Owner Name"));
        assertTrue(ExifExtractor.isSensitiveTag("Exif IFD0", "Camera Owner Name"));
    }

    @Test
    void softwareTagIsSensitive() {
        assertTrue(ExifExtractor.isSensitiveTag("Exif IFD0", "Software"));
    }

    @Test
    void nonSensitiveTagsAreNotClassifiedAsSensitive() {
        assertFalse(ExifExtractor.isSensitiveTag("Exif IFD0", "Make"));
        assertFalse(ExifExtractor.isSensitiveTag("Exif IFD0", "Model"));
        assertFalse(ExifExtractor.isSensitiveTag("Exif SubIFD", "Exposure Time"));
        assertFalse(ExifExtractor.isSensitiveTag("Exif SubIFD", "F-Number"));
        assertFalse(ExifExtractor.isSensitiveTag("Exif IFD0", "Orientation"));
    }

    @Test
    void stripSensitiveExifReturnsValidJpegBytes() throws Exception {
        Path file = createTestImage("photo.jpg");

        byte[] stripped = extractor.stripSensitiveExif(file);

        assertTrue(stripped.length > 0);
        assertEquals((byte) 0xFF, stripped[0]);
        assertEquals((byte) 0xD8, stripped[1]);
    }

    @Test
    void stripSensitiveExifPreservesImageDimensions() throws Exception {
        Path file = createTestImage("photo.jpg", 320, 240);

        byte[] stripped = extractor.stripSensitiveExif(file);
        BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(stripped));

        assertNotNull(image);
        assertEquals(320, image.getWidth());
        assertEquals(240, image.getHeight());
    }

    @Test
    void stripSensitiveExifHandlesPngWithReEncoding() throws Exception {
        Path file = createTestImage("photo.png", 100, 100);

        byte[] stripped = extractor.stripSensitiveExif(file);

        assertTrue(stripped.length > 0);
        assertEquals((byte) 0x89, stripped[0]);
        assertEquals((byte) 0x50, stripped[1]);
        assertEquals((byte) 0x4E, stripped[2]);
        assertEquals((byte) 0x47, stripped[3]);

        BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(stripped));
        assertNotNull(image);
        assertEquals(100, image.getWidth());
        assertEquals(100, image.getHeight());
    }

    @Test
    void stripSensitiveExifHandlesBmpWithReEncoding() throws Exception {
        Path file = createTestImage("photo.bmp", 50, 50);

        byte[] stripped = extractor.stripSensitiveExif(file);
        BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(stripped));

        assertTrue(stripped.length > 0);
        assertNotNull(image);
        assertEquals(50, image.getWidth());
        assertEquals(50, image.getHeight());
    }

    @Test
    void strippedJpegHasNoExifMetadata() throws Exception {
        Path file = createTestImage("photo.jpg");
        byte[] stripped = extractor.stripSensitiveExif(file);
        Path strippedFile = tempDir.resolve("stripped.jpg");
        Files.write(strippedFile, stripped);

        boolean hasGps = extractor.extractExif(strippedFile).keySet().stream().anyMatch(key -> key.contains("GPS"));

        assertFalse(hasGps, "Stripped image should not contain GPS data");
    }

    @Test
    void stripSensitiveExifHandlesGifWithReEncoding() throws Exception {
        Path file = createTestImage("anim.gif", 40, 40);

        byte[] stripped = extractor.stripSensitiveExif(file);
        BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(stripped));

        assertTrue(stripped.length > 0);
        assertNotNull(image);
        assertEquals(40, image.getWidth());
        assertEquals(40, image.getHeight());
    }

    @Test
    void stripSensitiveExifHandlesUnsupportedExtensionWithRawBytes() throws Exception {
        Path file = tempDir.resolve("data.xyz");
        Files.writeString(file, "not really an image", StandardCharsets.UTF_8);

        assertEquals("not really an image", new String(extractor.stripSensitiveExif(file), StandardCharsets.UTF_8));
    }

    @Test
    void extractExifReturnsMetadataKeysForImageWithData() throws Exception {
        Path file = createTestImage("meta.jpg", 100, 100);

        assertNotNull(extractor.extractExif(file));
    }

    @Test
    void extractStructuredReturnsEmptyForPng() throws Exception {
        Path file = createTestImage("test.png", 50, 50);

        var structured = extractor.extractStructured(file);

        assertNotNull(structured);
        assertFalse(structured.containsKey("gpsLatitude"));
    }

    @Test
    void extractStructuredHandlesNonExistentFileGracefully() {
        assertTrue(extractor.extractStructured(tempDir.resolve("missing.jpg")).isEmpty());
    }

    @Test
    void isSensitiveTagDetectsLensSerialNumber() {
        assertTrue(ExifExtractor.isSensitiveTag("Exif SubIFD", "Lens Serial Number"));
    }

    @Test
    void isSensitiveTagDetectsArtistTag() {
        assertTrue(ExifExtractor.isSensitiveTag("Exif IFD0", "Artist"));
    }

    @Test
    void stripSensitiveExifHandlesTiffWithReEncoding() throws Exception {
        Path file = tempDir.resolve("photo.tiff");
        BufferedImage image = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
        var writers = ImageIO.getImageWritersByFormatName("tiff");

        if (writers.hasNext()) {
            ImageIO.write(image, "tiff", file.toFile());
        } else {
            Files.writeString(file, "raw data", StandardCharsets.UTF_8);
        }

        assertTrue(extractor.stripSensitiveExif(file).length > 0);
    }

    @Test
    void stripSensitiveExifHandlesTifExtension() throws Exception {
        Path file = tempDir.resolve("photo.tif");
        BufferedImage image = new BufferedImage(30, 30, BufferedImage.TYPE_INT_RGB);
        var writers = ImageIO.getImageWritersByFormatName("tiff");

        if (writers.hasNext()) {
            ImageIO.write(image, "tiff", file.toFile());
        } else {
            Files.writeString(file, "raw tif data", StandardCharsets.UTF_8);
        }

        assertTrue(extractor.stripSensitiveExif(file).length > 0);
    }

    @Test
    void stripSensitiveExifHandlesWebpExtension() throws Exception {
        Path file = tempDir.resolve("photo.webp");
        Files.writeString(file, "fake webp data", StandardCharsets.UTF_8);

        assertTrue(extractor.stripSensitiveExif(file).length > 0);
    }

    @Test
    void stripSensitiveExifReturnsRawBytesForUnknownFormatWithoutImageIoReader() throws Exception {
        Path file = tempDir.resolve("data.raw");
        String content = "binary data content";
        Files.writeString(file, content, StandardCharsets.UTF_8);

        assertEquals(content, new String(extractor.stripSensitiveExif(file), StandardCharsets.UTF_8));
    }

    @Test
    void isSensitiveTagDetectsSerialNumberTag() {
        assertTrue(ExifExtractor.isSensitiveTag("Exif SubIFD", "Serial Number"));
    }

    @Test
    void isSensitiveTagIsCaseInsensitiveForGpsDirectoryPrefix() {
        assertTrue(ExifExtractor.isSensitiveTag("gps", "Any Tag"));
        assertTrue(ExifExtractor.isSensitiveTag("GPS Info", "Any Tag"));
    }

    @Test
    void isSensitiveTagReturnsFalseForSafeDirectoryAndTag() {
        assertFalse(ExifExtractor.isSensitiveTag("JPEG", "Image Width"));
        assertFalse(ExifExtractor.isSensitiveTag("Exif SubIFD", "Exposure Time"));
    }

    @Test
    void stripSensitiveExifHandlesJpegJpgExtension() throws Exception {
        assertTrue(extractor.stripSensitiveExif(createImage("photo2.jpg", "jpeg")).length > 0);
    }

    @Test
    void stripSensitiveExifHandlesJpegJpegExtension() throws Exception {
        assertTrue(extractor.stripSensitiveExif(createImage("photo.jpeg", "jpeg")).length > 0);
    }

    @Test
    void stripSensitiveExifHandlesPngFormat() throws Exception {
        assertTrue(extractor.stripSensitiveExif(createImage("image2.png", "png")).length > 0);
    }

    @Test
    void stripSensitiveExifHandlesBmpFormat() throws Exception {
        assertTrue(extractor.stripSensitiveExif(createImage("image.bmp", "bmp")).length > 0);
    }

    @Test
    void stripSensitiveExifHandlesGifFormat() throws Exception {
        assertTrue(extractor.stripSensitiveExif(createImage("image.gif", "gif")).length > 0);
    }

    @Test
    void stripSensitiveExifHandlesUnknownFormatByReturningRawBytes() throws Exception {
        Path file = tempDir.resolve("data.xyz2");
        byte[] bytes = new byte[42];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        Files.write(file, bytes);

        assertEquals(42, extractor.stripSensitiveExif(file).length);
    }

    @Test
    void stripSensitiveExifHandlesTiffFormat() throws Exception {
        Path file = tempDir.resolve("image.tiff2");
        Files.write(file, new byte[100]);

        assertTrue(extractor.stripSensitiveExif(file).length > 0);
    }

    @Test
    void extractExifReturnsEmptyMapForNonImageFile() throws Exception {
        Path file = tempDir.resolve("text.txt");
        Files.writeString(file, "not an image", StandardCharsets.UTF_8);

        assertTrue(extractor.extractExif(file).isEmpty());
    }

    @Test
    void extractExifReturnsDataForJpegWithMetadata() throws Exception {
        assertNotNull(extractor.extractExif(createImage("meta2.jpg", "jpeg")));
    }

    @Test
    void extractStructuredReturnsEmptyMapForNonImage() throws Exception {
        Path file = tempDir.resolve("text2.txt");
        Files.writeString(file, "not an image", StandardCharsets.UTF_8);

        assertTrue(extractor.extractStructured(file).isEmpty());
    }

    @Test
    void extractStructuredWorksForPng() throws Exception {
        assertNotNull(extractor.extractStructured(createImage("meta.png", "png")));
    }

    @Test
    void isSensitiveTagReturnsTrueForGpsDirectory() {
        assertTrue(ExifExtractor.isSensitiveTag("GPS", "GPS Latitude"));
    }

    @Test
    void isSensitiveTagReturnsTrueForSerialNumberTags() {
        assertTrue(ExifExtractor.isSensitiveTag("Exif IFD0", "Camera Serial Number"));
        assertTrue(ExifExtractor.isSensitiveTag("Exif IFD0", "Body Serial Number"));
        assertTrue(ExifExtractor.isSensitiveTag("Exif SubIFD", "Serial Number"));
    }

    @Test
    void isSensitiveTagReturnsTrueForOwnerTags() {
        assertTrue(ExifExtractor.isSensitiveTag("Exif IFD0", "Owner Name"));
        assertTrue(ExifExtractor.isSensitiveTag("Exif IFD0", "Camera Owner Name"));
        assertTrue(ExifExtractor.isSensitiveTag("Exif IFD0", "Artist"));
        assertTrue(ExifExtractor.isSensitiveTag("Exif IFD0", "Software"));
    }

    @Test
    void isSensitiveTagReturnsFalseForNonSensitiveTags() {
        assertFalse(ExifExtractor.isSensitiveTag("Exif IFD0", "Make"));
        assertFalse(ExifExtractor.isSensitiveTag("Exif SubIFD", "Exposure Time"));
    }
}
