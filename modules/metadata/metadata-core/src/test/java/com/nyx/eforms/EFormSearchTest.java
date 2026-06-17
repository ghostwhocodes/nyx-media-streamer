package com.nyx.eforms;

import static com.nyx.eforms.MetadataContractFactories.fieldDefinition;
import static com.nyx.eforms.MetadataContractFactories.searchQuery;
import static com.nyx.eforms.TestJsonSupport.jsonPrimitive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.common.DatabaseResources;
import com.nyx.eforms.model.FieldType;
import com.nyx.eforms.model.MediaType;
import com.nyx.eforms.model.SearchResult;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EFormSearchTest {
    private Path tempDir;
    private DatabaseResources databaseResources;
    private Jdbi db;
    private HikariDataSource ds;
    private EFormService service;

    @BeforeEach
    void setup() throws IOException {
        tempDir = Files.createTempDirectory("nyx-eform-search-test");
        databaseResources = EFormsDatabase.createDatabase(tempDir);
        db = databaseResources.getJdbi();
        ds = databaseResources.getDataSource();
        service = new EFormService(db);
    }

    @AfterEach
    void teardown() {
        if (ds != null) {
            ds.close();
        }
        TestFileSupport.deleteTree(tempDir);
    }

    @Test
    void ftsFindsMetadataByTextQuery() {
        seedData();

        SearchResult result = service.search(searchQuery("Matrix"));
        assertEquals(1, result.results().size());
        assertEquals("/media/matrix.mkv", result.results().get(0).mediaPath());
    }

    @Test
    void ftsFindsMultipleResults() {
        seedData();

        SearchResult result = service.search(searchQuery("Nolan"));
        assertEquals(2, result.results().size());
    }

    @Test
    void formScopedSearchDoesNotCrossForms() {
        SeededForms forms = seedData();

        SearchResult result = service.search(searchQuery(
            "Abbey",
            Map.of(),
            forms.movieFormId(),
            null,
            null,
            null,
            null
        ));
        assertEquals(0, result.results().size());
    }

    @Test
    void crossFormSearchFindsResultsFromAllForms() {
        seedData();

        SearchResult result = service.search(searchQuery("The"));
        assertTrue(result.results().size() >= 2);
    }

    @Test
    void fieldFilterWorksExactMatchOnNumber() {
        seedData();

        SearchResult result = service.search(searchQuery(
            null,
            Map.of("year", jsonPrimitive(2010)),
            null,
            null,
            null,
            null,
            null
        ));
        assertEquals(1, result.results().size());
        assertEquals("/media/inception.mkv", result.results().get(0).mediaPath());
    }

    @Test
    void fieldFilterWorksExactMatchOnBoolean() {
        SeededForms forms = seedData();

        SearchResult result = service.search(searchQuery(
            null,
            Map.of("watched", jsonPrimitive(true)),
            forms.movieFormId(),
            null,
            null,
            null,
            null
        ));
        assertEquals(2, result.results().size());
    }

    @Test
    void fieldFilterWorksExactMatchOnSelect() {
        SeededForms forms = seedData();

        SearchResult result = service.search(searchQuery(
            null,
            Map.of("genre", jsonPrimitive("Action")),
            forms.movieFormId(),
            null,
            null,
            null,
            null
        ));
        assertEquals(1, result.results().size());
        assertEquals("/media/dark_knight.mkv", result.results().get(0).mediaPath());
    }

    @Test
    void paginationWorksWithLimit() {
        seedData();

        SearchResult result = service.search(searchQuery(null, Map.of(), null, null, null, 2, 0));
        assertEquals(2, result.results().size());
        assertEquals(4, result.total());
        assertEquals(2, result.limit());
        assertEquals(0, result.offset());
    }

    @Test
    void paginationWorksWithOffset() {
        seedData();

        SearchResult page1 = service.search(searchQuery(null, Map.of(), null, null, null, 2, 0));
        SearchResult page2 = service.search(searchQuery(null, Map.of(), null, null, null, 2, 2));

        assertEquals(2, page1.results().size());
        assertEquals(2, page2.results().size());
        var allPaths = java.util.stream.Stream.concat(page1.results().stream(), page2.results().stream())
            .map(result -> result.mediaPath())
            .collect(Collectors.toSet());
        assertEquals(4, allPaths.size());
    }

    @Test
    void ftsResultsHaveRelevanceScores() {
        seedData();

        SearchResult result = service.search(searchQuery("Matrix"));
        assertEquals(1, result.results().size());
        assertNotNull(result.results().get(0).relevance());
    }

    @Test
    void emptyTextSearchReturnsAll() {
        seedData();

        SearchResult result = service.search(searchQuery());
        assertEquals(4, result.total());
    }

    @Test
    void combinedFtsAndFieldFilter() {
        seedData();

        SearchResult result = service.search(searchQuery(
            "Nolan",
            Map.of("genre", jsonPrimitive("Action")),
            null,
            null,
            null,
            null,
            null
        ));
        assertEquals(1, result.results().size());
        assertEquals("/media/dark_knight.mkv", result.results().get(0).mediaPath());
    }

    private SeededForms seedData() {
        var movieForm = service.createForm(
            "Movies",
            Set.of(MediaType.VIDEO),
            List.of(
                fieldDefinition("title", FieldType.TEXT, true),
                fieldDefinition("director", FieldType.TEXT),
                fieldDefinition("year", FieldType.NUMBER),
                fieldDefinition("genre", FieldType.SELECT, false, List.of("Action", "Drama", "Sci-Fi")),
                fieldDefinition("watched", FieldType.BOOLEAN)
            )
        );

        var musicForm = service.createForm(
            "Music",
            Set.of(MediaType.AUDIO),
            List.of(
                fieldDefinition("title", FieldType.TEXT, true),
                fieldDefinition("artist", FieldType.TEXT),
                fieldDefinition("year", FieldType.NUMBER)
            )
        );

        service.attachMetadata(
            "/media/matrix.mkv",
            movieForm.id(),
            Map.of(
                "title", jsonPrimitive("The Matrix"),
                "director", jsonPrimitive("Wachowski Sisters"),
                "year", jsonPrimitive(1999),
                "genre", jsonPrimitive("Sci-Fi"),
                "watched", jsonPrimitive(true)
            )
        );
        service.attachMetadata(
            "/media/inception.mkv",
            movieForm.id(),
            Map.of(
                "title", jsonPrimitive("Inception"),
                "director", jsonPrimitive("Christopher Nolan"),
                "year", jsonPrimitive(2010),
                "genre", jsonPrimitive("Sci-Fi"),
                "watched", jsonPrimitive(false)
            )
        );
        service.attachMetadata(
            "/media/dark_knight.mkv",
            movieForm.id(),
            Map.of(
                "title", jsonPrimitive("The Dark Knight"),
                "director", jsonPrimitive("Christopher Nolan"),
                "year", jsonPrimitive(2008),
                "genre", jsonPrimitive("Action"),
                "watched", jsonPrimitive(true)
            )
        );
        service.attachMetadata(
            "/media/abbey_road.flac",
            musicForm.id(),
            Map.of(
                "title", jsonPrimitive("Abbey Road"),
                "artist", jsonPrimitive("The Beatles"),
                "year", jsonPrimitive(1969)
            )
        );

        return new SeededForms(movieForm.id(), musicForm.id());
    }

    private record SeededForms(String movieFormId, String musicFormId) {
    }
}
