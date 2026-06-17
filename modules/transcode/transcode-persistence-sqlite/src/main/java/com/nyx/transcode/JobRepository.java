package com.nyx.transcode;

import static com.nyx.common.SqliteWriteTransactions.inTransactionUnchecked;
import static com.nyx.common.SqliteWriteTransactions.withHandleUnchecked;
import static com.nyx.transcode.contracts.TranscodeContracts.canTransitionTo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.common.DatabaseFactory;
import com.nyx.common.DatabaseResources;
import com.nyx.common.ErrorCode;
import com.nyx.common.InvalidJobTransitionException;
import com.nyx.common.NyxException;
import com.nyx.config.DatabaseConfig;
import com.nyx.json.NyxJson;
import com.nyx.stream.representation.contracts.StreamRepresentationPolicy;
import com.nyx.transcode.contracts.JobStatus;
import com.nyx.transcode.contracts.TranscodeExecutionMode;
import com.nyx.transcode.contracts.TranscodeJob;
import com.nyx.transcode.contracts.TranscodeJobStore;
import com.nyx.transcode.contracts.TranscodeRepresentation;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JobRepository implements TranscodeJobStore {
    private static final String ACTIVE_STATUS_SQL = "'QUEUED','PROBING','TRANSCODING','RETRYING'";
    private static final String STORAGE_STATUS_SQL = "'TRANSCODING','RETRYING','COMPLETED'";
    private static final TypeReference<List<TranscodeRepresentation>> REPRESENTATIONS_TYPE =
        new TypeReference<>() {};
    private static final StreamRepresentationPolicy REPRESENTATION_POLICY = StreamRepresentationPolicy.defaultPolicy();

    private final Jdbi jdbi;
    private final Clock clock;
    private final Logger logger = LoggerFactory.getLogger(JobRepository.class);
    private final ObjectMapper json = NyxJson.newMapper();

    public JobRepository(Jdbi jdbi) {
        this(jdbi, Clock.systemUTC());
    }

    public JobRepository(Jdbi jdbi, Clock clock) {
        this.jdbi = jdbi;
        this.clock = clock;
    }

    @Override
    public TranscodeJob create(TranscodeJob job) {
        return inTransactionUnchecked(jdbi, handle -> insertJobRow(handle, job));
    }

    public TranscodeJob createWithQuotaCheck(TranscodeJob job, int maxConcurrent) {
        return createWithQuotaCheck(job, maxConcurrent, null);
    }

    @Override
    public TranscodeJob createWithQuotaCheck(TranscodeJob job, int maxConcurrent, Long maxStorageBytes) {
        return inTransactionUnchecked(jdbi, handle -> {
            String owner = job.getOwner();
            if (owner == null) {
                throw new IllegalStateException("createWithQuotaCheck requires a non-null owner");
            }

            Integer activeCount = handle.createQuery(String.format("""
                    SELECT COUNT(*)
                    FROM transcode_jobs
                    WHERE owner = :owner AND status IN (%s)
                    """, ACTIVE_STATUS_SQL))
                .bind("owner", owner)
                .mapTo(Integer.class)
                .one();

            if (activeCount >= maxConcurrent) {
                return sneakyThrow(
                    new NyxException(
                        ErrorCode.QUOTA_EXCEEDED,
                        "Per-user concurrent job limit exceeded",
                        Map.of(),
                        null
                    )
                );
            }

            if (maxStorageBytes != null) {
                Long usedBytes = handle.createQuery(String.format("""
                        SELECT COALESCE(SUM(output_size_bytes), 0)
                        FROM transcode_jobs
                        WHERE owner = :owner AND status IN (%s)
                        """, STORAGE_STATUS_SQL))
                    .bind("owner", owner)
                    .mapTo(Long.class)
                    .one();
                if (usedBytes >= maxStorageBytes) {
                    return sneakyThrow(
                        new NyxException(
                            ErrorCode.QUOTA_EXCEEDED,
                            "Per-user storage quota exceeded",
                            Map.of(),
                            null
                        )
                    );
                }
            }

            return insertJobRow(handle, job);
        });
    }

    private TranscodeJob insertJobRow(Handle handle, TranscodeJob job) {
        Instant now = Instant.now(clock);
        handle.createUpdate("""
                INSERT INTO transcode_jobs(
                    id, status, input_path, profile, representation, representations,
                    execution_mode, spec_key, segments_produced, retry_count,
                    stderr_initial, stderr_fallback, created_at, updated_at,
                    completed_at, batch_id, owner, output_size_bytes
                ) VALUES (
                    :id, :status, :inputPath, :profile, :representation, :representations,
                    :executionMode, :specKey, :segmentsProduced, :retryCount,
                    :stderrInitial, :stderrFallback, :createdAt, :updatedAt,
                    :completedAt, :batchId, :owner, :outputSizeBytes
                )
                """)
            .bind("id", job.getId())
            .bind("status", job.getStatus().name())
            .bind("inputPath", job.getInputPath())
            .bind("profile", job.getProfile())
            .bind("representation", REPRESENTATION_POLICY.storageToken(job.getRepresentation()).value())
            .bind("representations", writeJson(job.getRepresentations()))
            .bind("executionMode", job.getExecutionMode().name())
            .bind("specKey", job.getSpecKey())
            .bind("segmentsProduced", job.getSegmentsProduced())
            .bind("retryCount", job.getRetryCount())
            .bind("stderrInitial", job.getStderrInitial())
            .bind("stderrFallback", job.getStderrFallback())
            .bind("createdAt", now.toString())
            .bind("updatedAt", now.toString())
            .bind("completedAt", job.getCompletedAt() == null ? null : job.getCompletedAt().toString())
            .bind("batchId", job.getBatchId())
            .bind("owner", job.getOwner())
            .bind("outputSizeBytes", job.getOutputSizeBytes())
            .execute();
        return new TranscodeJob(
            job.getId(),
            job.getStatus(),
            job.getInputPath(),
            job.getProfile(),
            job.getRepresentation(),
            job.getRepresentations(),
            job.getExecutionMode(),
            job.getSpecKey(),
            job.getSegmentsProduced(),
            job.getRetryCount(),
            job.getStderrInitial(),
            job.getStderrFallback(),
            now,
            now,
            job.getCompletedAt(),
            job.getManifestUrl(),
            job.getHlsUrl(),
            job.getProgressUrl(),
            job.getBatchId(),
            job.getOwner(),
            job.getOutputSizeBytes()
        );
    }

    @Override
    public TranscodeJob getById(String id) {
        return withHandleUnchecked(jdbi, handle -> getById(handle, id));
    }

    @Override
    public void updateStatus(String id, JobStatus newStatus) {
        inTransactionUnchecked(jdbi, handle -> {
            TranscodeJob current = getById(handle, id);
            if (current == null) {
                throw new IllegalArgumentException("Job not found: " + id);
            }
            if (!canTransitionTo(current.getStatus(), newStatus)) {
                sneakyThrow(new InvalidJobTransitionException(current.getStatus(), newStatus));
            }

            Instant now = Instant.now(clock);
            handle.createUpdate("""
                    UPDATE transcode_jobs
                    SET status = :status,
                        updated_at = :updatedAt,
                        completed_at = :completedAt
                    WHERE id = :id
                    """)
                .bind("status", newStatus.name())
                .bind("updatedAt", now.toString())
                .bind(
                    "completedAt",
                    switch (newStatus) {
                        case COMPLETED, FAILED, CANCELLED -> now.toString();
                        default -> current.getCompletedAt() == null ? null : current.getCompletedAt().toString();
                    }
                )
                .bind("id", id)
                .execute();
            return null;
        });
    }

    @Override
    public void updateProgress(String id, int segmentsProduced) {
        inTransactionUnchecked(jdbi, handle -> {
            handle.createUpdate("""
                    UPDATE transcode_jobs
                    SET segments_produced = :segmentsProduced,
                        updated_at = :updatedAt
                    WHERE id = :id
                    """)
                .bind("segmentsProduced", segmentsProduced)
                .bind("updatedAt", Instant.now(clock).toString())
                .bind("id", id)
                .execute();
            return null;
        });
    }

    @Override
    public void updateRetryCount(String id, int count) {
        inTransactionUnchecked(jdbi, handle -> {
            handle.createUpdate("""
                    UPDATE transcode_jobs
                    SET retry_count = :retryCount,
                        updated_at = :updatedAt
                    WHERE id = :id
                    """)
                .bind("retryCount", count)
                .bind("updatedAt", Instant.now(clock).toString())
                .bind("id", id)
                .execute();
            return null;
        });
    }

    @Override
    public void storeStderr(String id, String initial, String fallback) {
        inTransactionUnchecked(jdbi, handle -> {
            StringBuilder sql = new StringBuilder("UPDATE transcode_jobs SET updated_at = :updatedAt");
            if (initial != null) {
                sql.append(", stderr_initial = :stderrInitial");
            }
            if (fallback != null) {
                sql.append(", stderr_fallback = :stderrFallback");
            }
            sql.append(" WHERE id = :id");

            var update = handle.createUpdate(sql.toString())
                .bind("updatedAt", Instant.now(clock).toString())
                .bind("id", id);
            if (initial != null) {
                update.bind("stderrInitial", initial);
            }
            if (fallback != null) {
                update.bind("stderrFallback", fallback);
            }
            update.execute();
            return null;
        });
    }

    @Override
    public TranscodeJob findActiveBySpecKey(String specKey) {
        return withHandleUnchecked(jdbi, handle -> handle.createQuery(String.format("""
                SELECT *
                FROM transcode_jobs
                WHERE spec_key = :specKey AND status IN (%s)
                LIMIT 1
                """, ACTIVE_STATUS_SQL))
            .bind("specKey", specKey)
            .map((resultSet, ctx) -> toTranscodeJob(resultSet))
            .findOne()
            .orElse(null));
    }

    public List<TranscodeJob> listFiltered(JobStatus status, Integer sinceMinutes) {
        return listFiltered(status, sinceMinutes, 50, 0L, null);
    }

    public List<TranscodeJob> listFiltered(JobStatus status, Integer sinceMinutes, int limit, long offset) {
        return listFiltered(status, sinceMinutes, limit, offset, null);
    }

    @Override
    public List<TranscodeJob> listFiltered(JobStatus status, Integer sinceMinutes, int limit, long offset, String owner) {
        return withHandleUnchecked(jdbi, handle -> {
            Instant sinceInstant =
                sinceMinutes == null ? null : Instant.now(clock).minus(Duration.ofMinutes(sinceMinutes.longValue()));
            Query query = buildFilteredQuery(handle, status, sinceInstant, owner, false)
                .bind("limit", limit)
                .bind("offset", offset);
            return query.map((resultSet, ctx) -> toTranscodeJob(resultSet)).list();
        });
    }

    public int countFiltered(JobStatus status, Integer sinceMinutes) {
        return countFiltered(status, sinceMinutes, null);
    }

    @Override
    public int countFiltered(JobStatus status, Integer sinceMinutes, String owner) {
        return withHandleUnchecked(jdbi, handle -> {
            Instant sinceInstant =
                sinceMinutes == null ? null : Instant.now(clock).minus(Duration.ofMinutes(sinceMinutes.longValue()));
            return buildFilteredQuery(handle, status, sinceInstant, owner, true)
                .mapTo(Integer.class)
                .one();
        });
    }

    private Query buildFilteredQuery(
        Handle handle,
        JobStatus status,
        Instant sinceInstant,
        String owner,
        boolean countOnly
    ) {
        StringBuilder sql = new StringBuilder(countOnly
            ? "SELECT COUNT(*) FROM transcode_jobs WHERE 1 = 1"
            : "SELECT * FROM transcode_jobs WHERE 1 = 1");
        if (status != null) {
            sql.append(" AND status = :status");
        }
        if (sinceInstant != null) {
            sql.append(" AND updated_at >= :sinceInstant");
        }
        if (owner != null) {
            sql.append(" AND owner = :owner");
        }
        if (!countOnly) {
            sql.append(" ORDER BY updated_at DESC LIMIT :limit OFFSET :offset");
        }

        Query query = handle.createQuery(sql.toString());
        if (status != null) {
            query.bind("status", status.name());
        }
        if (sinceInstant != null) {
            query.bind("sinceInstant", sinceInstant.toString());
        }
        if (owner != null) {
            query.bind("owner", owner);
        }
        return query;
    }

    @Override
    public List<TranscodeJob> listActive() {
        return withHandleUnchecked(jdbi, handle -> handle
            .createQuery("SELECT * FROM transcode_jobs WHERE status IN (" + ACTIVE_STATUS_SQL + ")")
            .map((resultSet, ctx) -> toTranscodeJob(resultSet))
            .list());
    }

    @Override
    public List<TranscodeJob> listByBatchId(String batchId) {
        return withHandleUnchecked(jdbi, handle -> handle.createQuery("""
                SELECT *
                FROM transcode_jobs
                WHERE batch_id = :batchId
                ORDER BY created_at ASC
                """)
            .bind("batchId", batchId)
            .map((resultSet, ctx) -> toTranscodeJob(resultSet))
            .list());
    }

    @Override
    public int countActiveByOwner(String ownerId) {
        return withHandleUnchecked(jdbi, handle -> handle.createQuery(String.format("""
                SELECT COUNT(*)
                FROM transcode_jobs
                WHERE owner = :owner AND status IN (%s)
                """, ACTIVE_STATUS_SQL))
            .bind("owner", ownerId)
            .mapTo(Integer.class)
            .one());
    }

    @Override
    public void updateOutputSize(String id, long sizeBytes) {
        inTransactionUnchecked(jdbi, handle -> {
            handle.createUpdate("""
                    UPDATE transcode_jobs
                    SET output_size_bytes = :sizeBytes,
                        updated_at = :updatedAt
                    WHERE id = :id
                    """)
                .bind("sizeBytes", sizeBytes)
                .bind("updatedAt", Instant.now(clock).toString())
                .bind("id", id)
                .execute();
            return null;
        });
    }

    @Override
    public long sumStorageByOwner(String ownerId) {
        return withHandleUnchecked(jdbi, handle -> handle.createQuery(String.format("""
                SELECT COALESCE(SUM(output_size_bytes), 0)
                FROM transcode_jobs
                WHERE owner = :owner AND status IN (%s)
                """, STORAGE_STATUS_SQL))
            .bind("owner", ownerId)
            .mapTo(Long.class)
            .one());
    }

    public int countAll() {
        return countAll(null);
    }

    @Override
    public int countAll(String owner) {
        return withHandleUnchecked(jdbi, handle -> {
            Query query = owner == null
                ? handle.createQuery("SELECT COUNT(*) FROM transcode_jobs")
                : handle.createQuery("SELECT COUNT(*) FROM transcode_jobs WHERE owner = :owner").bind("owner", owner);
            return query.mapTo(Integer.class).one();
        });
    }

    public List<TranscodeJob> listRecent() {
        return listRecent(50, 0L, null);
    }

    public List<TranscodeJob> listRecent(int limit) {
        return listRecent(limit, 0L, null);
    }

    public List<TranscodeJob> listRecent(int limit, long offset) {
        return listRecent(limit, offset, null);
    }

    @Override
    public List<TranscodeJob> listRecent(int limit, long offset, String owner) {
        return withHandleUnchecked(jdbi, handle -> {
            Query query;
            if (owner != null) {
                query = handle.createQuery("""
                        SELECT *
                        FROM transcode_jobs
                        WHERE owner = :owner
                        ORDER BY updated_at DESC
                        LIMIT :limit OFFSET :offset
                        """)
                    .bind("owner", owner);
            } else {
                query = handle.createQuery("""
                        SELECT *
                        FROM transcode_jobs
                        ORDER BY updated_at DESC
                        LIMIT :limit OFFSET :offset
                        """);
            }
            return query.bind("limit", limit)
                .bind("offset", offset)
                .map((resultSet, ctx) -> toTranscodeJob(resultSet))
                .list();
        });
    }

    private TranscodeJob getById(Handle handle, String id) {
        return handle.createQuery("SELECT * FROM transcode_jobs WHERE id = :id")
            .bind("id", id)
            .map((resultSet, ctx) -> toTranscodeJob(resultSet))
            .findOne()
            .orElse(null);
    }

    private TranscodeJob toTranscodeJob(ResultSet resultSet) {
        List<TranscodeRepresentation> representations;
        try {
            representations = readRepresentations(resultSet.getString("representations"));
        } catch (Exception exception) {
            logger.debug(
                "Failed to parse representations JSON for job {}: {}",
                getStringUnchecked(resultSet, "id"),
                exception.getMessage()
            );
            representations = List.of();
        }

        return new TranscodeJob(
            getStringUnchecked(resultSet, "id"),
            JobStatus.valueOf(getStringUnchecked(resultSet, "status")),
            getStringUnchecked(resultSet, "input_path"),
            getStringUnchecked(resultSet, "profile"),
            REPRESENTATION_POLICY.fromStorageToken(getStringUnchecked(resultSet, "representation")),
            representations,
            TranscodeExecutionMode.valueOf(getStringUnchecked(resultSet, "execution_mode")),
            getStringUnchecked(resultSet, "spec_key"),
            getIntUnchecked(resultSet, "segments_produced"),
            getIntUnchecked(resultSet, "retry_count"),
            getStringUnchecked(resultSet, "stderr_initial"),
            getStringUnchecked(resultSet, "stderr_fallback"),
            parseInstant(getStringUnchecked(resultSet, "created_at")),
            parseInstant(getStringUnchecked(resultSet, "updated_at")),
            parseInstant(getStringUnchecked(resultSet, "completed_at")),
            null,
            null,
            null,
            getStringUnchecked(resultSet, "batch_id"),
            getStringUnchecked(resultSet, "owner"),
            getLongUnchecked(resultSet, "output_size_bytes")
        );
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new RuntimeException("Failed to serialize transcode representations", exception);
        }
    }

    private List<TranscodeRepresentation> readRepresentations(String value) throws Exception {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return json.readValue(value, REPRESENTATIONS_TYPE);
    }

    private Instant parseInstant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    private String getStringUnchecked(ResultSet resultSet, String column) {
        try {
            return resultSet.getString(column);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private int getIntUnchecked(ResultSet resultSet, String column) {
        try {
            return resultSet.getInt(column);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private long getLongUnchecked(ResultSet resultSet, String column) {
        try {
            return resultSet.getLong(column);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    public static DatabaseResources createDatabase(Path dbDir) {
        return createDatabase(dbDir, new DatabaseConfig(dbDir, 1, 600_000L, 1_800_000L));
    }

    public static DatabaseResources createDatabase(Path dbDir, DatabaseConfig dbConfig) {
        return DatabaseFactory.create(dbDir, "jobs", dbConfig);
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }
}
