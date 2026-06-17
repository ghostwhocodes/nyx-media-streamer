package com.nyx.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nyx.common.DatabaseResources;
import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.common.PathNotAllowedException;
import com.nyx.common.PathSecurity;
import com.nyx.media.model.UpsertChapterMarkRequest;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChapterServicePathValidationTest {
    @TempDir
    Path tempDir;

    private Path mediaRoot;
    private Path outsideDir;
    private Jdbi lastDb;
    private int dbCounter;
    private final List<HikariDataSource> dataSources = new ArrayList<>();

    @BeforeEach
    void setup() throws IOException {
        mediaRoot = Files.createDirectories(tempDir.resolve("media-root"));
        outsideDir = Files.createDirectories(tempDir.resolve("outside-root"));
        Files.createDirectories(mediaRoot.resolve("videos"));
        Files.createFile(mediaRoot.resolve("videos/movie.mkv"));
        Files.createFile(outsideDir.resolve("evil.mkv"));
    }

    @AfterEach
    void teardown() {
        ModuleMediaTestSupport.closeDataSources(dataSources);
    }

    private ChapterService createService(PathSecurity pathSecurity) throws IOException {
        dbCounter++;
        Path dbDir = Files.createDirectories(tempDir.resolve("db" + dbCounter));
        DatabaseResources resources = ChapterService.createDatabase(dbDir);
        dataSources.add(resources.getDataSource());
        lastDb = resources.getJdbi();
        return new ChapterService(resources.getJdbi(), pathSecurity);
    }

    private ChapterService createServiceWithSameDb(ChapterService original, PathSecurity pathSecurity) {
        original.toString();
        return new ChapterService(lastDb, pathSecurity);
    }

    @Test
    void upsertForMediaPathAcceptsMediaPathInsideAllowedRoot() throws IOException {
        ChapterService service = createService(new PathSecurity(List.of(mediaRoot)));
        String mediaPath = mediaRoot.resolve("videos/movie.mkv").toString();

        var stored = service.upsertForMediaPath(
            mediaPath,
            "Movie",
            List.of(new UpsertChapterMarkRequest("Intro", 0.0, "", 0))
        );

        assertEquals(mediaRoot.resolve("videos/movie.mkv").toRealPath().toString(), stored.mediaPath());
        assertEquals(1, stored.marks().size());
    }

    @Test
    void upsertForMediaPathRejectsMediaPathOutsideAllowedRoot() throws IOException {
        ChapterService service = createService(new PathSecurity(List.of(mediaRoot)));
        String mediaPath = outsideDir.resolve("evil.mkv").toString();

        assertThrows(
            PathNotAllowedException.class,
            () -> service.upsertForMediaPath(
                mediaPath,
                "Nope",
                List.of(new UpsertChapterMarkRequest("Intro", 0.0, "", 0))
            )
        );
    }

    @Test
    void updateMarkRejectsMarkWhoseChapterSetPathIsNoLongerAllowed() throws IOException {
        ChapterService serviceWithoutSecurity = createService(null);
        String outsidePath = outsideDir.resolve("evil.mkv").toString();
        var created = serviceWithoutSecurity.upsertForMediaPath(
            outsidePath,
            "Evil",
            List.of(new UpsertChapterMarkRequest("Intro", 0.0, "", 0))
        );
        String markId = created.marks().getFirst().id();

        ChapterService securedService = createServiceWithSameDb(
            serviceWithoutSecurity,
            new PathSecurity(List.of(mediaRoot))
        );

        assertThrows(
            PathNotAllowedException.class,
            () -> securedService.updateMark(markId, "Updated", 1.0, "", 0)
        );
    }

    @Test
    void deleteMarkRejectsMarkWhoseChapterSetPathIsNoLongerAllowed() throws IOException {
        ChapterService serviceWithoutSecurity = createService(null);
        String outsidePath = outsideDir.resolve("evil.mkv").toString();
        var created = serviceWithoutSecurity.upsertForMediaPath(
            outsidePath,
            "Evil",
            List.of(new UpsertChapterMarkRequest("Intro", 0.0, "", 0))
        );
        String markId = created.marks().getFirst().id();

        ChapterService securedService = createServiceWithSameDb(
            serviceWithoutSecurity,
            new PathSecurity(List.of(mediaRoot))
        );

        assertThrows(PathNotAllowedException.class, () -> securedService.deleteMark(markId));
    }

    @Test
    void upsertForMediaPathRejectsDirectoryInsideAllowedRoot() throws IOException {
        ChapterService service = createService(new PathSecurity(List.of(mediaRoot)));
        String mediaPath = mediaRoot.resolve("videos").toString();

        NyxException error = assertThrows(
            NyxException.class,
            () -> service.upsertForMediaPath(
                mediaPath,
                "Nope",
                List.of(new UpsertChapterMarkRequest("Intro", 0.0, "", 0))
            )
        );

        assertEquals(ErrorCode.INVALID_REQUEST, error.getErrorCode());
        assertEquals("Media path must reference a file", error.getMessage());
    }
}
