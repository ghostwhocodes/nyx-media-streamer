package com.nyx.common;

import com.nyx.config.MediaRootConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class VirtualPathResolver {
    private final List<VirtualRoot> roots;

    public VirtualPathResolver(List<MediaRootConfig> mediaRoots) {
        this.roots = List.copyOf(computeDisplayNames(mediaRoots));
    }

    public List<VirtualRoot> getRoots() {
        return roots;
    }

    public Path resolveToAbsolute(String virtualPath) {
        String stripped = virtualPath.startsWith("/") ? virtualPath.substring(1) : virtualPath;
        if (stripped.isBlank()) {
            throw new InvalidPathRequestException("Empty virtual path");
        }

        int separatorIndex = stripped.indexOf('/');
        String rootName = separatorIndex < 0 ? stripped : stripped.substring(0, separatorIndex);
        String subPath = separatorIndex < 0 ? "" : stripped.substring(separatorIndex + 1);

        VirtualRoot root = roots.stream()
            .filter(candidate -> candidate.displayName().equals(rootName))
            .findFirst()
            .orElseThrow(() -> new VirtualRootNotFoundException(rootName));

        return safeResolve(root.physicalPath(), subPath);
    }

    public String toVirtualPath(Path absolutePath) {
        Path canonical;
        try {
            canonical = absolutePath.toRealPath();
        } catch (Exception ignored) {
            canonical = absolutePath.toAbsolutePath().normalize();
        }

        for (VirtualRoot root : roots) {
            Path canonicalRoot;
            try {
                canonicalRoot = root.physicalPath().toRealPath();
            } catch (Exception ignored) {
                canonicalRoot = root.physicalPath().toAbsolutePath().normalize();
            }

            if (canonical.startsWith(canonicalRoot)) {
                String relative = canonicalRoot.relativize(canonical).toString();
                return relative.isEmpty() ? root.displayName() : root.displayName() + "/" + relative;
            }
        }
        return null;
    }

    public static List<VirtualRoot> computeDisplayNames(List<MediaRootConfig> mediaRoots) {
        List<RootCandidate> candidates = new ArrayList<>();
        for (MediaRootConfig config : mediaRoots) {
            if (!config.displayName().isBlank()) {
                candidates.add(new RootCandidate(config.displayName(), config));
                continue;
            }

            Path fileName = config.path().getFileName();
            String name = fileName == null ? "" : Objects.toString(fileName, "");
            candidates.add(new RootCandidate(name.isBlank() ? "media" : name, config));
        }

        List<VirtualRoot> resolved = new ArrayList<>();
        for (RootCandidate candidate : candidates) {
            MediaRootConfig config = candidate.config();
            if (!config.displayName().isBlank()) {
                resolved.add(new VirtualRoot(candidate.name(), config.path(), config));
                continue;
            }

            long collisions = candidates.stream()
                .filter(other -> other.name().equals(candidate.name()))
                .count();
            if (collisions > 1) {
                Path parentFileName = config.path().getParent() == null ? null : config.path().getParent().getFileName();
                String parent = parentFileName == null ? "" : parentFileName.toString();
                String disambiguated = parent.isBlank() ? candidate.name() : parent + "-" + candidate.name();
                resolved.add(new VirtualRoot(disambiguated, config.path(), config));
            } else {
                resolved.add(new VirtualRoot(candidate.name(), config.path(), config));
            }
        }
        return resolved;
    }

    private Path safeResolve(Path root, String subPath) {
        Path canonicalRoot;
        try {
            canonicalRoot = root.toRealPath();
        } catch (Exception ignored) {
            throw new PathNotFoundException("Media root not found on disk");
        }

        if (subPath.isBlank()) {
            return canonicalRoot;
        }

        Path resolved = root.resolve(subPath);
        Path canonicalResolved;
        try {
            canonicalResolved = resolved.toRealPath();
        } catch (Exception ignored) {
            throw new PathNotFoundException(subPath);
        }

        if (!canonicalResolved.startsWith(canonicalRoot)) {
            throw new PathNotAllowedException(subPath);
        }
        return canonicalResolved;
    }

    public record VirtualRoot(String displayName, Path physicalPath, MediaRootConfig config) {
        public String getDisplayName() {
            return displayName;
        }

        public Path getPhysicalPath() {
            return physicalPath;
        }

        public MediaRootConfig getConfig() {
            return config;
        }
    }

    private record RootCandidate(String name, MediaRootConfig config) {
    }
}
