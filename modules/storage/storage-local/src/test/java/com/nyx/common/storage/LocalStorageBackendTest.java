package com.nyx.common.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalStorageBackendTest {
    private Path tempDir;
    private LocalStorageBackend backend;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("nyx-local-storage");
        backend = new LocalStorageBackend(tempDir);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (var stream = Files.walk(tempDir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        }
    }

    @Test
    void writeAndReadReturnsSameBytes() {
        byte[] data = "hello world".getBytes();
        backend.write("test/file.txt", data);

        assertThat(backend.read("test/file.txt")).containsExactly(data);
    }

    @Test
    void readReturnsNullForNonExistentKey() {
        assertThat(backend.read("does/not/exist")).isNull();
    }

    @Test
    void existsReturnsTrueForExistingKey() {
        backend.write("exists.txt", "data".getBytes());

        assertThat(backend.exists("exists.txt")).isTrue();
    }

    @Test
    void existsReturnsFalseForNonExistentKey() {
        assertThat(backend.exists("nope.txt")).isFalse();
    }

    @Test
    void deleteRemovesFileAndReturnsTrue() {
        backend.write("delete-me.txt", "data".getBytes());

        assertThat(backend.delete("delete-me.txt")).isTrue();
        assertThat(backend.exists("delete-me.txt")).isFalse();
    }

    @Test
    void deleteReturnsFalseForNonExistentKey() {
        assertThat(backend.delete("no-file.txt")).isFalse();
    }

    @Test
    void metadataReturnsSizeAndLastModified() {
        byte[] data = new byte[42];
        backend.write("meta-test.bin", data, "application/octet-stream");

        StorageMetadata meta = backend.metadata("meta-test.bin");

        assertThat(meta).isNotNull();
        assertThat(meta.sizeBytes()).isEqualTo(42L);
        assertThat(meta.contentType()).isEqualTo("application/octet-stream");
        assertThat(meta.lastModifiedEpochMillis()).isPositive();
    }

    @Test
    void metadataReturnsNullForNonExistentKey() {
        assertThat(backend.metadata("nope")).isNull();
    }

    @Test
    void metadataIncludesUserMetadataFromSidecar() {
        Map<String, String> userMeta = Map.of("sourceSize", "1234", "sourceMtime", "5678");
        backend.write("with-meta.txt", "x".getBytes(), userMeta);

        StorageMetadata meta = backend.metadata("with-meta.txt");

        assertThat(meta).isNotNull();
        assertThat(meta.userMetadata()).containsEntry("sourceSize", "1234");
        assertThat(meta.userMetadata()).containsEntry("sourceMtime", "5678");
    }

    @Test
    void listReturnsKeysUnderPrefixExcludingMetaFiles() {
        backend.write("thumbs/a/150.jpg", "a".getBytes(), Map.of("k", "v"));
        backend.write("thumbs/a/300.jpg", "b".getBytes());
        backend.write("other/file.txt", "c".getBytes());

        List<String> listed = backend.list("thumbs").stream().sorted().toList();

        assertThat(listed).hasSize(2);
        assertThat(listed).anyMatch(path -> path.contains("150.jpg"));
        assertThat(listed).anyMatch(path -> path.contains("300.jpg"));
        assertThat(listed).noneMatch(path -> path.endsWith(".meta"));
    }

    @Test
    void listReturnsEmptyForNonExistentPrefix() {
        assertThat(backend.list("nonexistent")).isEmpty();
    }

    @Test
    void deletePrefixRemovesAllFilesUnderPrefix() {
        backend.write("prefix/a.txt", "a".getBytes());
        backend.write("prefix/b.txt", "b".getBytes());
        backend.write("other/c.txt", "c".getBytes());

        int deleted = backend.deletePrefix("prefix");

        assertThat(deleted).isGreaterThanOrEqualTo(2);
        assertThat(backend.exists("prefix/a.txt")).isFalse();
        assertThat(backend.exists("prefix/b.txt")).isFalse();
        assertThat(backend.exists("other/c.txt")).isTrue();
    }

    @Test
    void deletePrefixReturnsZeroForNonExistentPrefix() {
        assertThat(backend.deletePrefix("nope")).isZero();
    }

    @Test
    void totalSizeSumsFileSizesUnderPrefix() {
        backend.write("size/a.bin", new byte[100]);
        backend.write("size/b.bin", new byte[200]);
        backend.write("other/c.bin", new byte[300]);

        assertThat(backend.totalSize("size")).isEqualTo(300L);
    }

    @Test
    void totalSizeReturnsZeroForNonExistentPrefix() {
        assertThat(backend.totalSize("nonexistent")).isZero();
    }

    @Test
    void writeOverwritesExistingFile() {
        backend.write("overwrite.txt", "v1".getBytes());
        backend.write("overwrite.txt", "v2".getBytes());

        assertThat(backend.read("overwrite.txt")).containsExactly("v2".getBytes());
    }

    @Test
    void nestedKeyPathsAreSupported() {
        String key = "a/b/c/d/deep.txt";
        backend.write(key, "deep".getBytes());

        assertThat(backend.exists(key)).isTrue();
        assertThat(backend.read(key)).containsExactly("deep".getBytes());
    }

    @Test
    void readRejectsPathTraversalKey() {
        assertThatThrownBy(() -> backend.read("../../etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writeRejectsPathTraversalKey() {
        assertThatThrownBy(() -> backend.write("../escape/file.txt", "bad".getBytes()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteRejectsPathTraversalKey() {
        assertThatThrownBy(() -> backend.delete("../../etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void metadataRejectsPathTraversalKey() {
        assertThatThrownBy(() -> backend.metadata("../escape"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listRejectsPathTraversalPrefix() {
        assertThatThrownBy(() -> backend.list("../../etc"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankKeyIsRejected() {
        assertThatThrownBy(() -> backend.read("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> backend.read("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void metadataRoundTripsContentTypeThroughJsonSidecar() {
        backend.write("ct-test.bin", new byte[10], "application/octet-stream", Map.of("foo", "bar"));

        StorageMetadata meta = backend.metadata("ct-test.bin");

        assertThat(meta).isNotNull();
        assertThat(meta.contentType()).isEqualTo("application/octet-stream");
        assertThat(meta.userMetadata()).containsEntry("foo", "bar");
    }

    @Test
    void deletePrefixCountExcludesMetaFiles() {
        backend.write("counted/a.txt", "a".getBytes(), Map.of("k", "v"));
        backend.write("counted/b.txt", "b".getBytes(), Map.of("k", "v"));

        assertThat(backend.deletePrefix("counted")).isEqualTo(2);
    }

    @Test
    void listWithMetadataReturnsEntriesWithSizeAndMtime() {
        backend.write("lm/a.bin", new byte[42]);
        backend.write("lm/b.bin", new byte[77]);

        List<StorageEntryInfo> entries = backend.listWithMetadata("lm");

        assertThat(entries).hasSize(2);
        StorageEntryInfo entryA = entries.stream().filter(entry -> entry.key().contains("a.bin")).findFirst().orElseThrow();
        StorageEntryInfo entryB = entries.stream().filter(entry -> entry.key().contains("b.bin")).findFirst().orElseThrow();
        assertThat(entryA.sizeBytes()).isEqualTo(42L);
        assertThat(entryA.lastModifiedEpochMillis()).isPositive();
        assertThat(entryB.sizeBytes()).isEqualTo(77L);
    }

    @Test
    void listWithMetadataReturnsEmptyForNonExistentPrefix() {
        assertThat(backend.listWithMetadata("nonexistent")).isEmpty();
    }

    @Test
    void listWithMetadataExcludesMetaFilesFromResults() {
        backend.write("lm2/file.dat", "data".getBytes(), Map.of("k", "v"));

        List<StorageEntryInfo> entries = backend.listWithMetadata("lm2");

        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().key()).doesNotEndWith(".meta");
    }

    @Test
    void writeWithoutMetadataOrContentTypeDoesNotCreateSidecar() {
        backend.write("no-sidecar/file.txt", "data".getBytes());

        StorageMetadata meta = backend.metadata("no-sidecar/file.txt");

        assertThat(meta).isNotNull();
        assertThat(meta.contentType()).isNull();
        assertThat(meta.userMetadata()).isEmpty();
    }

    @Test
    void overwritingWithEmptyMetadataRemovesExistingSidecar() {
        backend.write("sidecar-rm/file.txt", "v1".getBytes(), "text/plain", Map.of("k", "v"));
        StorageMetadata meta1 = backend.metadata("sidecar-rm/file.txt");
        assertThat(meta1).isNotNull();
        assertThat(meta1.contentType()).isEqualTo("text/plain");

        backend.write("sidecar-rm/file.txt", "v2".getBytes());
        StorageMetadata meta2 = backend.metadata("sidecar-rm/file.txt");
        assertThat(meta2).isNotNull();
        assertThat(meta2.contentType()).isNull();
        assertThat(meta2.userMetadata()).isEmpty();
    }

    @Test
    void metadataWithCorruptSidecarReturnsDefaults() throws IOException {
        backend.write("corrupt/file.txt", "data".getBytes(), "text/plain");
        Files.writeString(tempDir.resolve("corrupt/file.txt.meta"), "not valid json {{{");

        StorageMetadata meta = backend.metadata("corrupt/file.txt");

        assertThat(meta).isNotNull();
        assertThat(meta.contentType()).isNull();
        assertThat(meta.userMetadata()).isEmpty();
    }

    @Test
    void deleteRemovesSidecarAlongsideDataFile() {
        backend.write("del-sidecar/file.txt", "data".getBytes(), "text/plain", Map.of("k", "v"));
        Path sidecar = tempDir.resolve("del-sidecar/file.txt.meta");
        assertThat(Files.exists(sidecar)).isTrue();

        backend.delete("del-sidecar/file.txt");

        assertThat(Files.exists(sidecar)).isFalse();
        assertThat(backend.exists("del-sidecar/file.txt")).isFalse();
    }

    @Test
    void closeIsANoOpAndDoesNotThrow() {
        assertThatCode(backend::close).doesNotThrowAnyException();
    }

    @Test
    void deletePrefixCountsOnlyNonMetaFiles() {
        backend.write("prefix/file1.txt", "hello".getBytes(), "text/plain");
        backend.write("prefix/file2.txt", "world".getBytes(), "text/plain");

        assertThat(backend.deletePrefix("prefix")).isEqualTo(2);
    }

    @Test
    void totalSizeSumsFileSizesCorrectly() {
        byte[] data = "test data content".getBytes();
        backend.write("sizes/a.txt", data, "text/plain");
        backend.write("sizes/b.txt", data, "text/plain");

        assertThat(backend.totalSize("sizes")).isEqualTo((long) data.length * 2L);
    }

    @Test
    void constructorWrapsBaseDirectoryCreationFailures() throws IOException {
        Path fileRoot = Files.writeString(tempDir.resolve("file-root"), "not a directory");

        assertThatThrownBy(() -> new LocalStorageBackend(fileRoot.resolve("child")))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Failed to create local storage base directory");
    }

    @Test
    void readWrapsIoFailuresWhenKeyResolvesToDirectory() throws IOException {
        Files.createDirectories(tempDir.resolve("read-as-dir"));

        assertThatThrownBy(() -> backend.read("read-as-dir"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Failed to read local storage object read-as-dir");
    }

    @Test
    void writeWrapsIoFailuresWhenKeyResolvesToDirectory() throws IOException {
        Files.createDirectories(tempDir.resolve("write-as-dir"));

        assertThatThrownBy(() -> backend.write("write-as-dir", "data".getBytes()))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Failed to write local storage object write-as-dir");
    }

    @Test
    void deleteWrapsIoFailuresWhenTargetDirectoryIsNotEmpty() throws IOException {
        Path directory = Files.createDirectories(tempDir.resolve("non-empty-dir"));
        Files.writeString(directory.resolve("child.txt"), "child");

        assertThatThrownBy(() -> backend.delete("non-empty-dir"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Failed to delete local storage object non-empty-dir");
    }

    @Test
    void writeWrapsSidecarDeletionFailuresWhenMetaDirectoryIsNonEmpty() throws IOException {
        Path metaDirectory = Files.createDirectories(tempDir.resolve("broken-sidecar/file.txt.meta"));
        Files.writeString(metaDirectory.resolve("child.txt"), "child");

        assertThatThrownBy(() -> backend.write("broken-sidecar/file.txt", "data".getBytes()))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Failed to delete local storage metadata sidecar for broken-sidecar/file.txt");
    }

    @Test
    void writeWrapsSidecarWriteFailuresWhenMetaPathIsDirectory() throws IOException {
        Files.createDirectories(tempDir.resolve("broken-sidecar-write/file.txt.meta"));

        assertThatThrownBy(() -> backend.write("broken-sidecar-write/file.txt", "data".getBytes(), "text/plain", Map.of("k", "v")))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Failed to write local storage metadata sidecar for broken-sidecar-write/file.txt");
    }

    @Test
    void listWrapsIoFailuresWhenPrefixDirectoryCannotBeWalked() throws IOException {
        Assumptions.assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"));

        Path restricted = Files.createDirectories(tempDir.resolve("restricted"));
        Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(restricted);
        Files.setPosixFilePermissions(restricted, Set.of());

        try {
            assertThatThrownBy(() -> backend.list("restricted"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to list local storage prefix restricted");
        } finally {
            Files.setPosixFilePermissions(restricted, originalPermissions);
        }
    }
}
