package com.nyx.eforms;

import static com.nyx.common.SqliteWriteTransactions.sqliteWriteTransaction;
import static com.nyx.common.SqliteWriteTransactions.withHandleUnchecked;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.jdbi.v3.core.Jdbi;

public final class RelocationService {
    private static final int CHUNK_SIZE = 1024 * 1024;

    private final Jdbi jdbi;

    public RelocationService(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public int relocate(String from, String to) {
        return sqliteWriteTransaction(jdbi, handle -> handle.createUpdate(
            """
            UPDATE media_metadata
            SET media_path = :toPath, updated_at = :updatedAt
            WHERE media_path = :fromPath
            """
        )
            .bind("toPath", to)
            .bind("updatedAt", Instant.now(Clock.systemUTC()).toString())
            .bind("fromPath", from)
            .execute());
    }

    public RelocationResult relocateBatch(String fromPattern, String toPattern) {
        return relocateBatch(fromPattern, toPattern, false);
    }

    public RelocationResult relocateBatch(String fromPattern, String toPattern, boolean dryRun) {
        String fromPrefix = fromPattern.endsWith("*") ? fromPattern.substring(0, fromPattern.length() - 1) : fromPattern;
        String toPrefix = toPattern.endsWith("*") ? toPattern.substring(0, toPattern.length() - 1) : toPattern;

        List<String[]> matchingRows = withHandleUnchecked(jdbi, handle -> handle.createQuery("SELECT id, media_path FROM media_metadata")
            .map((resultSet, ctx) -> new String[] {resultSet.getString("id"), resultSet.getString("media_path")})
            .list()
            .stream()
            .filter(row -> row[1].startsWith(fromPrefix))
            .toList());

        if (dryRun) {
            List<RelocationPreview> previews = matchingRows.stream()
                .map(row -> new RelocationPreview(row[1], toPrefix + row[1].substring(fromPrefix.length())))
                .toList();
            return new RelocationResult(0, previews);
        }

        String now = Instant.now(Clock.systemUTC()).toString();
        int[] updated = new int[] {0};
        sqliteWriteTransaction(jdbi, handle -> {
            for (String[] row : matchingRows) {
                handle.createUpdate(
                    """
                    UPDATE media_metadata
                    SET media_path = :mediaPath, updated_at = :updatedAt
                    WHERE id = :id
                    """
                )
                    .bind("mediaPath", toPrefix + row[1].substring(fromPrefix.length()))
                    .bind("updatedAt", now)
                    .bind("id", row[0])
                    .execute();
                updated[0]++;
            }
            return null;
        });
        return new RelocationResult(updated[0]);
    }

    public static String computeContentHash(Path path) {
        try {
            long fileSize = Files.size(path);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            try (var input = Files.newInputStream(path)) {
                if (fileSize <= CHUNK_SIZE * 2L) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = input.read(buffer)) != -1) {
                        digest.update(buffer, 0, bytesRead);
                    }
                } else {
                    byte[] firstChunk = new byte[CHUNK_SIZE];
                    int remaining = CHUNK_SIZE;
                    int offset = 0;
                    while (remaining > 0) {
                        int read = input.read(firstChunk, offset, remaining);
                        if (read == -1) {
                            break;
                        }
                        offset += read;
                        remaining -= read;
                    }
                    digest.update(firstChunk, 0, offset);

                    try (RandomAccessFile randomAccessFile = new RandomAccessFile(path.toFile(), "r")) {
                        randomAccessFile.seek(fileSize - CHUNK_SIZE);
                        byte[] lastChunk = new byte[CHUNK_SIZE];
                        int lastRemaining = CHUNK_SIZE;
                        int lastOffset = 0;
                        while (lastRemaining > 0) {
                            int read = randomAccessFile.read(lastChunk, lastOffset, lastRemaining);
                            if (read == -1) {
                                break;
                            }
                            lastOffset += read;
                            lastRemaining -= read;
                        }
                        digest.update(lastChunk, 0, lastOffset);
                    }
                }
            }

            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(fileSize).array());
            StringBuilder builder = new StringBuilder();
            for (byte value : digest.digest()) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception exception) {
            if (exception instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Failed to compute content hash for " + path, exception);
        }
    }
}
