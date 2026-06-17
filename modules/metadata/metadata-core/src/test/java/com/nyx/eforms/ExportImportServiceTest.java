package com.nyx.eforms;

import static com.nyx.eforms.MetadataContractFactories.fieldDefinition;
import static com.nyx.eforms.MetadataContractFactories.formDefinition;
import static com.nyx.eforms.MetadataContractFactories.formVersion;
import static com.nyx.eforms.TestJsonSupport.jsonPrimitive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.common.DatabaseResources;
import com.nyx.eforms.model.FieldDefinition;
import com.nyx.eforms.model.FieldType;
import com.nyx.eforms.model.MediaType;
import com.nyx.json.NyxJson;
import com.zaxxer.hikari.HikariDataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExportImportServiceTest {
    private final ObjectMapper coverageJson = NyxJson.newMapper();

    private Path tempDir;
    private Jdbi db;
    private EFormService service;
    private ExportImportService exportImport;
    private final List<HikariDataSource> datasources = new ArrayList<>();

    @BeforeEach
    void setup() throws IOException {
        tempDir = Files.createTempDirectory("nyx-export-import-test");
        DatabaseResources resources = EFormsDatabase.createDatabase(tempDir);
        db = resources.getJdbi();
        datasources.add(resources.getDataSource());
        service = new EFormService(db);
        exportImport = new ExportImportService(service);
    }

    @AfterEach
    void teardown() {
        datasources.forEach(HikariDataSource::close);
        datasources.clear();
        TestFileSupport.deleteTree(tempDir);
    }

    @Test
    void exportProducesValidZipWithCorrectStructure() throws IOException {
        seedData();

        byte[] zipBytes = exportImport.export();
        assertTrue(zipBytes.length > 0);

        List<String> entries = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry = zip.getNextEntry();
            while (entry != null) {
                entries.add(entry.getName());
                zip.closeEntry();
                entry = zip.getNextEntry();
            }
        }

        assertTrue(entries.stream().anyMatch(name -> name.endsWith("manifest.json")));
        assertTrue(entries.stream().anyMatch(name -> name.contains("/forms/") && name.endsWith(".json")));
        assertTrue(entries.stream().anyMatch(name -> name.contains("/metadata/") && name.endsWith(".json")));
    }

    @Test
    void roundTripExportThenImportToFreshDbYieldsIdenticalData() throws IOException {
        seedData();

        byte[] zipBytes = exportImport.export();

        Path freshDir = Files.createTempDirectory("nyx-import-fresh");
        try {
            DatabaseResources resources = EFormsDatabase.createDatabase(freshDir);
            datasources.add(resources.getDataSource());
            EFormService freshService = new EFormService(resources.getJdbi());
            ExportImportService freshExportImport = new ExportImportService(freshService);

            ImportResult result = freshExportImport.importArchive(zipBytes);

            assertEquals(2, result.getFormsCreated());
            assertEquals(0, result.getFormsSkipped());
            assertEquals(3, result.getMetadataCreated());
            assertEquals(0, result.getMetadataUpdated());
            assertTrue(result.getErrors().isEmpty());

            var forms = freshService.listForms();
            assertEquals(2, forms.size());
            assertTrue(forms.stream().anyMatch(form -> form.name().equals("Movies")));
            assertTrue(forms.stream().anyMatch(form -> form.name().equals("Music")));

            var movieForm = forms.stream().filter(form -> form.name().equals("Movies")).findFirst().orElseThrow();
            var movieMetadata = freshService.getMetadataByFormId(movieForm.id());
            assertEquals(2, movieMetadata.size());
        } finally {
            TestFileSupport.deleteTree(freshDir);
        }
    }

    @Test
    void partialImportOnlyImportsSpecifiedForms() throws IOException {
        seedData();

        byte[] zipBytes = exportImport.export();

        Path freshDir = Files.createTempDirectory("nyx-import-partial");
        try {
            DatabaseResources resources = EFormsDatabase.createDatabase(freshDir);
            datasources.add(resources.getDataSource());
            EFormService freshService = new EFormService(resources.getJdbi());
            ExportImportService freshExportImport = new ExportImportService(freshService);

            ImportResult result = freshExportImport.importArchive(zipBytes, Set.of("Movies"));

            assertEquals(1, result.getFormsCreated());
            assertEquals(1, result.getFormsSkipped());
            assertEquals(2, result.getMetadataCreated());
            assertEquals(1, result.getMetadataSkipped());

            var forms = freshService.listForms();
            assertEquals(1, forms.size());
            assertEquals("Movies", forms.get(0).name());
        } finally {
            TestFileSupport.deleteTree(freshDir);
        }
    }

    @Test
    void importHandlesVersionConflictsBySkippingExistingForms() {
        seedData();

        ImportResult result = exportImport.importArchive(exportImport.export());

        assertEquals(0, result.getFormsCreated());
        assertEquals(2, result.getFormsSkipped());
        assertEquals(0, result.getMetadataCreated());
        assertEquals(3, result.getMetadataUpdated());
    }

    @Test
    void importIsIdempotent() throws IOException {
        seedData();

        byte[] zipBytes = exportImport.export();

        Path freshDir = Files.createTempDirectory("nyx-import-idempotent");
        try {
            DatabaseResources resources = EFormsDatabase.createDatabase(freshDir);
            datasources.add(resources.getDataSource());
            EFormService freshService = new EFormService(resources.getJdbi());
            ExportImportService freshExportImport = new ExportImportService(freshService);

            ImportResult first = freshExportImport.importArchive(zipBytes);
            assertEquals(2, first.getFormsCreated());
            assertEquals(3, first.getMetadataCreated());

            ImportResult second = freshExportImport.importArchive(zipBytes);
            assertEquals(0, second.getFormsCreated());
            assertEquals(2, second.getFormsSkipped());
            assertEquals(0, second.getMetadataCreated());
            assertEquals(3, second.getMetadataUpdated());

            assertEquals(2, freshService.listForms().size());
        } finally {
            TestFileSupport.deleteTree(freshDir);
        }
    }

    @Test
    void exportEmptyDatabaseProducesValidZip() throws IOException {
        byte[] zipBytes = exportImport.export();
        assertTrue(zipBytes.length > 0);

        Path freshDir = Files.createTempDirectory("nyx-import-empty");
        try {
            DatabaseResources resources = EFormsDatabase.createDatabase(freshDir);
            datasources.add(resources.getDataSource());
            EFormService freshService = new EFormService(resources.getJdbi());
            ExportImportService freshExportImport = new ExportImportService(freshService);

            ImportResult result = freshExportImport.importArchive(zipBytes);
            assertEquals(0, result.getFormsCreated());
            assertEquals(0, result.getMetadataCreated());
            assertTrue(result.getErrors().isEmpty());
        } finally {
            TestFileSupport.deleteTree(freshDir);
        }
    }

    @Test
    void importFormWithEmptyVersionsListRecordsHasNoVersionsError() throws IOException {
        Instant now = Instant.now();
        var form = formDefinition(
            "form-no-versions",
            "EmptyVersionsForm",
            1,
            Set.of(MediaType.VIDEO),
            List.of(),
            now,
            Instant.EPOCH
        );
        ExportManifest manifest = new ExportManifest(now, 1, 0);

        byte[] zipBytes = buildTestZip(
            Map.entry("export/manifest.json", coverageJson.writeValueAsString(manifest)),
            Map.entry("export/forms/EmptyVersionsForm.json", coverageJson.writeValueAsString(form))
        );

        ImportResult result = exportImport.importArchive(zipBytes);

        assertEquals(0, result.getFormsCreated());
        assertEquals(0, result.getFormsSkipped());
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).contains("has no versions"));
    }

    @Test
    void importFormWithMultipleVersionsCreatesFormThenAppliesSubsequentVersions() throws IOException {
        Instant now = Instant.now();
        List<FieldDefinition> fieldsV1 = List.of(fieldDefinition("title", FieldType.TEXT, true));
        List<FieldDefinition> fieldsV2 = List.of(
            fieldDefinition("title", FieldType.TEXT, true),
            fieldDefinition("year", FieldType.NUMBER)
        );
        List<FieldDefinition> fieldsV3 = List.of(
            fieldDefinition("title", FieldType.TEXT, true),
            fieldDefinition("year", FieldType.NUMBER),
            fieldDefinition("genre", FieldType.TEXT)
        );

        var form = formDefinition(
            "form-multi-ver",
            "MultiVersionForm",
            3,
            Set.of(MediaType.VIDEO),
            List.of(
                formVersion(1, fieldsV1, now),
                formVersion(2, fieldsV2, now),
                formVersion(3, fieldsV3, now)
            ),
            now,
            Instant.EPOCH
        );
        ExportManifest manifest = new ExportManifest(now, 1, 0);

        byte[] zipBytes = buildTestZip(
            Map.entry("export/manifest.json", coverageJson.writeValueAsString(manifest)),
            Map.entry("export/forms/MultiVersionForm.json", coverageJson.writeValueAsString(form))
        );

        ImportResult result = exportImport.importArchive(zipBytes);

        assertEquals(1, result.getFormsCreated());
        assertTrue(result.getErrors().isEmpty());

        var imported = service.listForms().stream()
            .filter(candidate -> candidate.name().equals("MultiVersionForm"))
            .findFirst()
            .orElse(null);
        assertNotNull(imported);
        assertEquals(3, imported.currentVersion());

        var fullForm = service.getForm(imported.id());
        assertNotNull(fullForm);
        assertEquals(3, fullForm.versions().size());
    }

    @Test
    void importMetadataReferencingUnmappedFormIdIncrementsMetadataSkipped() throws IOException {
        Instant now = Instant.now();
        ExportManifest manifest = new ExportManifest(now, 0, 1);
        ExportedMetadata metadata = new ExportedMetadata(
            "/media/orphan.mkv",
            List.of(new ExportedMetadataEntry(
                "non-existent-form-id",
                1,
                Map.of("title", jsonPrimitive("Orphan")),
                now,
                now
            ))
        );

        byte[] zipBytes = buildTestZip(
            Map.entry("export/manifest.json", coverageJson.writeValueAsString(manifest)),
            Map.entry("export/metadata/media_orphan_mkv.json", coverageJson.writeValueAsString(metadata))
        );

        ImportResult result = exportImport.importArchive(zipBytes);

        assertEquals(0, result.getFormsCreated());
        assertEquals(0, result.getMetadataCreated());
        assertEquals(1, result.getMetadataSkipped());
        assertTrue(result.getErrors().isEmpty());
    }

    @Test
    void importFormThatTriggersExceptionRecordsErrorMessage() throws IOException {
        Instant now = Instant.now();
        List<FieldDefinition> fieldsV1 = List.of(fieldDefinition("title", FieldType.TEXT, true));
        List<FieldDefinition> fieldsV2 = List.of(
            fieldDefinition("title", FieldType.TEXT),
            fieldDefinition("title", FieldType.NUMBER)
        );

        var form = formDefinition(
            "form-bad-v2",
            "BadVersionForm",
            2,
            Set.of(MediaType.AUDIO),
            List.of(formVersion(1, fieldsV1, now), formVersion(2, fieldsV2, now)),
            now,
            Instant.EPOCH
        );
        ExportManifest manifest = new ExportManifest(now, 1, 0);

        byte[] zipBytes = buildTestZip(
            Map.entry("export/manifest.json", coverageJson.writeValueAsString(manifest)),
            Map.entry("export/forms/BadVersionForm.json", coverageJson.writeValueAsString(form))
        );

        ImportResult result = exportImport.importArchive(zipBytes);

        assertEquals(0, result.getFormsCreated());
        assertFalse(result.getErrors().isEmpty());
        assertTrue(result.getErrors().get(0).contains("Failed to import form 'BadVersionForm'"));
    }

    @Test
    void importMetadataThatTriggersExceptionRecordsErrorMessage() throws IOException {
        Instant now = Instant.now();
        var createdForm = service.createForm(
            "TestForm",
            Set.of(MediaType.VIDEO),
            List.of(fieldDefinition("title", FieldType.TEXT, true))
        );

        var form = formDefinition(
            createdForm.id(),
            "TestForm",
            1,
            Set.of(MediaType.VIDEO),
            List.of(formVersion(
                1,
                List.of(fieldDefinition("title", FieldType.TEXT, true)),
                now
            )),
            now,
            Instant.EPOCH
        );
        ExportManifest manifest = new ExportManifest(now, 1, 1);
        ExportedMetadata metadata = new ExportedMetadata(
            "/media/bad-meta.mkv",
            List.of(new ExportedMetadataEntry(
                createdForm.id(),
                1,
                Map.of("title", jsonPrimitive(12345)),
                now,
                now
            ))
        );

        byte[] zipBytes = buildTestZip(
            Map.entry("export/manifest.json", coverageJson.writeValueAsString(manifest)),
            Map.entry("export/forms/TestForm.json", coverageJson.writeValueAsString(form)),
            Map.entry("export/metadata/media_bad-meta_mkv.json", coverageJson.writeValueAsString(metadata))
        );

        ImportResult result = exportImport.importArchive(zipBytes);

        assertEquals(0, result.getFormsCreated());
        assertEquals(1, result.getFormsSkipped());
        assertEquals(0, result.getMetadataCreated());
        assertFalse(result.getErrors().isEmpty());
        assertTrue(result.getErrors().stream().anyMatch(error ->
            error.contains("Failed to import metadata for '/media/bad-meta.mkv'")
        ));
    }

    @Test
    void importSkipsFormWhenExistingHasLowerVersionThanImported() throws IOException {
        Path exportDir = tempDir.resolve("export-ver");
        Path importDir = tempDir.resolve("import-ver");
        Files.createDirectories(exportDir);
        Files.createDirectories(importDir);

        DatabaseResources sourceResources = EFormsDatabase.createDatabase(exportDir);
        datasources.add(sourceResources.getDataSource());
        EFormService sourceService = new EFormService(sourceResources.getJdbi());

        List<FieldDefinition> fields1 = List.of(fieldDefinition("title", FieldType.TEXT, true));
        List<FieldDefinition> fields2 = List.of(
            fieldDefinition("title", FieldType.TEXT, true),
            fieldDefinition("year", FieldType.NUMBER)
        );

        var form = sourceService.createForm("Movies", Set.of(MediaType.VIDEO), fields1);
        sourceService.updateForm(form.id(), fields2);

        byte[] zipBytes = new ExportImportService(sourceService).export();

        DatabaseResources targetResources = EFormsDatabase.createDatabase(importDir);
        datasources.add(targetResources.getDataSource());
        EFormService targetService = new EFormService(targetResources.getJdbi());
        targetService.createForm("Movies", Set.of(MediaType.VIDEO), fields1);

        ImportResult result = new ExportImportService(targetService).importArchive(zipBytes);

        assertEquals(0, result.getFormsCreated());
        assertEquals(1, result.getFormsSkipped());

        var forms = targetService.listForms();
        assertEquals(1, forms.size());
        assertEquals("Movies", forms.get(0).name());
        assertEquals(1, forms.get(0).currentVersion());
    }

    @Test
    void importMetadataWithMixOfMappedAndUnmappedFormIds() throws IOException {
        Instant now = Instant.now();
        List<FieldDefinition> fields = List.of(fieldDefinition("title", FieldType.TEXT, true));
        var form = formDefinition(
            "form-known",
            "KnownForm",
            1,
            Set.of(MediaType.VIDEO),
            List.of(formVersion(1, fields, now)),
            now,
            Instant.EPOCH
        );
        ExportManifest manifest = new ExportManifest(now, 1, 2);
        ExportedMetadata metadata = new ExportedMetadata(
            "/media/mixed.mkv",
            List.of(
                new ExportedMetadataEntry(
                    "form-known",
                    1,
                    Map.of("title", jsonPrimitive("Valid Entry")),
                    now,
                    now
                ),
                new ExportedMetadataEntry(
                    "form-unknown",
                    1,
                    Map.of("title", jsonPrimitive("Should Be Skipped")),
                    now,
                    now
                )
            )
        );

        byte[] zipBytes = buildTestZip(
            Map.entry("export/manifest.json", coverageJson.writeValueAsString(manifest)),
            Map.entry("export/forms/KnownForm.json", coverageJson.writeValueAsString(form)),
            Map.entry("export/metadata/media_mixed_mkv.json", coverageJson.writeValueAsString(metadata))
        );

        ImportResult result = exportImport.importArchive(zipBytes);

        assertEquals(1, result.getFormsCreated());
        assertEquals(1, result.getMetadataCreated());
        assertEquals(1, result.getMetadataSkipped());
        assertTrue(result.getErrors().isEmpty());
    }

    private void seedData() {
        var form = service.createForm(
            "Movies",
            Set.of(MediaType.VIDEO),
            List.of(
                fieldDefinition("title", FieldType.TEXT, true),
                fieldDefinition("year", FieldType.NUMBER)
            )
        );

        service.attachMetadata("/media/matrix.mkv", form.id(), Map.of(
            "title", jsonPrimitive("The Matrix"),
            "year", jsonPrimitive(1999)
        ));
        service.attachMetadata("/media/inception.mkv", form.id(), Map.of(
            "title", jsonPrimitive("Inception"),
            "year", jsonPrimitive(2010)
        ));

        var musicForm = service.createForm(
            "Music",
            Set.of(MediaType.AUDIO),
            List.of(
                fieldDefinition("title", FieldType.TEXT, true),
                fieldDefinition("artist", FieldType.TEXT)
            )
        );
        service.attachMetadata("/media/song.flac", musicForm.id(), Map.of(
            "title", jsonPrimitive("Bohemian Rhapsody"),
            "artist", jsonPrimitive("Queen")
        ));
    }

    @SafeVarargs
    private static byte[] buildTestZip(Map.Entry<String, String>... entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, String> entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
