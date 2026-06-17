package com.nyx.transcode.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.nyx.common.ManagedService;
import com.nyx.common.MetricsCollector;
import com.nyx.config.WebhookConfig;
import com.nyx.json.NyxJson;
import com.nyx.transcode.contracts.JobEvent;
import com.nyx.transcode.contracts.webhook.WebhookDelivery;
import com.nyx.transcode.contracts.webhook.WebhookEventTypes;
import com.nyx.transcode.contracts.webhook.WebhookPayload;
import com.nyx.transcode.contracts.webhook.WebhookStore;
import com.nyx.transcode.contracts.webhook.WebhookSubscription;
import com.nyx.transcode.contracts.webhook.WebhookUrlValidator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WebhookService implements ManagedService {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebhookService.class);

    private final WebhookHttpClient httpClient;
    private final WebhookStore repository;
    private final WebhookConfig config;
    private final MetricsCollector metricsService;
    private final WebhookUrlValidator urlValidator;
    private final Clock clock;
    private final com.fasterxml.jackson.databind.ObjectMapper json = NyxJson.newMapper();
    private final JsonNodeFactory nodeFactory = JsonNodeFactory.instance;
    private final Semaphore deliverySemaphore;
    private final boolean ownsDeliveryExecutor;
    private final ExecutorService deliveryExecutor;
    private final boolean ownsCleanupScheduler;
    private final ScheduledExecutorService cleanupScheduler;
    private final ScheduledFuture<?> cleanupTask;

    public WebhookService(WebhookHttpClient httpClient, WebhookStore repository, WebhookConfig config) {
        this(httpClient, repository, config, null, null, null, Clock.systemUTC(), null);
    }

    public WebhookService(
        WebhookHttpClient httpClient,
        WebhookStore repository,
        WebhookConfig config,
        ExecutorService deliveryExecutor
    ) {
        this(httpClient, repository, config, deliveryExecutor, null, null, Clock.systemUTC(), null);
    }

    public WebhookService(
        WebhookHttpClient httpClient,
        WebhookStore repository,
        WebhookConfig config,
        ExecutorService deliveryExecutor,
        MetricsCollector metricsService
    ) {
        this(httpClient, repository, config, deliveryExecutor, metricsService, null, Clock.systemUTC(), null);
    }

    public WebhookService(
        WebhookHttpClient httpClient,
        WebhookStore repository,
        WebhookConfig config,
        ExecutorService deliveryExecutor,
        MetricsCollector metricsService,
        WebhookUrlValidator urlValidator
    ) {
        this(httpClient, repository, config, deliveryExecutor, metricsService, urlValidator, Clock.systemUTC(), null);
    }

    public WebhookService(
        WebhookHttpClient httpClient,
        WebhookStore repository,
        WebhookConfig config,
        ExecutorService deliveryExecutor,
        MetricsCollector metricsService,
        WebhookUrlValidator urlValidator,
        Clock clock,
        ScheduledExecutorService cleanupScheduler
    ) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.config = Objects.requireNonNull(config, "config");
        this.metricsService = metricsService;
        this.urlValidator = urlValidator;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.deliverySemaphore = new Semaphore(config.getMaxConcurrentDeliveries());
        this.ownsDeliveryExecutor = deliveryExecutor == null;
        this.deliveryExecutor = deliveryExecutor != null
            ? deliveryExecutor
            : Executors.newVirtualThreadPerTaskExecutor();
        this.ownsCleanupScheduler = cleanupScheduler == null;
        this.cleanupScheduler = cleanupScheduler != null
            ? cleanupScheduler
            : Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "nyx-webhook-cleanup");
                thread.setDaemon(true);
                return thread;
            });
        this.cleanupTask = !config.getEnabled() || config.getCleanupIntervalMinutes() <= 0
            ? null
            : this.cleanupScheduler.scheduleWithFixedDelay(
                this::purgeExpiredDeliveries,
                config.getCleanupIntervalMinutes(),
                config.getCleanupIntervalMinutes(),
                TimeUnit.MINUTES
            );
    }

    public void onJobEvent(JobEvent event) {
        if (!config.getEnabled()) {
            return;
        }

        String eventType = mapEventType(event);
        if (eventType == null) {
            return;
        }

        WebhookPayload payload = buildPayload(event, eventType);
        String payloadJson;
        try {
            payloadJson = json.writeValueAsString(payload);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to encode webhook payload", exception);
        }

        for (WebhookSubscription subscription : repository.listActiveForEvent(eventType)) {
            if (metricsService != null) {
                metricsService.webhookDispatched();
            }
            deliveryExecutor.execute(() -> {
                try {
                    deliverySemaphore.acquire();
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    LOGGER.debug("Webhook delivery for {} was interrupted before dispatch", subscription.getUrl());
                    return;
                }
                try {
                    deliverWithRetry(subscription, eventType, payloadJson);
                } finally {
                    deliverySemaphore.release();
                }
            });
        }
    }

    void deliverWithRetry(WebhookSubscription subscription, String eventType, String payloadJson) {
        if (urlValidator != null) {
            try {
                urlValidator.validateOrThrow(subscription.getUrl());
            } catch (RuntimeException failure) {
                LOGGER.warn("Webhook URL failed SSRF validation at delivery time: {}", subscription.getUrl());
                if (metricsService != null) {
                    metricsService.webhookDeliveryFailure();
                }
                return;
            }
        }

        int maxAttempts = 1 + config.getMaxRetries();
        for (int attempt = 1; attempt <= maxAttempts; attempt += 1) {
            if (attemptDelivery(subscription, eventType, payloadJson, attempt, maxAttempts)) {
                return;
            }
            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(config.getRetryBackoffMs() * (1L << (attempt - 1)));
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (metricsService != null) {
            metricsService.webhookDeliveryFailure();
        }
        LOGGER.error(
            "Webhook delivery to {} exhausted all {} attempts",
            subscription.getUrl(),
            1 + config.getMaxRetries()
        );
    }

    @Override
    public void shutdown() {
        LOGGER.info("Shutting down WebhookService...");
        if (cleanupTask != null) {
            cleanupTask.cancel(true);
        }
        if (ownsDeliveryExecutor) {
            deliveryExecutor.shutdownNow();
        }
        if (ownsCleanupScheduler) {
            cleanupScheduler.shutdownNow();
        }
        httpClient.close();
    }

    static String computeHmac(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte current : digest) {
                builder.append(String.format("%02x", current));
            }
            return builder.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to compute webhook signature", exception);
        }
    }

    private void purgeExpiredDeliveries() {
        try {
            String threshold = clock.instant()
                .minus(Duration.ofDays(config.getDeliveryRetentionDays()))
                .toString();
            int purged = repository.purgeOldDeliveries(threshold);
            if (purged > 0) {
                LOGGER.info("Purged {} old webhook delivery records", purged);
                if (metricsService != null) {
                    metricsService.webhookDeliveriesPurged(purged);
                }
            }
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to purge old webhook deliveries: {}", exception.getMessage());
        }
    }

    private boolean attemptDelivery(
        WebhookSubscription subscription,
        String eventType,
        String payloadJson,
        int attempt,
        int maxAttempts
    ) {
        String deliveryId = UUID.randomUUID().toString();
        String now = clock.instant().toString();
        try {
            String signatureHeader = subscription.getSecret() == null
                ? null
                : "sha256=" + computeHmac(subscription.getSecret(), payloadJson);
            WebhookHttpResponse response = httpClient.postJson(URI.create(subscription.getUrl()), payloadJson, signatureHeader);
            int statusCode = response.statusCode();
            repository.recordDelivery(
                new WebhookDelivery(
                    deliveryId,
                    subscription.getId(),
                    eventType,
                    payloadJson,
                    statusCode,
                    attempt,
                    clock.instant().toString(),
                    now
                )
            );
            if (statusCode >= 200 && statusCode <= 299) {
                if (metricsService != null) {
                    metricsService.webhookDeliverySuccess();
                }
                LOGGER.debug("Webhook delivered to {} (status {})", subscription.getUrl(), statusCode);
                return true;
            }
            LOGGER.warn(
                "Webhook delivery to {} returned status {}, attempt {}/{}",
                subscription.getUrl(),
                statusCode,
                attempt,
                maxAttempts
            );
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            recordFailedDelivery(deliveryId, subscription.getId(), eventType, payloadJson, attempt, now);
            LOGGER.warn(
                "Webhook delivery to {} interrupted: {}, attempt {}/{}",
                subscription.getUrl(),
                interruptedException.getMessage(),
                attempt,
                maxAttempts
            );
        } catch (IOException ioException) {
            recordFailedDelivery(deliveryId, subscription.getId(), eventType, payloadJson, attempt, now);
            LOGGER.warn(
                "Webhook delivery to {} failed: {}, attempt {}/{}",
                subscription.getUrl(),
                ioException.getMessage(),
                attempt,
                maxAttempts
            );
        }
        return false;
    }

    private void recordFailedDelivery(
        String deliveryId,
        String subscriptionId,
        String eventType,
        String payloadJson,
        int attempt,
        String createdAt
    ) {
        repository.recordDelivery(
            new WebhookDelivery(
                deliveryId,
                subscriptionId,
                eventType,
                payloadJson,
                null,
                attempt,
                null,
                createdAt
            )
        );
    }

    private String mapEventType(JobEvent event) {
        return switch (event) {
            case JobEvent.Complete ignored -> WebhookEventTypes.JOB_COMPLETED;
            case JobEvent.Error ignored -> WebhookEventTypes.JOB_FAILED;
            case JobEvent.Progress ignored -> WebhookEventTypes.JOB_PROGRESS;
            case JobEvent.Retry ignored -> WebhookEventTypes.JOB_RETRYING;
            case JobEvent.Segment ignored -> null;
        };
    }

    private WebhookPayload buildPayload(JobEvent event, String eventType) {
        JsonNode data = switch (event) {
            case JobEvent.Complete complete -> nodeFactory.objectNode()
                .put("jobId", complete.getJobId())
                .put("durationSecs", complete.getDurationSecs())
                .put("segmentsTotal", complete.getSegmentsTotal());
            case JobEvent.Error error -> nodeFactory.objectNode()
                .put("jobId", error.getJobId())
                .put("code", error.getCode())
                .put("message", error.getMessage());
            case JobEvent.Progress progress -> nodeFactory.objectNode()
                .put("jobId", progress.getJobId())
                .put("percent", progress.getPercent())
                .put("speed", progress.getSpeed())
                .put("fps", progress.getFps());
            case JobEvent.Retry retry -> nodeFactory.objectNode()
                .put("jobId", retry.getJobId())
                .put("attempt", retry.getAttempt())
                .put("reason", retry.getReason());
            case JobEvent.Segment segment -> nodeFactory.objectNode()
                .put("jobId", segment.getJobId());
        };

        return new WebhookPayload(
            UUID.randomUUID().toString(),
            eventType,
            clock.instant().toString(),
            data
        );
    }
}
