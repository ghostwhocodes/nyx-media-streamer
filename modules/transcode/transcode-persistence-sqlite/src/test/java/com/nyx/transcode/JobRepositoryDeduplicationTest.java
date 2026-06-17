package com.nyx.transcode;

import com.nyx.common.DatabaseResources;
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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobRepositoryDeduplicationTest {
    private static final StreamRepresentationPolicy REPRESENTATION_POLICY = StreamRepresentationPolicy.defaultPolicy();

    @TempDir
    Path tempDir;

    private HikariDataSource dataSource;
    private JobRepository repo;

    @BeforeEach
    void setup() throws IOException {
        Path dbDir = Files.createDirectories(tempDir.resolve("jobs"));
        DatabaseResources resources = JobRepository.createDatabase(dbDir);
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
    void findActiveBySpecReturnsMatchingQueuedJob() {
        repo.create(testJob("queued-1", JobStatus.QUEUED));

        TranscodeJob result = findActive(testJob("test-job", JobStatus.QUEUED));
        assertNotNull(result);
        assertEquals("queued-1", result.id());
        assertEquals(JobStatus.QUEUED, result.status());
    }

    @Test
    void findActiveBySpecReturnsMatchingProbingJob() {
        repo.create(testJob("probing-1", JobStatus.QUEUED));
        repo.updateStatus("probing-1", JobStatus.PROBING);

        TranscodeJob result = findActive(testJob("test-job", JobStatus.QUEUED));
        assertNotNull(result);
        assertEquals("probing-1", result.id());
        assertEquals(JobStatus.PROBING, result.status());
    }

    @Test
    void findActiveBySpecReturnsMatchingTranscodingJob() {
        repo.create(testJob("transcoding-1", JobStatus.QUEUED));
        repo.updateStatus("transcoding-1", JobStatus.PROBING);
        repo.updateStatus("transcoding-1", JobStatus.TRANSCODING);

        TranscodeJob result = findActive(testJob("test-job", JobStatus.QUEUED));
        assertNotNull(result);
        assertEquals("transcoding-1", result.id());
        assertEquals(JobStatus.TRANSCODING, result.status());
    }

    @Test
    void findActiveBySpecReturnsNullWhenNoJobsExist() {
        assertNull(findActive(testJob("test-job", JobStatus.QUEUED)));
    }

    @Test
    void findActiveBySpecReturnsNullWhenInputPathDiffers() {
        repo.create(testJob("j1", JobStatus.QUEUED, "/media/other.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE));

        assertNull(findActive(testJob("test-job", JobStatus.QUEUED)));
    }

    @Test
    void findActiveBySpecReturnsNullWhenProfileDiffers() {
        repo.create(testJob("j1", JobStatus.QUEUED, "/media/test.mkv", "h265_quality", "both", TranscodeExecutionMode.VIDEO_TRANSCODE));

        assertNull(findActive(testJob("test-job", JobStatus.QUEUED)));
    }

    @Test
    void findActiveBySpecReturnsNullWhenRepresentationDiffers() {
        repo.create(testJob("j1", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "dash", TranscodeExecutionMode.VIDEO_TRANSCODE));

        assertNull(findActive(testJob("test-job", JobStatus.QUEUED)));
    }

    @Test
    void findActiveBySpecReturnsNullForCompletedJob() {
        repo.create(testJob("completed-1", JobStatus.QUEUED));
        repo.updateStatus("completed-1", JobStatus.PROBING);
        repo.updateStatus("completed-1", JobStatus.TRANSCODING);
        repo.updateStatus("completed-1", JobStatus.COMPLETED);

        assertNull(findActive(testJob("test-job", JobStatus.QUEUED)), "COMPLETED jobs should not be returned");
    }

    @Test
    void findActiveBySpecReturnsNullForFailedJob() {
        repo.create(testJob("failed-1", JobStatus.QUEUED));
        repo.updateStatus("failed-1", JobStatus.PROBING);
        repo.updateStatus("failed-1", JobStatus.FAILED);

        assertNull(findActive(testJob("test-job", JobStatus.QUEUED)), "FAILED jobs should not be returned");
    }

    @Test
    void findActiveBySpecReturnsNullForCancelledJob() {
        repo.create(testJob("cancelled-1", JobStatus.QUEUED));
        repo.updateStatus("cancelled-1", JobStatus.PROBING);
        repo.updateStatus("cancelled-1", JobStatus.TRANSCODING);
        repo.updateStatus("cancelled-1", JobStatus.CANCELLED);

        assertNull(findActive(testJob("test-job", JobStatus.QUEUED)), "CANCELLED jobs should not be returned");
    }

    @Test
    void differentProfileDoesNotMatchExistingActiveJob() {
        repo.create(testJob("h264-job", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE));

        assertNull(
            findActive(testJob("test-job", JobStatus.QUEUED, "/media/test.mkv", "h265_quality", "both", TranscodeExecutionMode.VIDEO_TRANSCODE)),
            "Different profile should not match"
        );
    }

    @Test
    void differentRepresentationDoesNotMatchExistingActiveJob() {
        repo.create(testJob("dash-job", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "dash", TranscodeExecutionMode.VIDEO_TRANSCODE));

        assertNull(
            findActive(testJob("test-job", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "hls", TranscodeExecutionMode.VIDEO_TRANSCODE)),
            "Different representation should not match"
        );
    }

    @Test
    void exactMatchOnAllThreeFieldsReturnsTheJob() {
        repo.create(testJob("exact-match", JobStatus.QUEUED, "/specific/file.mkv", "av1_balanced", "dash", TranscodeExecutionMode.VIDEO_TRANSCODE));

        TranscodeJob result = findActive(testJob("test-job", JobStatus.QUEUED, "/specific/file.mkv", "av1_balanced", "dash", TranscodeExecutionMode.VIDEO_TRANSCODE));
        assertNotNull(result);
        assertEquals("exact-match", result.id());
    }

    @Test
    void findActiveBySpecReturnsActiveJobWhenTerminalJobsAlsoExistForSameSpec() {
        repo.create(testJob("old-completed", JobStatus.QUEUED));
        repo.updateStatus("old-completed", JobStatus.PROBING);
        repo.updateStatus("old-completed", JobStatus.TRANSCODING);
        repo.updateStatus("old-completed", JobStatus.COMPLETED);

        repo.create(testJob("new-active", JobStatus.QUEUED));

        TranscodeJob result = findActive(testJob("test-job", JobStatus.QUEUED));
        assertNotNull(result);
        assertEquals("new-active", result.id());
    }

    @Test
    void listFilteredWithNoFiltersReturnsAllJobs() {
        repo.create(testJob("j1", JobStatus.QUEUED));
        repo.create(testJob("j2", JobStatus.QUEUED, "/other.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE));

        List<TranscodeJob> results = repo.listFiltered(null, null);
        assertEquals(2, results.size());
    }

    @Test
    void listFilteredByStatusReturnsMatchingJobsOnly() {
        repo.create(testJob("queued-job", JobStatus.QUEUED));
        repo.create(testJob("failed-job", JobStatus.QUEUED));
        repo.updateStatus("failed-job", JobStatus.PROBING);
        repo.updateStatus("failed-job", JobStatus.FAILED);

        List<TranscodeJob> queued = repo.listFiltered(JobStatus.QUEUED, null);
        assertEquals(1, queued.size());
        assertEquals("queued-job", queued.get(0).id());

        List<TranscodeJob> failed = repo.listFiltered(JobStatus.FAILED, null);
        assertEquals(1, failed.size());
        assertEquals("failed-job", failed.get(0).id());
    }

    @Test
    void listFilteredBySinceMinutesReturnsOnlyRecentJobs() {
        repo.create(testJob("recent-job", JobStatus.QUEUED));

        List<TranscodeJob> results = repo.listFiltered(null, 60);
        assertEquals(1, results.size());
        assertEquals("recent-job", results.get(0).id());
    }

    @Test
    void listFilteredByStatusAndSinceMinutesCombinesFilters() {
        repo.create(testJob("match-job", JobStatus.QUEUED));
        repo.create(testJob("other-status", JobStatus.QUEUED));
        repo.updateStatus("other-status", JobStatus.PROBING);

        List<TranscodeJob> results = repo.listFiltered(JobStatus.QUEUED, 60);
        assertEquals(1, results.size());
        assertEquals("match-job", results.get(0).id());
    }

    @Test
    void listFilteredRespectsPagination() {
        for (int index = 0; index < 5; index++) {
            repo.create(testJob("j" + index, JobStatus.QUEUED, "/media/file" + index + ".mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE));
        }

        List<TranscodeJob> page1 = repo.listFiltered(null, null, 2, 0L);
        List<TranscodeJob> page2 = repo.listFiltered(null, null, 2, 2L);

        assertEquals(2, page1.size());
        assertEquals(2, page2.size());

        Set<String> page1Ids = page1.stream().map(TranscodeJob::id).collect(Collectors.toSet());
        assertTrue(page2.stream().map(TranscodeJob::id).noneMatch(page1Ids::contains));
    }

    @Test
    void countFilteredWithNoFiltersReturnsTotalCount() {
        repo.create(testJob("c1", JobStatus.QUEUED));
        repo.create(testJob("c2", JobStatus.QUEUED, "/other.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE));

        assertEquals(2, repo.countFiltered(null, null));
    }

    @Test
    void countFilteredByStatusReturnsMatchingCount() {
        repo.create(testJob("q1", JobStatus.QUEUED));
        repo.create(testJob("q2", JobStatus.QUEUED, "/b.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE));
        repo.create(testJob("f1", JobStatus.QUEUED, "/c.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE));
        repo.updateStatus("f1", JobStatus.PROBING);
        repo.updateStatus("f1", JobStatus.FAILED);

        assertEquals(2, repo.countFiltered(JobStatus.QUEUED, null));
        assertEquals(1, repo.countFiltered(JobStatus.FAILED, null));
        assertEquals(0, repo.countFiltered(JobStatus.COMPLETED, null));
    }

    @Test
    void countFilteredBySinceMinutesReturnsOnlyRecentCount() {
        repo.create(testJob("recent", JobStatus.QUEUED));

        assertEquals(1, repo.countFiltered(null, 60));
    }

    @Test
    void findActiveBySpecWithRetryingStatusReturnsTheJob() {
        repo.create(testJob("retry-job", JobStatus.QUEUED));
        repo.updateStatus("retry-job", JobStatus.PROBING);
        repo.updateStatus("retry-job", JobStatus.TRANSCODING);
        repo.updateStatus("retry-job", JobStatus.RETRYING);

        TranscodeJob result = findActive(testJob("test-job", JobStatus.QUEUED));
        assertNotNull(result, "RETRYING jobs should block duplicate submission");
        assertEquals("retry-job", result.id());
        assertEquals(JobStatus.RETRYING, result.status());
    }

    @Test
    void differentExecutionModeDoesNotMatchExistingActiveJob() {
        repo.create(testJob("video-job", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE));

        assertNull(
            findActive(testJob("test-job", JobStatus.QUEUED, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.REMUX)),
            "Execution mode must participate in duplicate-job identity"
        );
    }

    private TranscodeJob findActive(TranscodeJob spec) {
        return repo.findActiveBySpecKey(spec.specKey());
    }

    private TranscodeJob testJob(String id, JobStatus status) {
        return testJob(id, status, "/media/test.mkv", "h264_fast", "both", TranscodeExecutionMode.VIDEO_TRANSCODE);
    }

    private TranscodeJob testJob(
        String id,
        JobStatus status,
        String inputPath,
        String profile,
        String format,
        TranscodeExecutionMode executionMode
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
            null,
            0L
        );
    }
}
