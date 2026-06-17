package com.nyx.common;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class PathSecurity {
    private final List<Path> resolvedRoots;

    public PathSecurity(List<Path> mediaRoots) {
        List<Path> roots = new ArrayList<>();
        for (Path root : mediaRoots) {
            try {
                roots.add(root.toRealPath());
            } catch (Exception ignored) {
                // Missing roots are excluded and reject all paths beneath them.
            }
        }
        this.resolvedRoots = List.copyOf(roots);
    }

    public Path validate(String requestPath) {
        if (requestPath == null || requestPath.isBlank()) {
            throw new PathNotAllowedException("<empty>");
        }
        if (requestPath.indexOf('\0') >= 0) {
            throw new PathNotAllowedException("<null bytes>");
        }

        Path path;
        try {
            path = Path.of(requestPath);
        } catch (Exception ignored) {
            throw new PathNotAllowedException(requestPath);
        }

        if (!path.isAbsolute()) {
            throw new PathNotAllowedException(requestPath);
        }

        Path canonical;
        try {
            canonical = path.toRealPath();
        } catch (Exception ignored) {
            throw new PathNotFoundException(requestPath);
        }

        for (Path root : resolvedRoots) {
            if (canonical.startsWith(root)) {
                return canonical;
            }
        }
        throw new PathNotAllowedException(requestPath);
    }

    public Path validateDirectory(String requestPath) {
        Path path = validate(requestPath);
        if (!Files.isDirectory(path)) {
            throw new PathNotDirectoryException(requestPath);
        }
        return path;
    }
}
