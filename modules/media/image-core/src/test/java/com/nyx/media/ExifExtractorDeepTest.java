package com.nyx.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExifExtractorDeepTest {
    private Path tempDir;
    private final ExifExtractor extractor = new ExifExtractor();

    @BeforeEach
    void setup() throws Exception {
        tempDir = Files.createTempDirectory("nyx-exif-deep-test");
    }

    @AfterEach
    void teardown() throws Exception {
        ImageCoreTestSupport.deleteRecursively(tempDir);
    }

    private Path createImage(String name, String format, Color color, int width, int height) throws Exception {
        Path file = tempDir.resolve(name);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(image, format, file.toFile());
        return file;
    }

    private Path createJpegImage(String name, int width, int height) throws Exception {
        return createImage(name, "jpg", Color.BLUE, width, height);
    }

    private Path createJpegImage(String name) throws Exception {
        return createJpegImage(name, 100, 100);
    }

    private Path createPngImage(String name, int width, int height) throws Exception {
        return createImage(name, "png", Color.RED, width, height);
    }

    private Path createPngImage(String name) throws Exception {
        return createPngImage(name, 100, 100);
    }

    @Test
    void extractExifReturnsMetadataForJpeg() throws Exception {
        assertNotNull(extractor.extractExif(createJpegImage("test.jpg")));
    }

    @Test
    void extractExifReturnsMetadataForPng() throws Exception {
        assertNotNull(extractor.extractExif(createPngImage("test.png")));
    }

    @Test
    void extractExifReturnsEmptyMapForNonImageFile() throws Exception {
        Path file = tempDir.resolve("data.bin");
        Files.writeString(file, "not an image", StandardCharsets.UTF_8);

        assertTrue(extractor.extractExif(file).isEmpty());
    }

    @Test
    void extractExifReturnsEmptyMapForNonExistentFile() {
        assertTrue(extractor.extractExif(tempDir.resolve("nonexistent.jpg")).isEmpty());
    }

    @Test
    void extractStructuredReturnsStructuredDataForJpeg() throws Exception {
        assertNotNull(extractor.extractStructured(createJpegImage("structured.jpg")));
    }

    @Test
    void extractStructuredReturnsEmptyMapForNonImageFile() throws Exception {
        Path file = tempDir.resolve("notimg.txt");
        Files.writeString(file, "text content", StandardCharsets.UTF_8);

        assertTrue(extractor.extractStructured(file).isEmpty());
    }

    @Test
    void extractStructuredReturnsEmptyMapForNonExistentFile() {
        assertTrue(extractor.extractStructured(tempDir.resolve("missing.jpg")).isEmpty());
    }

    @Test
    void stripSensitiveExifReEncodesJpegWithoutExif() throws Exception {
        byte[] stripped = extractor.stripSensitiveExif(createJpegImage("strip.jpg", 200, 200));

        assertTrue(stripped.length > 0);
        assertEquals((byte) 0xFF, stripped[0]);
        assertEquals((byte) 0xD8, stripped[1]);
    }

    @Test
    void stripSensitiveExifReEncodesPng() throws Exception {
        byte[] stripped = extractor.stripSensitiveExif(createPngImage("strip.png"));

        assertTrue(stripped.length > 0);
        assertEquals((byte) 0x89, stripped[0]);
        assertEquals((byte) 0x50, stripped[1]);
    }

    @Test
    void stripSensitiveExifHandlesBmpFormat() throws Exception {
        Path file = tempDir.resolve("image.bmp");
        BufferedImage image = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, "bmp", file.toFile());

        assertTrue(extractor.stripSensitiveExif(file).length > 0);
    }

    @Test
    void stripSensitiveExifHandlesGifFormat() throws Exception {
        Path file = tempDir.resolve("image.gif");
        BufferedImage image = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, "gif", file.toFile());

        assertTrue(extractor.stripSensitiveExif(file).length > 0);
    }

    @Test
    void stripSensitiveExifFallsBackToRawBytesForUnknownFormat() throws Exception {
        Path file = tempDir.resolve("data.xyz");
        String content = "unknown format data";
        Files.writeString(file, content, StandardCharsets.UTF_8);

        assertEquals(content, new String(extractor.stripSensitiveExif(file), StandardCharsets.UTF_8));
    }

    @Test
    void isSensitiveTagDetectsAllSensitiveTagNames() {
        assertTrue(ExifExtractor.isSensitiveTag("Exif IFD0", "Camera Serial Number"));
        assertTrue(ExifExtractor.isSensitiveTag("Exif IFD0", "Body Serial Number"));
        assertTrue(ExifExtractor.isSensitiveTag("Exif IFD0", "Lens Serial Number"));
        assertTrue(ExifExtractor.isSensitiveTag("Exif IFD0", "Owner Name"));
        assertTrue(ExifExtractor.isSensitiveTag("Exif IFD0", "Camera Owner Name"));
        assertTrue(ExifExtractor.isSensitiveTag("Exif IFD0", "Artist"));
        assertTrue(ExifExtractor.isSensitiveTag("Exif IFD0", "Software"));
    }

    @Test
    void isSensitiveTagDetectsGpsDirectory() {
        assertTrue(ExifExtractor.isSensitiveTag("GPS", "GPS Latitude"));
        assertTrue(ExifExtractor.isSensitiveTag("GPS Info", "GPS Longitude"));
    }

    @Test
    void isSensitiveTagReturnsFalseForSafeTags() {
        assertFalse(ExifExtractor.isSensitiveTag("Exif SubIFD", "Exposure Time"));
        assertFalse(ExifExtractor.isSensitiveTag("JPEG", "Image Height"));
        assertFalse(ExifExtractor.isSensitiveTag("Exif IFD0", "Make"));
    }
}
