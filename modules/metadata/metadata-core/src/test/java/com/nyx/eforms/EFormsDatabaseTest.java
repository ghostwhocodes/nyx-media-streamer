package com.nyx.eforms;

import static com.nyx.common.SqliteWriteTransactions.withHandleUnchecked;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.common.DatabaseResources;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EFormsDatabaseTest {
    private Path tempDir;
    private DatabaseResources databaseResources;
    private Jdbi db;
    private HikariDataSource ds;

    @BeforeEach
    void setup() throws IOException {
        tempDir = Files.createTempDirectory("nyx-eforms-db-test");
        databaseResources = EFormsDatabase.createDatabase(tempDir);
        db = databaseResources.getJdbi();
        ds = databaseResources.getDataSource();
    }

    @AfterEach
    void teardown() {
        if (ds != null) {
            ds.close();
        }
        TestFileSupport.deleteTree(tempDir);
    }

    @Test
    void tablesAreCreatedSuccessfully() {
        assertEquals(0, countRows("form_definitions"));
        assertEquals(0, countRows("form_versions"));
        assertEquals(0, countRows("media_metadata"));
    }

    @Test
    void insertAndSelectFormDefinition() {
        String now = Instant.now().toString();
        withHandleUnchecked(db, handle -> {
            handle.createUpdate(
                """
                INSERT INTO form_definitions (id, name, media_types, current_version, created_at, updated_at)
                VALUES (:id, :name, :mediaTypes, :currentVersion, :createdAt, :updatedAt)
                """
            )
                .bind("id", "form-1")
                .bind("name", "Movie Info")
                .bind("mediaTypes", "[\"VIDEO\"]")
                .bind("currentVersion", 1)
                .bind("createdAt", now)
                .bind("updatedAt", now)
                .execute();
            return null;
        });

        FormDefinitionRow row = withHandleUnchecked(db, handle -> handle
            .createQuery("SELECT id, name, current_version FROM form_definitions")
            .map((rs, ctx) -> new FormDefinitionRow(
                rs.getString("id"),
                rs.getString("name"),
                rs.getInt("current_version")
            ))
            .one());

        assertEquals("form-1", row.id());
        assertEquals("Movie Info", row.name());
        assertEquals(1, row.currentVersion());
    }

    @Test
    void insertAndSelectFormVersion() {
        String now = Instant.now().toString();
        withHandleUnchecked(db, handle -> {
            handle.createUpdate(
                """
                INSERT INTO form_definitions (id, name, media_types, current_version, created_at, updated_at)
                VALUES (:id, :name, :mediaTypes, :currentVersion, :createdAt, :updatedAt)
                """
            )
                .bind("id", "form-1")
                .bind("name", "Test")
                .bind("mediaTypes", "[\"VIDEO\"]")
                .bind("currentVersion", 1)
                .bind("createdAt", now)
                .bind("updatedAt", now)
                .execute();
            handle.createUpdate(
                """
                INSERT INTO form_versions (id, form_id, version, fields, created_at)
                VALUES (:id, :formId, :version, :fields, :createdAt)
                """
            )
                .bind("id", "fv-1")
                .bind("formId", "form-1")
                .bind("version", 1)
                .bind("fields", "[{\"name\":\"title\",\"type\":\"TEXT\",\"required\":true}]")
                .bind("createdAt", now)
                .execute();
            return null;
        });

        FormVersionRow row = withHandleUnchecked(db, handle -> handle
            .createQuery("SELECT id, form_id, version FROM form_versions")
            .map((rs, ctx) -> new FormVersionRow(
                rs.getString("id"),
                rs.getString("form_id"),
                rs.getInt("version")
            ))
            .one());

        assertEquals("fv-1", row.id());
        assertEquals("form-1", row.formId());
        assertEquals(1, row.version());
    }

    @Test
    void insertAndSelectMediaMetadata() {
        String now = Instant.now().toString();
        withHandleUnchecked(db, handle -> {
            handle.createUpdate(
                """
                INSERT INTO form_definitions (id, name, media_types, current_version, created_at, updated_at)
                VALUES (:id, :name, :mediaTypes, :currentVersion, :createdAt, :updatedAt)
                """
            )
                .bind("id", "form-1")
                .bind("name", "Test")
                .bind("mediaTypes", "[\"VIDEO\"]")
                .bind("currentVersion", 1)
                .bind("createdAt", now)
                .bind("updatedAt", now)
                .execute();
            handle.createUpdate(
                """
                INSERT INTO media_metadata (
                    id, media_path, content_hash, form_id, form_version, "values", created_at, updated_at
                ) VALUES (
                    :id, :mediaPath, NULL, :formId, :formVersion, :values, :createdAt, :updatedAt
                )
                """
            )
                .bind("id", "meta-1")
                .bind("mediaPath", "/media/test.mp4")
                .bind("formId", "form-1")
                .bind("formVersion", 1)
                .bind("values", "{\"title\":\"Test Movie\"}")
                .bind("createdAt", now)
                .bind("updatedAt", now)
                .execute();
            return null;
        });

        MediaMetadataRow row = withHandleUnchecked(db, handle -> handle
            .createQuery("SELECT id, media_path, content_hash, form_id FROM media_metadata")
            .map((rs, ctx) -> new MediaMetadataRow(
                rs.getString("id"),
                rs.getString("media_path"),
                rs.getString("content_hash"),
                rs.getString("form_id")
            ))
            .one());

        assertEquals("meta-1", row.id());
        assertEquals("/media/test.mp4", row.mediaPath());
        assertNull(row.contentHash());
        assertEquals("form-1", row.formId());
    }

    @Test
    void fts5VirtualTableIsCreatedAndSearchable() {
        withHandleUnchecked(db, handle -> {
            handle.createUpdate("INSERT INTO metadata_fts(metadata_id, content) VALUES ('meta-1', 'The Dark Knight movie review')")
                .execute();
            handle.createUpdate("INSERT INTO metadata_fts(metadata_id, content) VALUES ('meta-2', 'Inception sci-fi thriller')")
                .execute();
            return null;
        });

        var results = withHandleUnchecked(db, handle -> handle
            .createQuery("SELECT metadata_id FROM metadata_fts WHERE metadata_fts MATCH 'Knight'")
            .mapTo(String.class)
            .list());

        assertEquals(1, results.size());
        assertEquals("meta-1", results.get(0));
    }

    @Test
    void fts5SearchReturnsMultipleMatches() {
        withHandleUnchecked(db, handle -> {
            handle.createUpdate("INSERT INTO metadata_fts(metadata_id, content) VALUES ('meta-1', 'action movie thriller')")
                .execute();
            handle.createUpdate("INSERT INTO metadata_fts(metadata_id, content) VALUES ('meta-2', 'comedy movie romance')")
                .execute();
            handle.createUpdate("INSERT INTO metadata_fts(metadata_id, content) VALUES ('meta-3', 'documentary nature')")
                .execute();
            return null;
        });

        var results = withHandleUnchecked(db, handle -> handle
            .createQuery("SELECT metadata_id FROM metadata_fts WHERE metadata_fts MATCH 'movie'")
            .mapTo(String.class)
            .list());

        assertEquals(2, results.size());
        assertTrue(results.contains("meta-1"));
        assertTrue(results.contains("meta-2"));
    }

    private int countRows(String table) {
        return withHandleUnchecked(db, handle -> handle
            .createQuery("SELECT COUNT(*) FROM " + table)
            .mapTo(Integer.class)
            .one());
    }

    private record FormDefinitionRow(String id, String name, int currentVersion) {
    }

    private record FormVersionRow(String id, String formId, int version) {
    }

    private record MediaMetadataRow(String id, String mediaPath, String contentHash, String formId) {
    }
}
