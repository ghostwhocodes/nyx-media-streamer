package com.nyx.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nyx.config.MediaRootConfig;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PathingContractCoverageTest {

    @Test
    void mediaRootConfigConstructorsPreserveDefaultsAndAccessors() {
        Path root = Path.of("/srv/media");

        MediaRootConfig defaulted = new MediaRootConfig(root);
        MediaRootConfig filesystemOnly = new MediaRootConfig(root, "s3");
        MediaRootConfig nullDefaulted = new MediaRootConfig(root, null, null);

        assertSame(root, defaulted.path());
        assertSame(root, defaulted.getPath());
        assertEquals("local", defaulted.filesystem());
        assertEquals("local", defaulted.getFilesystem());
        assertEquals("", defaulted.displayName());
        assertEquals("", defaulted.getDisplayName());

        assertEquals("s3", filesystemOnly.filesystem());
        assertEquals("", filesystemOnly.displayName());

        assertEquals("local", nullDefaulted.filesystem());
        assertEquals("", nullDefaulted.displayName());
    }

    @Test
    void mediaRootConfigRejectsNullPathsAndVirtualRootsExposeBeanGetters() {
        Path root = Path.of("/srv/library");
        MediaRootConfig config = new MediaRootConfig(root, "local", "library");
        VirtualPathResolver.VirtualRoot virtualRoot = new VirtualPathResolver.VirtualRoot("library", root, config);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new MediaRootConfig(null, "local", "broken")
        );

        assertEquals("path must not be null", exception.getMessage());
        assertEquals("library", virtualRoot.getDisplayName());
        assertSame(root, virtualRoot.getPhysicalPath());
        assertSame(config, virtualRoot.getConfig());
    }

    @Test
    void pathingExceptionConstructorsPreserveMessageAndCause() {
        IllegalStateException cause = new IllegalStateException("boom");

        PathingException simple = new PathingException("bad path");
        PathingException wrapped = new PathingException("bad path", cause);

        assertEquals("bad path", simple.getMessage());
        assertEquals("bad path", wrapped.getMessage());
        assertSame(cause, wrapped.getCause());
    }
}
