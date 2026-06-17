package com.nyx.media;

import java.nio.file.Path;
import java.util.Locale;

final class LibraryItemText {
    private LibraryItemText() {
    }

    static String titleFromPath(String path) {
        Path filePath = Path.of(path);
        String fileName = fileName(filePath);
        return cleanTitle(stripExtension(fileName == null ? path : fileName));
    }

    static String cleanTitle(String raw) {
        return raw.replaceAll("[._]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    static String normalizedIdentity(String raw) {
        return cleanTitle(raw)
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
    }

    static String fileStem(Path path) {
        String fileName = fileName(path);
        if (fileName == null) {
            return null;
        }
        return stripExtension(fileName);
    }

    static String stripExtension(String value) {
        int index = value.lastIndexOf('.');
        return index >= 0 ? value.substring(0, index) : value;
    }

    static String fileName(Path path) {
        return path == null || path.getFileName() == null ? null : path.getFileName().toString();
    }

    static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    static String nonBlank(String value, String fallback) {
        return notBlank(value) ? value.trim() : fallback;
    }
}
