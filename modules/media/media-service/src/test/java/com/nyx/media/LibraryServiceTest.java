package com.nyx.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.common.DatabaseResources;
import com.nyx.config.DatabaseConfig;
import com.nyx.media.contracts.CreateLibraryRequest;
import com.nyx.media.contracts.LibrarySourceRootWriteRequest;
import com.nyx.media.contracts.LibraryType;
import com.nyx.media.contracts.UpdateLibraryRequest;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryServiceTest {
    @TempDir
    Path tempDir;

    private final List<HikariDataSource> dataSources = new ArrayList<>();

    @AfterEach
    void tearDown() {
        ModuleMediaTestSupport.closeDataSources(dataSources);
    }

    private LibraryService createService() {
        DatabaseResources resources = LibraryService.createDatabase(tempDir, new DatabaseConfig(tempDir, 1));
        dataSources.add(resources.getDataSource());
        return new LibraryService(resources.getJdbi());
    }

    private String rootPath(String name) throws Exception {
        return Files.createDirectories(tempDir.resolve(name)).toString();
    }

    @Test
    void createDatabaseProvisionsLibraryTables() throws Exception {
        DatabaseResources resources = LibraryService.createDatabase(tempDir);
        dataSources.add(resources.getDataSource());

        Set<String> tables = new java.util.HashSet<>();
        Set<String> libraryColumns = new java.util.HashSet<>();

        try (var connection = resources.getDataSource().getConnection();
             var statement = connection.createStatement()) {
            try (var resultSet = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
                while (resultSet.next()) {
                    tables.add(resultSet.getString("name"));
                }
            }
            try (var resultSet = statement.executeQuery("PRAGMA table_info(libraries)")) {
                while (resultSet.next()) {
                    libraryColumns.add(resultSet.getString("name"));
                }
            }
        }

        assertTrue(tables.contains("libraries"));
        assertTrue(tables.contains("library_source_roots"));
        assertTrue(libraryColumns.contains("scan_status"));
        assertTrue(libraryColumns.contains("last_scan_error"));
    }

    @Test
    void createUpdateListAndDeleteTypedLibraries() throws Exception {
        LibraryService service = createService();
        String moviesRoot = rootPath("movies");
        String extrasRoot = rootPath("movies-extras");

        var created = service.createLibrary(
            new CreateLibraryRequest(
                " Movies ",
                " Primary films ",
                LibraryType.MOVIE,
                List.of(
                    new LibrarySourceRootWriteRequest(moviesRoot, "Feature Films"),
                    new LibrarySourceRootWriteRequest(extrasRoot)
                )
            )
        );

        assertEquals("Movies", created.name());
        assertEquals("Primary films", created.description());
        assertEquals(LibraryType.MOVIE, created.type());
        assertEquals(2, created.sourceRoots().size());
        assertEquals("Feature Films", created.sourceRoots().getFirst().displayName());
        assertEquals("movies-extras", Path.of(created.sourceRoots().getLast().path()).getFileName().toString());
        assertEquals(0, created.sourceRoots().getFirst().position());
        assertEquals(1, created.sourceRoots().getLast().position());

        var updated = service.updateLibrary(
            created.libraryId(),
            new UpdateLibraryRequest(
                "Curated Movies",
                "",
                LibraryType.GENERIC_VIDEO,
                List.of(
                    new LibrarySourceRootWriteRequest(extrasRoot, "Bonus Features"),
                    new LibrarySourceRootWriteRequest(moviesRoot, "Movies")
                )
            )
        );

        var listed = service.listLibraries();

        assertEquals("Curated Movies", updated.name());
        assertEquals("", updated.description());
        assertEquals(LibraryType.GENERIC_VIDEO, updated.type());
        assertEquals(List.of(extrasRoot, moviesRoot), updated.sourceRoots().stream().sorted(java.util.Comparator.comparingInt(root -> root.position())).map(root -> root.path()).toList());
        assertEquals(1, listed.size());
        assertEquals(created.libraryId(), listed.getFirst().libraryId());

        service.deleteLibrary(created.libraryId());

        assertNull(service.getLibrary(created.libraryId()));
        assertTrue(service.listLibraries().isEmpty());
    }

    @Test
    void updatePreservesStableSourceRootIdsForUnchangedPaths() throws Exception {
        LibraryService service = createService();
        String showsRoot = rootPath("shows");

        var created = service.createLibrary(
            new CreateLibraryRequest(
                "Shows",
                LibraryType.SHOW,
                List.of(new LibrarySourceRootWriteRequest(showsRoot, "TV"))
            )
        );

        var updated = service.updateLibrary(
            created.libraryId(),
            new UpdateLibraryRequest(
                null,
                null,
                null,
                List.of(new LibrarySourceRootWriteRequest(showsRoot, "Series"))
            )
        );

        assertEquals(created.sourceRoots().getFirst().sourceRootId(), updated.sourceRoots().getFirst().sourceRootId());
        assertEquals("Series", updated.sourceRoots().getFirst().displayName());
    }

    @Test
    void sourceRootsMustBeUniqueWithinAndAcrossLibraries() throws Exception {
        LibraryService service = createService();
        String musicRoot = rootPath("music");

        NyxException duplicateError = assertThrows(NyxException.class, () -> service.createLibrary(
            new CreateLibraryRequest(
                "Music",
                LibraryType.MUSIC,
                List.of(
                    new LibrarySourceRootWriteRequest(musicRoot),
                    new LibrarySourceRootWriteRequest(musicRoot)
                )
            )
        ));
        assertEquals(ErrorCode.INVALID_REQUEST, duplicateError.getErrorCode());

        var created = service.createLibrary(
            new CreateLibraryRequest(
                "Music",
                LibraryType.MUSIC,
                List.of(new LibrarySourceRootWriteRequest(musicRoot))
            )
        );

        NyxException conflictError = assertThrows(NyxException.class, () -> service.createLibrary(
            new CreateLibraryRequest(
                "Backup Music",
                LibraryType.MUSIC,
                List.of(new LibrarySourceRootWriteRequest(musicRoot))
            )
        ));
        assertEquals(ErrorCode.SCHEMA_CONFLICT, conflictError.getErrorCode());
        assertNotNull(service.getLibrary(created.libraryId()));
    }
}
