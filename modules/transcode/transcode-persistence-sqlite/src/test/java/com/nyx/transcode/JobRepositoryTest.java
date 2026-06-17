package com.nyx.transcode;

import com.nyx.common.DatabaseResources;
import com.nyx.common.InvalidJobTransitionException;
import com.nyx.common.NyxException;
import com.nyx.ffmpeg.model.*;
import com.nyx.stream.representation.contracts.StreamRepresentationPolicy;
import com.nyx.transcode.contracts.JobStatus;
import com.nyx.transcode.contracts.TranscodeExecutionMode;
import com.nyx.transcode.contracts.TranscodeJob;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobRepositoryTest {
    private static final StreamRepresentationPolicy REPRESENTATION_POLICY = StreamRepresentationPolicy.defaultPolicy();

    @TempDir
    Path tempDir;

    private DatabaseResources resources;
    private HikariDataSource dataSource;
    private JobRepository repo;

    @BeforeEach
    void setup() throws IOException {
        Path dbDir = Files.createDirectories(tempDir.resolve("jobs"));
        resources = JobRepository.createDatabase(dbDir);
        dataSource = resources.getDataSource();
        repo = new JobRepository(resources.getJdbi());
    }

    @AfterEach
    void teardown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void createAndRetrieveJob() {
        TranscodeJob job = repo.create(testJob());
        assertEquals("test-job-1", job.id());
        assertNotNull(job.createdAt());

        TranscodeJob retrieved = repo.getById("test-job-1");
        assertNotNull(retrieved);
        assertEquals(JobStatus.QUEUED, retrieved.status());
        assertEquals("/media/test.mkv", retrieved.inputPath());
    }

    @Test
    void updateStatusWithValidTransition() {
        repo.create(testJob());

        repo.updateStatus("test-job-1", JobStatus.PROBING);
        TranscodeJob updated = repo.getById("test-job-1");
        assertEquals(JobStatus.PROBING, updated == null ? null : updated.status());
    }

    @Test
    void updateStatusWithInvalidTransitionThrows() {
        repo.create(testJob());

        assertThrows(InvalidJobTransitionException.class, () -> repo.updateStatus("test-job-1", JobStatus.COMPLETED));
    }

    @Test
    void updateProgress() {
        repo.create(testJob());

        repo.updateProgress("test-job-1", 42);
        TranscodeJob updated = repo.getById("test-job-1");
        assertEquals(42, updated == null ? null : updated.segmentsProduced());
    }

    @Test
    void storeStderr() {
        repo.create(testJob());

        repo.storeStderr("test-job-1", "error output 1", null);
        TranscodeJob updated = repo.getById("test-job-1");
        assertEquals("error output 1", updated == null ? null : updated.stderrInitial());
        assertNull(updated == null ? null : updated.stderrFallback());

        repo.storeStderr("test-job-1", null, "error output 2");
        updated = repo.getById("test-job-1");
        assertEquals("error output 1", updated == null ? null : updated.stderrInitial());
        assertEquals("error output 2", updated == null ? null : updated.stderrFallback());
    }

    @Test
    void listActiveJobs() {
        repo.create(testJob("job1", JobStatus.QUEUED));
        repo.create(testJob("job2", JobStatus.QUEUED));

        repo.updateStatus("job1", JobStatus.PROBING);
        repo.updateStatus("job1", JobStatus.TRANSCODING);
        repo.updateStatus("job1", JobStatus.COMPLETED);

        List<TranscodeJob> active = repo.listActive();
        assertEquals(1, active.size());
        assertEquals("job2", active.get(0).id());
    }

    @Test
    void listRecentJobs() {
        for (int index = 0; index < 5; index++) {
            repo.create(testJob("job" + index, JobStatus.QUEUED));
        }

        List<TranscodeJob> recent = repo.listRecent(3);
        assertEquals(3, recent.size());
    }

    @Test
    void completedAtIsSetForTerminalStates() {
        repo.create(testJob());
        repo.updateStatus("test-job-1", JobStatus.PROBING);
        repo.updateStatus("test-job-1", JobStatus.FAILED);

        TranscodeJob job = repo.getById("test-job-1");
        assertNotNull(job == null ? null : job.completedAt());
    }

    @Test
    void newJobHasRetryCountZeroByDefault() {
        TranscodeJob job = repo.create(testJob());
        assertEquals(0, job.retryCount());

        TranscodeJob retrieved = repo.getById(job.id());
        assertEquals(0, retrieved == null ? null : retrieved.retryCount());
    }

    @Test
    void updateRetryCountPersistsTheNewValue() {
        repo.create(testJob());

        repo.updateRetryCount("test-job-1", 2);
        TranscodeJob updated = repo.getById("test-job-1");
        assertEquals(2, updated == null ? null : updated.retryCount());
    }

    @Test
    void updateRetryCountUpdatesTheUpdatedAtTimestamp() throws InterruptedException {
        repo.create(testJob());
        TranscodeJob beforeJob = repo.getById("test-job-1");
        assertNotNull(beforeJob);

        Thread.sleep(5L);
        repo.updateRetryCount("test-job-1", 1);

        TranscodeJob afterJob = repo.getById("test-job-1");
        assertNotNull(afterJob);
        assertFalse(afterJob.updatedAt().isBefore(beforeJob.updatedAt()));
    }

    @Test
    void createWithOwnerPersistsOwnerField() {
        TranscodeJob created = repo.create(testJob("owner-j1", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE, "alice"));
        assertEquals("alice", created.owner());

        TranscodeJob retrieved = repo.getById("owner-j1");
        assertEquals("alice", retrieved == null ? null : retrieved.owner());
    }

    @Test
    void createWithoutOwnerPersistsNullOwner() {
        TranscodeJob created = repo.create(testJob("owner-j2", JobStatus.QUEUED));
        assertNull(created.owner());

        TranscodeJob retrieved = repo.getById("owner-j2");
        assertNull(retrieved == null ? null : retrieved.owner());
    }

    @Test
    void countActiveByOwnerCountsActiveJobsForSpecificOwner() {
        repo.create(testJob("a1", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE, "alice"));
        repo.create(testJob("a2", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE, "alice"));
        repo.create(testJob("b1", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE, "bob"));

        assertEquals(2, repo.countActiveByOwner("alice"));
        assertEquals(1, repo.countActiveByOwner("bob"));
    }

    @Test
    void countActiveByOwnerIgnoresCompletedFailedAndCancelledJobs() {
        repo.create(testJob("t1", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE, "alice"));
        repo.updateStatus("t1", JobStatus.PROBING);
        repo.updateStatus("t1", JobStatus.TRANSCODING);
        repo.updateStatus("t1", JobStatus.COMPLETED);

        repo.create(testJob("t2", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE, "alice"));
        repo.updateStatus("t2", JobStatus.PROBING);
        repo.updateStatus("t2", JobStatus.FAILED);

        repo.create(testJob("t3", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE, "alice"));

        assertEquals(1, repo.countActiveByOwner("alice"));
    }

    @Test
    void countActiveByOwnerReturnsZeroForOwnerWithNoJobs() {
        assertEquals(0, repo.countActiveByOwner("nobody"));
    }

    @Test
    void createWithQuotaCheckInsertsWhenUnderLimit() {
        repo.create(testJob("q1", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE, "alice"));
        TranscodeJob created = repo.createWithQuotaCheck(
            testJob("q2", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE, "alice"),
            2
        );
        assertEquals("q2", created.id());
        assertEquals("alice", created.owner());
    }

    @Test
    void createWithQuotaCheckThrowsWhenAtLimit() {
        repo.create(testJob("q1", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE, "alice"));
        repo.create(testJob("q2", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE, "alice"));

        NyxException exception = assertThrows(
            NyxException.class,
            () -> repo.createWithQuotaCheck(
                testJob("q3", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE, "alice"),
                2
            )
        );
        assertEquals("QUOTA_EXCEEDED", exception.getErrorCode().name());
    }

    @Test
    void createWithQuotaCheckIgnoresJobsFromOtherOwners() {
        repo.create(testJob("bob1", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE, "bob"));
        repo.create(testJob("bob2", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE, "bob"));

        TranscodeJob created = repo.createWithQuotaCheck(
            testJob("alice1", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE, "alice"),
            1
        );
        assertEquals("alice1", created.id());
    }

    @Test
    void createWithQuotaCheckIgnoresTerminalJobs() {
        repo.create(testJob("done1", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE, "alice"));
        repo.updateStatus("done1", JobStatus.PROBING);
        repo.updateStatus("done1", JobStatus.TRANSCODING);
        repo.updateStatus("done1", JobStatus.COMPLETED);

        repo.create(testJob("done2", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE, "alice"));
        repo.updateStatus("done2", JobStatus.PROBING);
        repo.updateStatus("done2", JobStatus.FAILED);

        TranscodeJob created = repo.createWithQuotaCheck(
            testJob("new1", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE, "alice"),
            1
        );
        assertEquals("new1", created.id());
    }

    @Test
    void createWithQuotaCheckThrowsWhenOwnerIsNull() {
        assertThrows(IllegalStateException.class, () -> repo.createWithQuotaCheck(testJob("no-owner", JobStatus.QUEUED), 10));
    }

    @Test
    void updateOutputSizePersistsTheValue() {
        repo.create(testJob("size-j1", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE, "alice"));
        repo.updateOutputSize("size-j1", 1_048_576L);

        TranscodeJob retrieved = repo.getById("size-j1");
        assertEquals(1_048_576L, retrieved == null ? null : retrieved.outputSizeBytes());
    }

    @Test
    void sumStorageByOwnerSumsAcrossActiveAndCompletedJobs() {
        repo.create(testJob("s1", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE, "alice"));
        repo.updateStatus("s1", JobStatus.PROBING);
        repo.updateStatus("s1", JobStatus.TRANSCODING);
        repo.updateOutputSize("s1", 100L);

        repo.create(testJob("s2", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE, "alice"));
        repo.updateStatus("s2", JobStatus.PROBING);
        repo.updateStatus("s2", JobStatus.TRANSCODING);
        repo.updateStatus("s2", JobStatus.COMPLETED);
        repo.updateOutputSize("s2", 200L);

        assertEquals(300L, repo.sumStorageByOwner("alice"));
    }

    @Test
    void sumStorageByOwnerExcludesQueuedAndProbingJobs() {
        repo.create(testJob("sq-q", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE, "alice"));
        repo.updateOutputSize("sq-q", 100L);

        repo.create(testJob("sq-p", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE, "alice"));
        repo.updateStatus("sq-p", JobStatus.PROBING);
        repo.updateOutputSize("sq-p", 200L);

        assertEquals(0L, repo.sumStorageByOwner("alice"));
    }

    @Test
    void sumStorageByOwnerExcludesFailedAndCancelledJobs() {
        repo.create(testJob("sf1", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE, "alice"));
        repo.updateStatus("sf1", JobStatus.PROBING);
        repo.updateStatus("sf1", JobStatus.FAILED);
        repo.updateOutputSize("sf1", 999L);

        repo.create(testJob("sf2", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE, "alice"));
        repo.updateStatus("sf2", JobStatus.PROBING);
        repo.updateStatus("sf2", JobStatus.TRANSCODING);
        repo.updateOutputSize("sf2", 50L);

        assertEquals(50L, repo.sumStorageByOwner("alice"));
    }

    @Test
    void sumStorageByOwnerReturnsZeroForOwnerWithNoJobs() {
        assertEquals(0L, repo.sumStorageByOwner("nobody"));
    }

    @Test
    void createWithQuotaCheckWithStorageLimitAllowsWhenUnder() {
        repo.create(testJob("sq1", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE, "alice"));
        repo.updateStatus("sq1", JobStatus.PROBING);
        repo.updateStatus("sq1", JobStatus.TRANSCODING);
        repo.updateStatus("sq1", JobStatus.COMPLETED);
        repo.updateOutputSize("sq1", 500L);

        TranscodeJob created = repo.createWithQuotaCheck(
            testJob("sq2", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE, "alice"),
            10,
            1000L
        );
        assertEquals("sq2", created.id());
    }

    @Test
    void createWithQuotaCheckWithStorageLimitRejectsWhenExceeded() {
        repo.create(testJob("sq3", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE, "alice"));
        repo.updateStatus("sq3", JobStatus.PROBING);
        repo.updateStatus("sq3", JobStatus.TRANSCODING);
        repo.updateStatus("sq3", JobStatus.COMPLETED);
        repo.updateOutputSize("sq3", 1000L);

        NyxException exception = assertThrows(
            NyxException.class,
            () -> repo.createWithQuotaCheck(
                testJob("sq4", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE, "alice"),
                10,
                1000L
            )
        );
        assertEquals("QUOTA_EXCEEDED", exception.getErrorCode().name());
        assertTrue(exception.getMessage().contains("storage"));
    }

    @Test
    void toTranscodeJobReturnsEmptyListWhenRepresentationsJsonIsInvalid() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("""
                INSERT INTO transcode_jobs (id, status, input_path, profile, representation,
                    representations, segments_produced, retry_count,
                    created_at, updated_at, output_size_bytes)
                VALUES ('bad-reps-1', 'QUEUED', '/test/file.mkv', 'h264_fast', 'sr_hls_dash_fmp4',
                    'NOT VALID JSON!!!', 0, 0,
                    '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z', 0)
                """.stripIndent());
        }

        TranscodeJob job = repo.getById("bad-reps-1");
        assertNotNull(job, "Job should be found even with bad representations JSON");
        assertEquals("bad-reps-1", job.id());
        assertTrue(job.representations().isEmpty(), "Representations should be empty when JSON is invalid");
    }

    @Test
    void listRecentHandlesInvalidRepresentationsJsonGracefully() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("""
                INSERT INTO transcode_jobs (id, status, input_path, profile, representation,
                    representations, segments_produced, retry_count,
                    created_at, updated_at, output_size_bytes)
                VALUES ('good-1', 'QUEUED', '/test/good.mkv', 'h264_fast', 'sr_hls_dash_fmp4',
                    '[{"width":1920,"height":1080,"bitrateKbps":5000}]', 0, 0,
                    '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z', 0)
                """.stripIndent());
            statement.execute("""
                INSERT INTO transcode_jobs (id, status, input_path, profile, representation,
                    representations, segments_produced, retry_count,
                    created_at, updated_at, output_size_bytes)
                VALUES ('bad-2', 'QUEUED', '/test/bad.mkv', 'h264_fast', 'sr_hls_dash_fmp4',
                    '{corrupt}', 0, 0,
                    '2025-01-02T00:00:00Z', '2025-01-02T00:00:00Z', 0)
                """.stripIndent());
        }

        List<TranscodeJob> jobs = repo.listRecent();
        assertEquals(2, jobs.size());

        TranscodeJob goodJob = jobs.stream().filter(job -> "good-1".equals(job.id())).findFirst().orElse(null);
        assertNotNull(goodJob);
        assertEquals(1, goodJob.representations().size());

        TranscodeJob badJob = jobs.stream().filter(job -> "bad-2".equals(job.id())).findFirst().orElse(null);
        assertNotNull(badJob);
        assertTrue(badJob.representations().isEmpty());
    }

    @Test
    void statusAndUpdatedAtIndexesArePresent() throws Exception {
        List<String> indexNames = new ArrayList<>();
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery("PRAGMA index_list(transcode_jobs)")) {
            while (resultSet.next()) {
                indexNames.add(resultSet.getString("name"));
            }
        }

        assertTrue(indexNames.stream().anyMatch(name -> name.contains("status")),
            "Expected idx_jobs_status to be present, found: " + indexNames);
        assertTrue(indexNames.stream().anyMatch(name -> name.contains("updated")),
            "Expected idx_jobs_updated to be present, found: " + indexNames);
    }

    private TranscodeJob testJob() {
        return testJob("test-job-1", JobStatus.QUEUED);
    }

    private TranscodeJob testJob(String id, JobStatus status) {
        return testJob(id, status, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE, null);
    }

    private TranscodeJob testJob(
        String id,
        JobStatus status,
        String inputPath,
        String profile,
        String format,
        TranscodeExecutionMode executionMode,
        String owner
    ) {
        return new TranscodeJob(
            id,
            status,
            inputPath,
            profile,
            REPRESENTATION_POLICY.normalizeExternalName(format),
            List.of(),
            executionMode,
            null,
            0,
            0,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            owner,
            0L
        );
    }
}
