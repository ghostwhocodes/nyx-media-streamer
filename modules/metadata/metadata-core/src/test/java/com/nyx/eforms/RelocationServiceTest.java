package com.nyx.eforms;

import static com.nyx.eforms.MetadataContractFactories.fieldDefinition;
import static com.nyx.eforms.TestJsonSupport.jsonPrimitive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.common.DatabaseResources;
import com.nyx.eforms.model.FieldType;
import com.nyx.eforms.model.MediaType;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RelocationServiceTest {
    private Path tempDir;
    private DatabaseResources databaseResources;
    private Jdbi db;
    private HikariDataSource ds;
    private EFormService eformService;
    private RelocationService relocationService;

    @BeforeEach
    void setup() throws IOException {
        tempDir = Files.createTempDirectory("nyx-relocation-test");
        databaseResources = EFormsDatabase.createDatabase(tempDir);
        db = databaseResources.getJdbi();
        ds = databaseResources.getDataSource();
        eformService = new EFormService(db);
        relocationService = new RelocationService(db);
    }

    @AfterEach
    void teardown() {
        if (ds != null) {
            ds.close();
        }
        TestFileSupport.deleteTree(tempDir);
    }

    @Test
    void singleRelocationUpdatesMatchingRecords() {
        seedData();

        int count = relocationService.relocate(
            "/media/old/movies/matrix.mkv",
            "/media/new/movies/matrix.mkv"
        );

        assertEquals(1, count);

        var metadata = eformService.getMetadata("/media/new/movies/matrix.mkv");
        assertEquals(1, metadata.size());
        assertEquals("The Matrix", metadata.get(0).values().get("title").asText());
        assertTrue(eformService.getMetadata("/media/old/movies/matrix.mkv").isEmpty());
    }

    @Test
    void singleRelocationReturnsZeroForNonMatchingPath() {
        seedData();
        assertEquals(0, relocationService.relocate("/nonexistent/path", "/new/path"));
    }

    @Test
    void batchRelocationWithWildcardPattern() {
        seedData();

        RelocationResult result = relocationService.relocateBatch("/media/old/*", "/media/new/*");

        assertEquals(2, result.getUpdated());
        assertNull(result.getPreviews());
        assertEquals(1, eformService.getMetadata("/media/new/movies/matrix.mkv").size());
        assertEquals(1, eformService.getMetadata("/media/new/movies/inception.mkv").size());
        assertEquals(1, eformService.getMetadata("/media/other/song.flac").size());
    }

    @Test
    void dryRunReturnsPreviewWithoutModifyingDb() {
        seedData();

        RelocationResult result = relocationService.relocateBatch("/media/old/*", "/media/new/*", true);

        assertEquals(0, result.getUpdated());
        assertNotNull(result.getPreviews());
        assertEquals(2, result.getPreviews().size());
        assertEquals(1, eformService.getMetadata("/media/old/movies/matrix.mkv").size());
        assertEquals(1, eformService.getMetadata("/media/old/movies/inception.mkv").size());

        var previewPaths = result.getPreviews().stream()
            .map(preview -> preview.from() + "->" + preview.to())
            .collect(Collectors.toSet());
        assertTrue(previewPaths.contains("/media/old/movies/matrix.mkv->/media/new/movies/matrix.mkv"));
        assertTrue(previewPaths.contains("/media/old/movies/inception.mkv->/media/new/movies/inception.mkv"));
    }

    @Test
    void contentHashIsDeterministic() throws IOException {
        Path testFile = tempDir.resolve("test_file.bin");
        Files.write(testFile, createSequentialBytes(1024));

        String hash1 = RelocationService.computeContentHash(testFile);
        String hash2 = RelocationService.computeContentHash(testFile);

        assertEquals(hash1, hash2);
        assertEquals(64, hash1.length());
    }

    @Test
    void contentHashDiffersForDifferentFiles() throws IOException {
        Path file1 = tempDir.resolve("file1.bin");
        Path file2 = tempDir.resolve("file2.bin");
        Files.write(file1, createSequentialBytes(1024));
        Files.write(file2, createScaledBytes(1024));

        String hash1 = RelocationService.computeContentHash(file1);
        String hash2 = RelocationService.computeContentHash(file2);

        assertNotEquals(hash1, hash2);
    }

    @Test
    void contentHashHandlesLargeFilesWithHeadAndTail() throws IOException {
        Path bigFile = tempDir.resolve("large.bin");
        int size = 3 * 1024 * 1024;
        byte[] data = new byte[size];
        for (int index = 0; index < size; index++) {
            data[index] = (byte) (index % 256);
        }
        Files.write(bigFile, data);

        String hash = RelocationService.computeContentHash(bigFile);
        assertEquals(64, hash.length());
        assertEquals(hash, RelocationService.computeContentHash(bigFile));
    }

    @Test
    void contentHashHandlesSmallFiles() throws IOException {
        Path smallFile = tempDir.resolve("small.bin");
        Files.writeString(smallFile, "hello world");

        String hash = RelocationService.computeContentHash(smallFile);
        assertEquals(64, hash.length());
        assertEquals(hash, RelocationService.computeContentHash(smallFile));
    }

    private void seedData() {
        var form = eformService.createForm(
            "Movies",
            Set.of(MediaType.VIDEO),
            List.of(fieldDefinition("title", FieldType.TEXT, true))
        );

        eformService.attachMetadata(
            "/media/old/movies/matrix.mkv",
            form.id(),
            java.util.Map.of("title", jsonPrimitive("The Matrix"))
        );
        eformService.attachMetadata(
            "/media/old/movies/inception.mkv",
            form.id(),
            java.util.Map.of("title", jsonPrimitive("Inception"))
        );
        eformService.attachMetadata(
            "/media/other/song.flac",
            form.id(),
            java.util.Map.of("title", jsonPrimitive("Song"))
        );
    }

    private static byte[] createSequentialBytes(int size) {
        byte[] data = new byte[size];
        for (int index = 0; index < size; index++) {
            data[index] = (byte) index;
        }
        return data;
    }

    private static byte[] createScaledBytes(int size) {
        byte[] data = new byte[size];
        for (int index = 0; index < size; index++) {
            data[index] = (byte) (index * 2);
        }
        return data;
    }
}
