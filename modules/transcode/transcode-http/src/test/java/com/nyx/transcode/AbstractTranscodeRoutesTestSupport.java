package com.nyx.transcode;

import com.nyx.common.DatabaseResources;
import com.nyx.common.HealthMonitor;
import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.common.PathSecurity;
import com.nyx.common.QuotaService;
import com.nyx.common.VirtualPathResolver;
import com.nyx.config.DatabaseConfig;
import com.nyx.config.FfmpegConfig;
import com.nyx.config.MediaRootConfig;
import com.nyx.config.QuotaConfig;
import com.nyx.config.ServerConfig;
import com.nyx.config.TranscodeConfig;
import com.nyx.config.UserQuotaOverride;
import com.nyx.ffmpeg.MediaProber;
import com.nyx.ffmpeg.ProbeService;
import com.nyx.ffmpeg.SubtitleExtractor;
import com.nyx.http.UserIdPrincipal;
import com.nyx.playback.contracts.PlaybackDecision;
import com.nyx.playback.contracts.PlaybackDecisionService;
import com.nyx.playback.contracts.PlaybackMode;
import com.nyx.playback.contracts.PlaybackRequest;
import com.nyx.playback.contracts.PlaybackSessionService;
import com.nyx.playback.contracts.StreamDescriptor;
import com.nyx.stream.representation.contracts.StreamRepresentation;
import com.nyx.stream.representation.contracts.StreamRepresentationPolicy;
import com.nyx.transcode.contracts.JobStatus;
import com.nyx.transcode.contracts.SegmentCacheService;
import com.nyx.transcode.contracts.TranscodeJob;
import com.nyx.transcode.contracts.TranscodeApplicationService;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

abstract class AbstractTranscodeRoutesTestSupport {
    private static final StreamRepresentationPolicy REPRESENTATION_POLICY = StreamRepresentationPolicy.defaultPolicy();

    private final List<HikariDataSource> routeDataSources = new ArrayList<>();
    private final List<TranscodeService> routeServices = new ArrayList<>();

    protected Path tempDir;
    protected Path mediaDir;
    protected Path dbDir;
    protected Path mediaRoot;

    @BeforeEach
    void setUpRouteSupport() throws Exception {
        tempDir = Files.createTempDirectory("nyx-routes-test");
        mediaDir = Files.createDirectories(tempDir.resolve("media"));
    }

    @AfterEach
    void tearDownRouteSupport() throws Exception {
        for (int index = routeServices.size() - 1; index >= 0; index -= 1) {
            routeServices.get(index).shutdown();
        }
        routeServices.clear();

        for (HikariDataSource dataSource : routeDataSources) {
            dataSource.close();
        }
        routeDataSources.clear();

        deleteRecursively(tempDir);
    }

    protected TranscodeConfig testTranscodeConfig() {
        return testTranscodeConfig("both", 10, 6, 10_000, 524_288_000L, 3, 2_000L, 5);
    }

    protected TranscodeConfig testTranscodeConfig(
        String defaultFormat,
        int segmentCacheGracePeriodMinutes,
        int segmentDurationSteadyStateSecs,
        int segmentCacheMaxEntries,
        long minFreeDiskBytes,
        int maxRetries,
        long retryBackoffMs,
        int circuitBreakerThreshold
    ) {
        return new TranscodeConfig(
            defaultFormat,
            segmentCacheGracePeriodMinutes,
            segmentDurationSteadyStateSecs,
            segmentCacheMaxEntries,
            minFreeDiskBytes,
            maxRetries,
            retryBackoffMs,
            circuitBreakerThreshold
        );
    }

    protected QuotaConfig testQuotaConfig(
        boolean enabled,
        int defaultMaxConcurrentJobs,
        int defaultMaxRequestsPerMinute,
        long defaultMaxStorageBytes,
        Map<String, UserQuotaOverride> userOverrides
    ) {
        return new QuotaConfig(
            enabled,
            defaultMaxConcurrentJobs,
            defaultMaxRequestsPerMinute,
            defaultMaxStorageBytes,
            userOverrides
        );
    }

