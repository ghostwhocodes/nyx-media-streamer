package com.nyx.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PathSecurityTest {
    @TempDir
    Path tempRoot;

    @Test
    void validPathWithinMediaRootSucceeds() throws Exception {
        Path movies = Files.createDirectories(tempRoot.resolve("movies"));
        Path file = Files.createFile(movies.resolve("test.mkv"));
        PathSecurity pathSecurity = new PathSecurity(List.of(tempRoot));

        Path result = pathSecurity.validate(file.toString());

        assertThat(result).isEqualTo(file.toRealPath());
    }

    @Test
    void pathTraversalWithDotDotIsRejected() throws Exception {
        Files.createDirectories(tempRoot.resolve("movies"));
        Files.createFile(tempRoot.resolve("movies/test.mkv"));
        PathSecurity pathSecurity = new PathSecurity(List.of(tempRoot));
        String outsidePath = tempRoot.resolve("../../../etc/passwd").toString();

        assertThatThrownBy(() -> pathSecurity.validate(outsidePath))
            .isInstanceOf(PathingException.class);
    }

    @Test
    void symlinkEscapingMediaRootIsRejected() throws Exception {
        Path movies = Files.createDirectories(tempRoot.resolve("movies"));
        Path outsideDir = Files.createTempDirectory("nyx-test-outside");
        Path secretFile = Files.createFile(outsideDir.resolve("secret.txt"));
        Path symlink = movies.resolve("link.txt");
        Files.createSymbolicLink(symlink, secretFile);
        PathSecurity pathSecurity = new PathSecurity(List.of(tempRoot));

        try {
            assertThatThrownBy(() -> pathSecurity.validate(symlink.toString()))
                .isInstanceOf(PathNotAllowedException.class);
        } finally {
            Files.deleteIfExists(symlink);
            Files.deleteIfExists(secretFile);
            Files.deleteIfExists(outsideDir);
        }
    }

    @Test
    void nullBytesInPathAreRejected() {
        PathSecurity pathSecurity = new PathSecurity(List.of(tempRoot));

        assertThatThrownBy(() -> pathSecurity.validate("/media/movies/test\u0000.mkv"))
            .isInstanceOf(PathNotAllowedException.class);
    }

    @Test
    void emptyBlankAndRelativePathsAreRejected() {
        PathSecurity pathSecurity = new PathSecurity(List.of(tempRoot));

        assertThatThrownBy(() -> pathSecurity.validate(""))
            .isInstanceOf(PathNotAllowedException.class);
        assertThatThrownBy(() -> pathSecurity.validate("   "))
            .isInstanceOf(PathNotAllowedException.class);
        assertThatThrownBy(() -> pathSecurity.validate("movies/test.mkv"))
            .isInstanceOf(PathNotAllowedException.class);
    }

    @Test
    void nonexistentFileIsRejected() throws Exception {
        Files.createDirectories(tempRoot.resolve("movies"));
        PathSecurity pathSecurity = new PathSecurity(List.of(tempRoot));

        assertThatThrownBy(() -> pathSecurity.validate(tempRoot.resolve("movies/nonexistent.mkv").toString()))
            .isInstanceOf(PathNotFoundException.class);
    }

    @Test
    void validateDirectoryRejectsNonDirectory() throws Exception {
        Path movies = Files.createDirectories(tempRoot.resolve("movies"));
        Path file = Files.createFile(movies.resolve("test.mkv"));
        PathSecurity pathSecurity = new PathSecurity(List.of(tempRoot));

        assertThatThrownBy(() -> pathSecurity.validateDirectory(file.toString()))
            .isInstanceOf(PathNotDirectoryException.class);
    }

    @Test
    void constructorHandlesNonexistentRootGracefully() {
        PathSecurity pathSecurity = new PathSecurity(List.of(Path.of("/non/existent/path/surely")));

        assertThatThrownBy(() -> pathSecurity.validate("/some/file.txt"))
            .isInstanceOf(PathingException.class);
    }
}
