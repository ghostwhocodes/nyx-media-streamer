package com.nyx.transcode;

import static com.nyx.transcode.TranscodeEngineConfigMapper.toTranscodeEngineConfig;

import com.nyx.common.MetricsCollector;
import com.nyx.common.PathSecurity;
import com.nyx.common.QuotaService;
import com.nyx.config.ServerConfig;
import com.nyx.ffmpeg.MediaProber;
import com.nyx.transcode.contracts.ManagedTranscodeApplicationService;
import com.nyx.transcode.contracts.SegmentCacheService;
import com.nyx.transcode.contracts.TranscodeApplicationService;
import com.nyx.transcode.contracts.TranscodeJobStore;
import com.nyx.transcode.contracts.webhook.WebhookStore;
import com.nyx.transcode.contracts.webhook.WebhookUrlValidator;
import com.nyx.transcode.webhook.JavaNetWebhookHttpClient;
import com.nyx.transcode.webhook.WebhookService;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

public final class TranscodeModule {
    private TranscodeModule() {}

    public static TranscodeBindings createTranscodeBindings(
        ServerConfig config,
        PathSecurity pathSecurity,
        MediaProber probeService,
        TranscodeJobStore jobStore,
        MetricsCollector metricsCollector,
        ConcurrentHashMap<String, String> runtimeUsers,
        ScheduledExecutorService cleanupScheduler,
        ExecutorService backgroundExecutor,
        WebhookStore webhookStore
    ) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(pathSecurity, "pathSecurity");
        Objects.requireNonNull(probeService, "probeService");
        Objects.requireNonNull(jobStore, "jobStore");
        Objects.requireNonNull(cleanupScheduler, "cleanupScheduler");

        TranscodeCommandFactory commandFactory = new TranscodeCommandFactory();
        ManifestGenerator manifestGenerator = new ManifestGenerator();
        SegmentCache segmentCache = new SegmentCache(
            config.getTranscode().getSegmentCacheGracePeriodMinutes(),
            config.getTranscode().getSegmentCacheMaxEntries(),
            cleanupScheduler,
            metricsCollector == null ? null : metricsCollector::recordSegmentCacheEviction
        );
        QuotaService quotaService = createQuotaService(config, jobStore, runtimeUsers);
        TranscodeService transcodeService = new TranscodeService(
            toTranscodeEngineConfig(config),
            probeService,
            segmentCache,
            manifestGenerator,
            jobStore,
            pathSecurity,
            cleanupScheduler,
            metricsCollector,
            new InMemorySegmentRegistry(),
            quotaService,
            commandFactory
        );
        TranscodeApplicationService transcodeApplicationService = transcodeService;
        ManagedTranscodeApplicationService managedTranscodeApplicationService = transcodeService;
        WebhookResources webhookResources = createWebhookResources(
            config,
            cleanupScheduler,
            backgroundExecutor,
            webhookStore,
            metricsCollector,
            transcodeApplicationService
        );

        return new TranscodeBindings(
            commandFactory,
            manifestGenerator,
            segmentCache,
            segmentCache,
            quotaService,
            transcodeService,
            transcodeApplicationService,
            managedTranscodeApplicationService,
            webhookResources
        );
    }

    public static TranscodeBindings createTranscodeBindings(
        ServerConfig config,
        PathSecurity pathSecurity,
        MediaProber probeService,
        TranscodeJobStore jobStore,
        ScheduledExecutorService cleanupScheduler
    ) {
        return createTranscodeBindings(
            config,
            pathSecurity,
            probeService,
            jobStore,
            null,
            new ConcurrentHashMap<>(),
            cleanupScheduler,
            null,
            null
        );
    }

    private static QuotaService createQuotaService(
        ServerConfig config,
        TranscodeJobStore jobStore,
        ConcurrentHashMap<String, String> runtimeUsers
    ) {
        if (!config.getQuota().getEnabled()) {
            return null;
        }

        Set<String> knownUserIds = config.getAuth().getEnabled()
            ? Set.copyOf(runtimeUsers == null
                ? config.getAuth().getTokens().values()
                : new java.util.LinkedHashSet<String>() {{
                    addAll(config.getAuth().getTokens().values());
                    addAll(runtimeUsers.keySet());
                }})
            : Set.of();

        return new QuotaService(
            config.getQuota(),
            jobStore::countActiveByOwner,
            jobStore::sumStorageByOwner,
            knownUserIds
        );
    }

    private static WebhookResources createWebhookResources(
        ServerConfig config,
        ScheduledExecutorService cleanupScheduler,
        ExecutorService backgroundExecutor,
        WebhookStore webhookStore,
        MetricsCollector metricsCollector,
        TranscodeApplicationService transcodeApplicationService
    ) {
        if (!config.getWebhooks().getEnabled() || webhookStore == null) {
            return new WebhookResources();
        }

        Duration requestTimeout = Duration.ofMillis(config.getWebhooks().getTimeoutMs());
        JavaNetWebhookHttpClient httpClient = new JavaNetWebhookHttpClient(
            HttpClient.newBuilder().connectTimeout(requestTimeout).build(),
            requestTimeout
        );
        WebhookUrlValidator urlValidator = new WebhookUrlValidator(config.getWebhooks().getAllowedHosts());
        WebhookService service = new WebhookService(
            httpClient,
            webhookStore,
            config.getWebhooks(),
            backgroundExecutor,
            metricsCollector,
            urlValidator,
            java.time.Clock.systemUTC(),
            cleanupScheduler
        );
        transcodeApplicationService.setOnJobEvent(event -> {
            service.onJobEvent(event);
        });
        return new WebhookResources(service, urlValidator);
    }
}
