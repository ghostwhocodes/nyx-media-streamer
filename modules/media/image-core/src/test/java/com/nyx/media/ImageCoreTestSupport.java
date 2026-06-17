package com.nyx.media;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import javax.imageio.ImageIO;

final class ImageCoreTestSupport {
    private ImageCoreTestSupport() {}

    static void deleteRecursively(Path root) throws IOException {
        if (root == null || Files.notExists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            });
        } catch (RuntimeException exception) {
            if (exception.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw exception;
        }
    }

    static Path createImage(Path directory, String name, int width, int height) throws IOException {
        return createImage(directory, name, width, height, Color.BLACK);
    }

    static Path createImage(Path directory, String name, int width, int height, Color color) throws IOException {
        Path file = directory.resolve(name);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        String format = extension(name);
        ImageIO.write(image, format, file.toFile());
        return file;
    }

    static Path createVideoPlaceholder(Path directory, String name, int size) throws IOException {
        Path file = directory.resolve(name);
        Files.write(file, new byte[size]);
        return file;
    }

    static boolean isFfmpegAvailable() {
        try {
            Process process = new ProcessBuilder("ffmpeg", "-version")
                .redirectErrorStream(true)
                .start();
            return process.waitFor() == 0;
        } catch (Exception exception) {
            return false;
        }
    }

    private static String extension(String name) {
        int dotIndex = name.lastIndexOf('.');
        return dotIndex >= 0 ? name.substring(dotIndex + 1) : "png";
    }
}