    protected ServerConfig testServerConfig(Path mediaDirectory, Path databaseDirectory) {
        return new ServerConfig(
            "0.0.0.0",
            8080,
            List.of("*"),
            List.of(new MediaRootConfig(mediaDirectory, "local")),
            new FfmpegConfig("ffmpeg", "ffprobe", "6.0", 2),
            testTranscodeConfig(),
            new DatabaseConfig(databaseDirectory)
        );
    }

    protected TestServices createServices(String dbName) throws Exception {
        RouteResources resources = createRouteResources(mediaDir, dbPathFor(dbName), null);
        return new TestServices(
            resources.transcodeService(),
            resources.segmentCache(),
            resources.probeService(),
            resources.pathSecurity()
        );
    }

    protected TestEnv createEnv(String dbName) throws Exception {
        RouteResources resources = createRouteResources(mediaDir, dbPathFor(dbName), null);
        return new TestEnv(
            resources.transcodeService(),
            resources.segmentCache(),
            resources.probeService(),
            resources.pathSecurity(),
            resources.jobRepository(),
            resources.quotaService()
        );
    }

    protected TestEnv createEnvWithQuota(String dbName, int maxJobs, int maxRequests) throws Exception {
        RouteResources resources = createRouteResources(
            mediaDir,
            dbPathFor(dbName),
            jobRepository -> new QuotaService(
                testQuotaConfig(true, maxJobs, maxRequests, 10_737_418_240L, Map.of()),
                jobRepository::countActiveByOwner
            )
        );
        return new TestEnv(
            resources.transcodeService(),
            resources.segmentCache(),
            resources.probeService(),
            resources.pathSecurity(),
            resources.jobRepository(),
            resources.quotaService()
        );
    }

    protected void ensureCovDirs() throws Exception {
        if (dbDir == null) {
            dbDir = Files.createDirectories(tempDir.resolve("cov-db"));
            mediaRoot = Files.createDirectories(tempDir.resolve("cov-media"));
        }
    }

    protected TestEnv createCovEnv(String dbSubDir) throws Exception {
        ensureCovDirs();
        Path envDbDir = Files.createDirectories(dbDir.resolve(dbSubDir));
        RouteResources resources = createRouteResources(mediaRoot, envDbDir, null);
        return new TestEnv(
            resources.transcodeService(),
            resources.segmentCache(),
            resources.probeService(),
            resources.pathSecurity(),
            resources.jobRepository(),
            resources.quotaService()
        );
    }

    protected void installEnvPlugins(TranscodeHttpTestSupport.ApplicationHarness app, TestEnv env) {
        installRoutes(
            app,
            env.transcodeService(),
            env.segmentCache(),
            env.probeService(),
            env.pathSecurity(),
            List.of(),
            null,
            null,
            null,
            null,
            testPlaybackDecisionService(),
            null,
            env.quotaService()
        );
    }

    protected void installCovPlugins(TranscodeHttpTestSupport.ApplicationHarness app, TestEnv env) {
        installCovPlugins(app, env, null, null, List.of(), null);
    }

    protected void installCovPlugins(
        TranscodeHttpTestSupport.ApplicationHarness app,
        TestEnv env,
        HealthMonitor healthMonitor,
        SubtitleExtractor subtitleExtractor,
        List<String> authProviders,
        String authUser
    ) {
        installRoutes(
            app,
            env.transcodeService(),
            env.segmentCache(),
            env.probeService(),
            env.pathSecurity(),
            authProviders,
            authUser,
            subtitleExtractor,
            null,
            null,
            testPlaybackDecisionService(),
            healthMonitor,
            env.quotaService()
        );
    }

    protected void installRoutes(
        TranscodeHttpTestSupport.ApplicationHarness app,
        TranscodeApplicationService transcodeService,
        SegmentCacheService segmentCache,
        MediaProber probeService,
        PathSecurity pathSecurity,
        List<String> authProviders,
        String authUser,
        SubtitleExtractor subtitleExtractor,
        VirtualPathResolver virtualPathResolver,
        PlaybackSessionService playbackSessionService,
        PlaybackDecisionService playbackDecisionService,
        HealthMonitor healthMonitor,
        QuotaService quotaService
    ) {
        if (!authProviders.isEmpty() && authUser != null) {
            for (String authProvider : authProviders) {
                app.installBearerAuth(authProvider, credential -> new UserIdPrincipal(authUser));
            }
        }

        app.routing(route -> TranscodeRoutes.transcodeRoutes(
            route,
            transcodeService,
            segmentCache,
            probeService,
            pathSecurity,
            authProviders,
            subtitleExtractor,
            virtualPathResolver,
            playbackSessionService,
            playbackDecisionService,
            healthMonitor,
            quotaService
        ));
    }

