package com.nyx.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nyx.common.DatabaseResources;
import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.media.model.UpsertChapterMarkRequest;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChapterServiceTest {
    @TempDir
    Path tempDir;

    private ChapterService service;
    private HikariDataSource dataSource;

    @BeforeEach
    void setup() {
        DatabaseResources resources = ChapterService.createDatabase(tempDir.resolve("db"));
        dataSource = resources.getDataSource();
        service = new ChapterService(resources.getJdbi());
    }

    @AfterEach
    void teardown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void getByMediaPathReturnsNullWhenChapterSetIsAbsent() {
        assertNull(service.getByMediaPath("/media/movie.mkv"));
    }

    @Test
    void upsertForMediaPathCreatesChapterSetAndNormalizesOrdering() {
        var stored = service.upsertForMediaPath(
            "/media/movie.mkv",
            "  Feature Cut  ",
            List.of(
                new UpsertChapterMarkRequest(" Finale ", 92.75, "  ", 10),
                new UpsertChapterMarkRequest("Intro", 0.5, "cold open", 0),
                new UpsertChapterMarkRequest("Middle", 45.25, "", 10)
            )
        );

        assertNotNull(stored.id());
        assertEquals("Feature Cut", stored.title());
        assertEquals(3, stored.marks().size());
        assertEquals(List.of("Intro", "Finale", "Middle"), stored.marks().stream().map(mark -> mark.label()).toList());
        assertEquals(List.of(0, 1, 2), stored.marks().stream().map(mark -> mark.sortOrder()).toList());
        assertEquals(92.75, stored.marks().get(1).ptsSecs(), 0.0);
        assertEquals("", stored.marks().get(1).notes());
    }

    @Test
    void createMarkAppendsMarksAndPreservesFractionalSeconds() {
        service.createMark("/media/movie.mkv", "Intro", 0.0, "");
        var stored = service.createMark("/media/movie.mkv", "Scene 2", 12.75, "note");

        assertEquals(2, stored.marks().size());
        assertEquals(List.of(0, 1), stored.marks().stream().map(mark -> mark.sortOrder()).toList());
        assertEquals(12.75, stored.marks().get(1).ptsSecs(), 0.0);
    }

    @Test
    void updateMarkReordersTargetMarkWithinChapterSet() {
        var created = service.upsertForMediaPath(
            "/media/movie.mkv",
            "",
            List.of(
                new UpsertChapterMarkRequest("Intro", 0.0, "", 0),
                new UpsertChapterMarkRequest("Middle", 30.0, "", 1),
                new UpsertChapterMarkRequest("End", 60.0, "", 2)
            )
        );

        var updated = service.updateMark(
            created.marks().get(2).id(),
            "Ending",
            61.5,
            "",
            0
        );

        assertEquals(List.of("Ending", "Intro", "Middle"), updated.marks().stream().map(mark -> mark.label()).toList());
        assertEquals(List.of(0, 1, 2), updated.marks().stream().map(mark -> mark.sortOrder()).toList());
        assertEquals(61.5, updated.marks().getFirst().ptsSecs(), 0.0);
    }

    @Test
    void updateMarkMovesTargetMarkLaterInChapterSet() {
        var created = service.upsertForMediaPath(
            "/media/movie.mkv",
            "",
            List.of(
                new UpsertChapterMarkRequest("Intro", 0.0, "", 0),
                new UpsertChapterMarkRequest("Middle", 30.0, "", 1),
                new UpsertChapterMarkRequest("End", 60.0, "", 2)
            )
        );

        var updated = service.updateMark(
            created.marks().getFirst().id(),
            "Intro",
            0.0,
            "",
            2
        );

        assertEquals(List.of("Middle", "End", "Intro"), updated.marks().stream().map(mark -> mark.label()).toList());
        assertEquals(List.of(0, 1, 2), updated.marks().stream().map(mark -> mark.sortOrder()).toList());
    }

    @Test
    void deleteMarkRemovesMarkAndReindexesRemainingMarks() {
        var created = service.upsertForMediaPath(
            "/media/movie.mkv",
            "",
            List.of(
                new UpsertChapterMarkRequest("Intro", 0.0, "", 0),
                new UpsertChapterMarkRequest("End", 90.0, "", 1)
            )
        );

        service.deleteMark(created.marks().getFirst().id());
        var stored = service.getByMediaPath("/media/movie.mkv");

        assertNotNull(stored);
        assertEquals(1, stored.marks().size());
        assertEquals("End", stored.marks().getFirst().label());
        assertEquals(0, stored.marks().getFirst().sortOrder());
    }

    @Test
    void validationRejectsBlankLabel() {
        NyxException exception = assertThrows(
            NyxException.class,
            () -> service.createMark("/media/movie.mkv", "   ", 1.0, "")
        );

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.getErrorCode());
    }

    @Test
    void validationRejectsNegativePts() {
        NyxException exception = assertThrows(
            NyxException.class,
            () -> service.upsertForMediaPath(
                "/media/movie.mkv",
                "",
                List.of(new UpsertChapterMarkRequest("Bad", -1.0, "", 0))
            )
        );

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.getErrorCode());
    }

    @Test
    void upsertForMediaPathRejectsDuplicateMarkIds() {
        var created = service.upsertForMediaPath(
            "/media/movie.mkv",
            "Movie",
            List.of(
                new UpsertChapterMarkRequest("Intro", 0.0, "", 0),
                new UpsertChapterMarkRequest("Middle", 10.0, "", 1)
            )
        );
        String duplicateId = created.marks().getFirst().id();

        NyxException exception = assertThrows(
            NyxException.class,
            () -> service.upsertForMediaPath(
                "/media/movie.mkv",
                "Movie",
                List.of(
                    new UpsertChapterMarkRequest(duplicateId, "Intro", 0.0, "", 0),
                    new UpsertChapterMarkRequest(duplicateId, "Ending", 20.0, "", 1)
                )
            )
        );

        assertEquals(ErrorCode.VALIDATION_ERROR, exception.getErrorCode());
    }
}
