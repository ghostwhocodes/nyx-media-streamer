package com.nyx.transcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.common.DatabaseResources;
import com.nyx.common.PathSecurity;
import com.nyx.config.DatabaseConfig;
import com.nyx.config.FfmpegConfig;
import com.nyx.config.MediaRootConfig;
import com.nyx.config.ServerConfig;
import com.nyx.config.TranscodeConfig;
import com.nyx.ffmpeg.ProbeService;
import com.nyx.stream.representation.contracts.StreamRepresentation;
import com.nyx.transcode.contracts.JobStatus;
import com.nyx.transcode.contracts.TranscodeJob;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TranscodeJobListingTest {
    private final List<HikariDataSource> dataSources = new ArrayList<>();
    private final List<TranscodeService> managedServices = new ArrayList<>();

    private Path tempDir;
    private Path mediaDir;
    private int dbCounter;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("nyx-listing-test");
        mediaDir = Files.createDirectories(tempDir.resolve("media"));
        dbCounter = 0;
    }

    @AfterEach
    void tearDown() throws Exception {
        for (int index = managedServices.size() - 1; index >= 0; index--) {
            managedServices.get(index).shutdown();
        }
        managedServices.clear();
        dataSources.forEach(HikariDataSource::close);
        dataSources.clear();
        deleteRecursively(tempDir);
    }

    @Test
    void listJobsReturnsPaginatedResponseWithCorrectMetadata() {
        ServiceResources resources = createService();
        for (int index = 1; index <= 5; index++) {
            resources.jobRepository().create(job("pag-" + index, "/file" + index + ".mkv"));
        }

        var listing = resources.service().listJobs(1, 2);
        assertEquals(2, listing.jobs().size());
        assertEquals(5, listing.total());
        assertEquals(1, listing.page());
        assertEquals(2, listing.limit());
    }

    @Test
    void listJobsPage2ReturnsCorrectOffset() {
        ServiceResources resources = createService();
        for (int index = 1; index <= 5; index++) {
            resources.jobRepository().create(job("off-" + index, "/file" + index + ".mkv"));
        }

        var page1 = resources.service().listJobs(1, 2);
        var page2 = resources.service().listJobs(2, 2);

        assertEquals(2, page1.jobs().size());
        assertEquals(2, page2.jobs().size());
        assertEquals(5, page2.total());
        assertEquals(2, page2.page());

        Set<String> page1Ids = page1.jobs().stream().map(TranscodeJob::id).collect(java.util.stream.Collectors.toSet());
        Set<String> page2Ids = page2.jobs().stream().map(TranscodeJob::id).collect(java.util.stream.Collectors.toSet());
        assertTrue(page1Ids.stream().noneMatch(page2Ids::contains), "Pages 1 and 2 should not share job IDs");
    }

    @Test
    void listJobsPage3ReturnsRemainingItems() {
        ServiceResources resources = createService();
        for (int index = 1; index <= 5; index++) {
            resources.jobRepository().create(job("rem-" + index, "/file" + index + ".mkv"));
        }

        var page3 = resources.service().listJobs(3, 2);
        assertEquals(1, page3.jobs().size());
        assertEquals(5, page3.total());
        assertEquals(3, page3.page());
    }

    @Test
    void listJobsPastLastPageReturnsEmpty() {
        ServiceResources resources = createService();
        for (int index = 1; index <= 3; index++) {
            resources.jobRepository().create(job("empty-" + index, "/file" + index + ".mkv"));
        }

        var listing = resources.service().listJobs(10, 5);
        assertEquals(0, listing.jobs().size());
        assertEquals(3, listing.total());
        assertEquals(10, listing.page());
    }

    @Test
    void listJobsWithNoJobsReturnsEmptyListing() {
        ServiceResources resources = createService();
        var listing = resources.service().listJobs();
        assertEquals(0, listing.jobs().size());
        assertEquals(0, listing.total());
        assertEquals(1, listing.page());
        assertEquals(50, listing.limit());
    }

    @Test
    void listJobsDefaultsToPage1Limit50() {
        ServiceResources resources = createService();
        resources.jobRepository().create(job("def-1", "/file.mkv"));

        var listing = resources.service().listJobs();
        assertEquals(1, listing.page());
        assertEquals(50, listing.limit());
        assertEquals(1, listing.total());
        assertEquals(1, listing.jobs().size());
    }

    @Test
    void countAllReturnsTotalJobCount() {
        ServiceResources resources = createService();
        assertEquals(0, resources.jobRepository().countAll());

        resources.jobRepository().create(job("cnt-1", "/a.mkv"));
        assertEquals(1, resources.jobRepository().countAll());

        resources.jobRepository().create(new TranscodeJob(
            "cnt-2",
            JobStatus.COMPLETED,
            "/b.mkv",
            "h264_fast",
            StreamRepresentation.HLS_FMP4
        ));
        assertEquals(2, resources.jobRepository().countAll());
    }

    @Test
    void listRecentReturnsJobsOrderedByUpdatedAtDescending() throws Exception {
        ServiceResources resources = createService();
        resources.jobRepository().create(job("ord-1", "/a.mkv"));
        Thread.sleep(10L);
        resources.jobRepository().create(job("ord-2", "/b.mkv"));

        List<TranscodeJob> jobs = resources.jobRepository().listRecent(10, 0L);
        assertEquals(2, jobs.size());
        assertEquals("ord-2", jobs.get(0).id());
        assertEquals("ord-1", jobs.get(1).id());
    }

    @Test
    void listRecentWithOffsetSkipsJobs() throws Exception {
        ServiceResources resources = createService();
        for (int index = 1; index <= 5; index++) {
            resources.jobRepository().create(job("skip-" + index, "/file" + index + ".mkv"));
            Thread.sleep(5L);
        }

        List<TranscodeJob> jobs = resources.jobRepository().listRecent(2, 2L);
        assertEquals(2, jobs.size());
    }

    @Test
    void countAllIncludesAllStatuses() {
        ServiceResources resources = createService();
        resources.jobRepository().create(new TranscodeJob(
            "s-queued",
            JobStatus.QUEUED,
            "/a.mkv",
            "h264_fast",
            StreamRepresentation.DASH_FMP4
        ));
        resources.jobRepository().create(new TranscodeJob(
            "s-completed",
            JobStatus.QUEUED,
            "/b.mkv",
            "h264_fast",
            StreamRepresentation.DASH_FMP4
        ));
        resources.jobRepository().updateStatus("s-completed", JobStatus.PROBING);
        resources.jobRepository().updateStatus("s-completed", JobStatus.TRANSCODING);
        resources.jobRepository().updateStatus("s-completed", JobStatus.COMPLETED);

        assertEquals(2, resources.jobRepository().countAll());
    }

    private ServiceResources createService() {
        Path dbDir = tempDir.resolve("db-" + (++dbCounter));
        DatabaseResources database = JobRepository.createDatabase(dbDir);
        dataSources.add(database.getDataSource());
        JobRepository jobRepository = new JobRepository(database.getJdbi());
        TranscodeService service = new TranscodeService(
            TranscodeEngineConfigMapper.toTranscodeEngineConfig(testConfig(dbDir)),
            new ProbeService(),
            new SegmentCache(),
            new ManifestGenerator(),
            jobRepository,
            new PathSecurity(List.of(mediaDir))
        );
        managedServices.add(service);
        return new ServiceResources(service, jobRepository);
    }

    private ServerConfig testConfig(Path dbDir) {
        return new ServerConfig(
            "0.0.0.0",
            8080,
            List.of("*"),
            List.of(new MediaRootConfig(mediaDir, "local")),
            new FfmpegConfig("ffmpeg", "ffprobe", "6.0", 2),
            new TranscodeConfig("both", 10, 6, 10_000, 524_288_000L, 3, 2_000L, 5),
            new DatabaseConfig(dbDir)
        );
    }

    private static TranscodeJob job(String id, String inputPath) {
        return new TranscodeJob(id, JobStatus.QUEUED, inputPath, "h264_fast", StreamRepresentation.DASH_FMP4);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || Files.notExists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new IllegalStateException("Failed to delete " + path, exception);
                }
            });
        }
    }

    private record ServiceResources(TranscodeService service, JobRepository jobRepository) {
    }
}
