package com.nyx.common;

import com.nyx.config.MediaRootConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VirtualPathResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void uniqueRootNamesUseBasenameDirectly() throws Exception {
        Path movies = Files.createDirectories(tempDir.resolve("movies"));
        Path music = Files.createDirectories(tempDir.resolve("music"));

        VirtualPathResolver resolver = new VirtualPathResolver(List.of(
            new MediaRootConfig(movies),
            new MediaRootConfig(music)
        ));

        assertThat(resolver.getRoots()).extracting(VirtualPathResolver.VirtualRoot::displayName)
            .containsExactly("movies", "music");
    }

    @Test
    void collidingBasenamesUseDisambiguatedParentPrefix() throws Exception {
        Path dir1 = Files.createDirectories(tempDir.resolve("disk1/movies"));
        Path dir2 = Files.createDirectories(tempDir.resolve("disk2/movies"));
        Path dir3 = Files.createDirectories(tempDir.resolve("disk3/movies"));

        VirtualPathResolver resolver = new VirtualPathResolver(List.of(
            new MediaRootConfig(dir1),
            new MediaRootConfig(dir2),
            new MediaRootConfig(dir3)
        ));

        assertThat(resolver.getRoots()).extracting(VirtualPathResolver.VirtualRoot::displayName)
            .containsExactly("disk1-movies", "disk2-movies", "disk3-movies");
    }

    @Test
    void explicitDisplayNamesArePreserved() throws Exception {
        Path movies = Files.createDirectories(tempDir.resolve("movies"));

        VirtualPathResolver resolver = new VirtualPathResolver(List.of(
            new MediaRootConfig(movies, "local", "library")
        ));

        assertThat(resolver.getRoots()).extracting(VirtualPathResolver.VirtualRoot::displayName)
            .containsExactly("library");
    }

    @Test
    void resolvesRootOnlyAndNestedPaths() throws Exception {
        Path movies = Files.createDirectories(tempDir.resolve("movies"));
        Path file = Files.createDirectories(movies.resolve("action")).resolve("film.mp4");
        Files.createFile(file);
        VirtualPathResolver resolver = new VirtualPathResolver(List.of(new MediaRootConfig(movies)));

        assertThat(resolver.resolveToAbsolute("movies")).isEqualTo(movies.toRealPath());
        assertThat(resolver.resolveToAbsolute("/movies/action/film.mp4")).isEqualTo(file.toRealPath());
    }

    @Test
    void invalidVirtualPathsThrowSpecificExceptions() throws Exception {
        Path movies = Files.createDirectories(tempDir.resolve("movies"));
        Path secret = Files.createDirectories(tempDir.resolve("secret"));
        Files.createFile(secret.resolve("hidden.txt"));
        VirtualPathResolver resolver = new VirtualPathResolver(List.of(new MediaRootConfig(movies)));

        assertThatThrownBy(() -> resolver.resolveToAbsolute(""))
            .isInstanceOf(InvalidPathRequestException.class);
        assertThatThrownBy(() -> resolver.resolveToAbsolute("nonexistent"))
            .isInstanceOf(VirtualRootNotFoundException.class);
        assertThatThrownBy(() -> resolver.resolveToAbsolute("movies/../secret/hidden.txt"))
            .isInstanceOf(PathNotAllowedException.class);
        assertThatThrownBy(() -> resolver.resolveToAbsolute("movies/no/such/file"))
            .isInstanceOf(PathNotFoundException.class);
    }

    @Test
    void toVirtualPathConvertsAbsolutePathsAndReturnsNullOutsideRoots() throws Exception {
        Path movies = Files.createDirectories(tempDir.resolve("movies"));
        Path file = Files.createDirectories(movies.resolve("action")).resolve("film.mp4");
        Files.createFile(file);
        VirtualPathResolver resolver = new VirtualPathResolver(List.of(new MediaRootConfig(movies)));
        Path outside = Files.createTempDirectory("nyx-outside");

        try {
            assertThat(resolver.toVirtualPath(file)).isEqualTo("movies/action/film.mp4");
            assertThat(resolver.toVirtualPath(movies)).isEqualTo("movies");
            assertThat(resolver.toVirtualPath(outside)).isNull();
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void toVirtualPathFallsBackToNormalizedPathForNonexistentFiles() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("media"));
        VirtualPathResolver resolver = new VirtualPathResolver(List.of(new MediaRootConfig(root)));

        assertThat(resolver.toVirtualPath(root.resolve("nonexistent.txt")))
            .isEqualTo("media/nonexistent.txt");
    }

    @Test
    void deletedRootsFailResolution() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("deleted-root"));
        VirtualPathResolver resolver = new VirtualPathResolver(List.of(new MediaRootConfig(root)));
        Files.delete(root);

        assertThatThrownBy(() -> resolver.resolveToAbsolute("deleted-root"))
            .isInstanceOf(PathNotFoundException.class);
    }

    @Test
    void computeDisplayNamesHandlesRootAndDuplicateNames() {
        List<VirtualPathResolver.VirtualRoot> deduplicated = VirtualPathResolver.computeDisplayNames(List.of(
            new MediaRootConfig(Path.of("/data/media")),
            new MediaRootConfig(Path.of("/other/media"))
        ));
        List<VirtualPathResolver.VirtualRoot> rootOnly = VirtualPathResolver.computeDisplayNames(List.of(
            new MediaRootConfig(Path.of("/"))
        ));

        assertThat(deduplicated).extracting(VirtualPathResolver.VirtualRoot::displayName)
            .containsExactly("data-media", "other-media");
        assertThat(rootOnly).extracting(VirtualPathResolver.VirtualRoot::displayName)
            .containsExactly("media");
    }
}