    protected void registerSegmentOutputDir(TestEnv env, String jobId, Path outputDir) {
        env.transcodeService().getSegmentOutputDirs().put(jobId, outputDir);
    }

    protected TranscodeJob job(String id, JobStatus status, String inputPath, String profile, String format) {
        return new TranscodeJob(id, status, inputPath, profile, REPRESENTATION_POLICY.normalizeExternalName(format));
    }

    protected PlaybackDecisionService testPlaybackDecisionService() {
        return request -> {
            StreamRepresentation representation = request.output().preferredRepresentation();
            if (representation == null) {
                representation = StreamRepresentation.HLS_DASH_FMP4;
            }
            if (
                representation == StreamRepresentation.HLS_MPEG_TS
                    && request.transcode().explicitRepresentations().size() > 1
            ) {
                return sneakyThrow(new NyxException(
                    ErrorCode.INVALID_REQUEST,
                    "HLS MPEG-TS output supports only one video representation"
                ));
            }
            return new PlaybackDecision(PlaybackMode.VIDEO_TRANSCODE, new StreamDescriptor(representation));
        };
    }

    protected TranscodeJob job(
        String id,
        JobStatus status,
        String inputPath,
        String profile,
        String format,
        String batchId,
        String owner
    ) {
        return new TranscodeJob(
            id,
            status,
            inputPath,
            profile,
            REPRESENTATION_POLICY.normalizeExternalName(format),
            List.of(),
            null,
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
            batchId,
            owner,
            0L
        );
    }

    private RouteResources createRouteResources(
        Path mediaRootPath,
        Path dbPath,
        Function<JobRepository, QuotaService> quotaServiceFactory
    ) throws Exception {
        Files.createDirectories(dbPath);
        DatabaseResources databaseResources = JobRepository.createDatabase(dbPath);
        routeDataSources.add(databaseResources.getDataSource());

        PathSecurity pathSecurity = new PathSecurity(List.of(mediaRootPath));
        ProbeService probeService = new ProbeService();
        SegmentCache segmentCache = new SegmentCache();
        JobRepository jobRepository = new JobRepository(databaseResources.getJdbi());
        QuotaService quotaService = quotaServiceFactory == null ? null : quotaServiceFactory.apply(jobRepository);
        TranscodeService transcodeService = new TranscodeService(
            TranscodeEngineConfigMapper.toTranscodeEngineConfig(testServerConfig(mediaRootPath, dbPath)),
            probeService,
            segmentCache,
            new ManifestGenerator(),
            jobRepository,
            pathSecurity,
            null,
            null,
            new InMemorySegmentRegistry(),
            quotaService,
            new TranscodeCommandFactory()
        );
        routeServices.add(transcodeService);

        return new RouteResources(transcodeService, segmentCache, probeService, pathSecurity, jobRepository, quotaService);
    }

    private Path dbPathFor(String dbName) {
        return tempDir.resolve("db").resolve(dbName);
    }

    protected void deleteRecursively(Path path) throws IOException {
        if (path == null || Files.notExists(path)) {
            return;
        }

        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(entry -> {
                    try {
                        Files.deleteIfExists(entry);
                    } catch (IOException exception) {
                        throw new RuntimeException(exception);
                    }
                });
        } catch (RuntimeException exception) {
            if (exception.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw exception;
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable, T> T sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }

    protected record TestServices(
        TranscodeService transcodeService,
        SegmentCache segmentCache,
        ProbeService probeService,
        PathSecurity pathSecurity
    ) {
    }

    protected record TestEnv(
        TranscodeService transcodeService,
        SegmentCache segmentCache,
        ProbeService probeService,
        PathSecurity pathSecurity,
        JobRepository jobRepository,
        QuotaService quotaService
    ) {
    }

    private record RouteResources(
        TranscodeService transcodeService,
        SegmentCache segmentCache,
        ProbeService probeService,
        PathSecurity pathSecurity,
        JobRepository jobRepository,
        QuotaService quotaService
    ) {
    }
}
