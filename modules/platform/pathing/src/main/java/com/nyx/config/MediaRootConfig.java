package com.nyx.config;

import java.nio.file.Path;

public record MediaRootConfig(Path path, String filesystem, String displayName) {
    public MediaRootConfig {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        filesystem = filesystem == null ? "local" : filesystem;
        displayName = displayName == null ? "" : displayName;
    }

    public MediaRootConfig(Path path) {
        this(path, "local", "");
    }

    public MediaRootConfig(Path path, String filesystem) {
        this(path, filesystem, "");
    }

    public Path getPath() {
        return path;
    }

    public String getFilesystem() {
        return filesystem;
    }

    public String getDisplayName() {
        return displayName;
    }
}
