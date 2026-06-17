package com.nyx.transcode.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.common.DatabaseResources;
import com.nyx.common.RecordingMetricsCollector;
import com.nyx.config.WebhookConfig;
import com.nyx.transcode.TestScheduledExecutorService;
import com.nyx.transcode.contracts.JobEvent;
import com.nyx.transcode.contracts.webhook.WebhookDelivery;
import com.nyx.transcode.contracts.webhook.WebhookSubscription;
import com.nyx.transcode.contracts.webhook.WebhookUrlValidator;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebhookServiceTest {
    private final List<WebhookService> services = new ArrayList<>();

    private Path tempDir;
    private WebhookRepository repository;
    private HikariDataSource dataSource;
    private ExecutorService deliveryExecutor;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("nyx-webhook-svc-test");
        DatabaseResources resources = WebhookRepository.createDatabase(tempDir);
        dataSource = resources.getDataSource();
        repository = new WebhookRepository(resources.getJdbi());
        deliveryExecutor = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (int index = services.size() - 1; index >= 0; index -= 1) {
            services.get(index).shutdown();
        }
        deliveryExecutor.shutdownNow();
        dataSource.close();
        deleteRecursively(tempDir);
    }

    @Test
    void onJobEventDispatchesToMatchingSubscription() throws Exception {
        RecordingWebhookHttpClient client = createMockClient(request -> new WebhookHttpResponse(200));
        RecordingMetricsCollector metrics = new RecordingMetricsCollector();
        WebhookConfig config = webhookConfig(true, 0, 5_000L, 30_000L, 10, Set.of(), 7, 60);
        WebhookService service = trackService(new WebhookService(client, repository, config, deliveryExecutor, metrics));

        repository.createSubscription(testSubscription());
        service.onJobEvent(new JobEvent.Complete("job-1", 42.5, 7));

        waitUntil(() -> client.requests.size() == 1 && metrics.getWebhookSuccess() == 1, 2_000L);

        assertEquals(1, client.requests.size());
        RecordedRequest request = client.requests.get(0);
        assertEquals("https://example.com/hook", request.uri().toString());
        assertNotNull(request.signatureHeader());
        assertEquals(1, metrics.getWebhookDispatched());
        assertEquals(1, metrics.getWebhookSuccess());
    }

    @Test
    void onJobEventDoesNothingWhenDisabled() throws Exception {
        RecordingWebhookHttpClient client = createMockClient(request -> new WebhookHttpResponse(200));
        WebhookConfig config = webhookConfig(false, 3, 5_000L, 30_000L, 10, Set.of(), 7, 60);
        WebhookService service = trackService(new WebhookService(client, repository, config, deliveryExecutor));

        repository.createSubscription(testSubscription());
        service.onJobEvent(new JobEvent.Complete("job-1", 42.5, 7));

        Thread.sleep(200L);
        assertTrue(client.requests.isEmpty());
    }

    @Test
    void onJobEventIgnoresSegmentEvents() throws Exception {
        RecordingWebhookHttpClient client = createMockClient(request -> new WebhookHttpResponse(200));
        WebhookConfig config = webhookConfig(true, 0, 5_000L, 30_000L, 10, Set.of(), 7, 60);
        WebhookService service = trackService(new WebhookService(client, repository, config, deliveryExecutor));

        repository.createSubscription(testSubscription());
        service.onJobEvent(new JobEvent.Segment("job-1", "seg_0.m4s", "video", 6.0));

        Thread.sleep(200L);
        assertTrue(client.requests.isEmpty());
    }

    @Test
    void hmacSignatureIsComputedCorrectly() {
        String body = "{\"test\":\"data\"}";
        String signature = WebhookService.computeHmac("secret-key", body);

        assertEquals(64, signature.length());
        assertTrue(signature.chars().allMatch(ch -> (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f')));

        String again = WebhookService.computeHmac("secret-key", body);
        assertEquals(signature, again);

        String different = WebhookService.computeHmac("other-key", body);
        assertNotEquals(signature, different);
    }

    @Test
    void hmacSignatureUsesUtf8WhenDefaultCharsetDiffers() throws Exception {
        String signature = runInJvmWithEncoding(
            "ISO-8859-1",
            HmacProbe.class.getName(),
            "sëcret-密",
            "{\"message\":\"héllo 東京\"}"
        );

        assertEquals("a3313c9b25cfdb9a3ea9ccaf6205d80b91eb1f893c2778501a9a58a87a798caa", signature);
    }

    @Test
    void deliverWithRetryRetriesOnFailure() {
        int[] attemptCount = new int[] { 0 };
        RecordingWebhookHttpClient client = createMockClient(request -> {
            attemptCount[0] += 1;
            return attemptCount[0] < 3 ? new WebhookHttpResponse(500) : new WebhookHttpResponse(200);
        });

        RecordingMetricsCollector metrics = new RecordingMetricsCollector();
        WebhookConfig config = webhookConfig(true, 3, 10L, 30_000L, 10, Set.of(), 7, 60);
        WebhookService service = trackService(new WebhookService(client, repository, config, deliveryExecutor, metrics));

        repository.createSubscription(testSubscription());
        service.deliverWithRetry(testSubscription(), "job.completed", "{\"test\":true}");

        assertEquals(3, attemptCount[0]);
        assertEquals(1, metrics.getWebhookSuccess());
        assertEquals(0, metrics.getWebhookFailure());
    }

    @Test
    void deliverWithRetryRecordsFailureAfterExhaustingRetries() {
        RecordingWebhookHttpClient client = createMockClient(request -> new WebhookHttpResponse(500));

        RecordingMetricsCollector metrics = new RecordingMetricsCollector();
        WebhookConfig config = webhookConfig(true, 2, 10L, 30_000L, 10, Set.of(), 7, 60);
        WebhookService service = trackService(new WebhookService(client, repository, config, deliveryExecutor, metrics));

        repository.createSubscription(testSubscription());
        service.deliverWithRetry(testSubscription(), "job.completed", "{\"test\":true}");

        assertEquals(1, metrics.getWebhookFailure());
        assertEquals(0, metrics.getWebhookSuccess());

        List<WebhookDelivery> deliveries = repository.listDeliveries("sub-1");
        assertEquals(3, deliveries.size());
    }

    @Test
    void deliverWithRetryHandlesConnectionExceptions() {
        RecordingWebhookHttpClient client = createMockClient(request -> {
            throw new IOException("Connection refused");
        });

        RecordingMetricsCollector metrics = new RecordingMetricsCollector();
        WebhookConfig config = webhookConfig(true, 1, 10L, 30_000L, 10, Set.of(), 7, 60);
        WebhookService service = trackService(new WebhookService(client, repository, config, deliveryExecutor, metrics));

        repository.createSubscription(testSubscription());
        service.deliverWithRetry(testSubscription(), "job.completed", "{\"test\":true}");

        assertEquals(1, metrics.getWebhookFailure());

        List<WebhookDelivery> deliveries = repository.listDeliveries("sub-1");
        assertEquals(2, deliveries.size());
        for (WebhookDelivery delivery : deliveries) {
            assertNull(delivery.getStatusCode());
        }
    }

    @Test
    void onJobEventSkipsWhenNoSubscriptionsMatch() throws Exception {
        RecordingWebhookHttpClient client = createMockClient(request -> new WebhookHttpResponse(200));
        WebhookConfig config = webhookConfig(true, 0, 5_000L, 30_000L, 10, Set.of(), 7, 60);
        WebhookService service = trackService(new WebhookService(client, repository, config, deliveryExecutor));

        repository.createSubscription(testSubscription("sub-1", List.of("job.progress"), "my-secret", "https://example.com/hook"));
        service.onJobEvent(new JobEvent.Complete("job-1", 42.5, 7));

        Thread.sleep(200L);
        assertTrue(client.requests.isEmpty());
    }

    @Test
    void onJobEventMapsErrorEventToJobFailed() throws Exception {
        RecordingWebhookHttpClient client = createMockClient(request -> new WebhookHttpResponse(200));
        WebhookConfig config = webhookConfig(true, 0, 5_000L, 30_000L, 10, Set.of(), 7, 60);
        WebhookService service = trackService(new WebhookService(client, repository, config, deliveryExecutor));

        repository.createSubscription(testSubscription("sub-1", List.of("job.failed"), "my-secret", "https://example.com/hook"));
        service.onJobEvent(new JobEvent.Error("job-1", "TRANSCODE_FAILED", "FFmpeg crashed"));

        waitUntil(() -> client.requests.size() == 1, 2_000L);
        assertEquals(1, client.requests.size());
    }

    @Test
    void onJobEventMapsRetryEventToJobRetrying() throws Exception {
        RecordingWebhookHttpClient client = createMockClient(request -> new WebhookHttpResponse(200));
        WebhookConfig config = webhookConfig(true, 0, 5_000L, 30_000L, 10, Set.of(), 7, 60);
        WebhookService service = trackService(new WebhookService(client, repository, config, deliveryExecutor));

        repository.createSubscription(testSubscription("sub-1", List.of("job.retrying"), "my-secret", "https://example.com/hook"));
        service.onJobEvent(new JobEvent.Retry("job-1", 2, "Retrying with fallback"));

        waitUntil(() -> client.requests.size() == 1, 2_000L);
        assertEquals(1, client.requests.size());
    }

    @Test
    void onJobEventMapsProgressEventToJobProgress() throws Exception {
        RecordingWebhookHttpClient client = createMockClient(request -> new WebhookHttpResponse(200));
        WebhookConfig config = webhookConfig(true, 0, 5_000L, 30_000L, 10, Set.of(), 7, 60);
        WebhookService service = trackService(new WebhookService(client, repository, config, deliveryExecutor));

        repository.createSubscription(testSubscription("sub-1", List.of("job.progress"), "my-secret", "https://example.com/hook"));
        service.onJobEvent(new JobEvent.Progress("job-1", 50.0, 1.5, 30.0));

        waitUntil(() -> client.requests.size() == 1, 2_000L);
        assertEquals(1, client.requests.size());
    }

    @Test
    void deliverWithRetrySkipsSignatureHeaderWhenNoSecret() {
        RecordingWebhookHttpClient client = createMockClient(request -> new WebhookHttpResponse(200));
        WebhookConfig config = webhookConfig(true, 0, 5_000L, 30_000L, 10, Set.of(), 7, 60);
        WebhookService service = trackService(new WebhookService(client, repository, config, deliveryExecutor));

        WebhookSubscription subscription = testSubscription("sub-1", List.of("job.completed"), null, "https://example.com/hook");
        repository.createSubscription(subscription);

        service.deliverWithRetry(subscription, "job.completed", "{\"test\":true}");

        assertEquals(1, client.requests.size());
        assertNull(client.requests.get(0).signatureHeader());
    }

    @Test
    void deliverWithRetryRejectsSsrfUrlAtDeliveryTime() {
        RecordingWebhookHttpClient client = createMockClient(request -> new WebhookHttpResponse(200));

        RecordingMetricsCollector metrics = new RecordingMetricsCollector();
        WebhookConfig config = webhookConfig(true, 0, 5_000L, 30_000L, 10, Set.of(), 7, 60);
        WebhookUrlValidator urlValidator = new WebhookUrlValidator();
        WebhookService service = trackService(
            new WebhookService(client, repository, config, deliveryExecutor, metrics, urlValidator)
        );

        WebhookSubscription subscription = webhookSubscription(
            "sub-ssrf",
            "http://127.0.0.1/evil",
            null,
            List.of("job.completed"),
            true,
            "2026-01-01T00:00:00Z",
            "2026-01-01T00:00:00Z"
        );
        repository.createSubscription(subscription);

        service.deliverWithRetry(subscription, "job.completed", "{\"test\":true}");

        assertTrue(client.requests.isEmpty());
        assertEquals(1, metrics.getWebhookFailure());
    }

    @Test
    void purgeOldDeliveriesCalledDirectlyWorks() {
        repository.createSubscription(testSubscription());
        repository.recordDelivery(webhookDelivery("del-old", "sub-1", "job.completed", "{}", "2024-01-01T00:00:00Z"));
        repository.recordDelivery(webhookDelivery("del-new", "sub-1", "job.completed", "{}", "2026-06-01T00:00:00Z"));

        int purged = repository.purgeOldDeliveries("2025-01-01T00:00:00Z");
        assertEquals(1, purged);

        List<WebhookDelivery> remaining = repository.listDeliveries("sub-1");
        assertEquals(1, remaining.size());
        assertEquals("del-new", remaining.get(0).getId());
    }

    @Test
    void shutdownClosesTheHttpClient() {
        RecordingWebhookHttpClient client = createMockClient(request -> new WebhookHttpResponse(200));

        WebhookConfig config = webhookConfig(false, 3, 5_000L, 30_000L, 10, Set.of(), 7, 60);
        WebhookService service = trackService(new WebhookService(client, repository, config, deliveryExecutor));

        service.shutdown();

        WebhookSubscription subscription = testSubscription();
        repository.createSubscription(subscription);
        boolean threw;
        try {
            service.deliverWithRetry(subscription, "job.completed", "{\"test\":true}");
            threw = false;
        } catch (Exception ignored) {
            threw = true;
        }
        assertTrue(threw, "Using the service after shutdown should fail because client is closed");
    }

    @Test
    void onJobEventWithSegmentEventReturnsWithoutDispatching() throws Exception {
        RecordingWebhookHttpClient client = createMockClient(request -> new WebhookHttpResponse(200));

        RecordingMetricsCollector metrics = new RecordingMetricsCollector();
        WebhookConfig config = webhookConfig(true, 0, 5_000L, 30_000L, 10, Set.of(), 7, 60);
        WebhookService service = trackService(new WebhookService(client, repository, config, deliveryExecutor, metrics));

        repository.createSubscription(
            testSubscription(
                "sub-1",
                List.of("job.completed", "job.failed", "job.progress", "job.retrying"),
                "my-secret",
                "https://example.com/hook"
            )
        );

        service.onJobEvent(new JobEvent.Segment("job-1", "seg_0.m4s", "video", 6.0));

        Thread.sleep(300L);

        assertTrue(client.requests.isEmpty(), "Segment events should not produce HTTP requests");
        assertEquals(0, metrics.getWebhookDispatched(), "Segment events should not increment dispatched counter");
    }

    @Test
    void onJobEventDispatchesToMultipleMatchingSubscriptions() throws Exception {
        RecordingWebhookHttpClient client = createMockClient(request -> new WebhookHttpResponse(200));

        RecordingMetricsCollector metrics = new RecordingMetricsCollector();
        WebhookConfig config = webhookConfig(true, 0, 5_000L, 30_000L, 10, Set.of(), 7, 60);
        WebhookService service = trackService(new WebhookService(client, repository, config, deliveryExecutor, metrics));

        repository.createSubscription(
            testSubscription("sub-A", List.of("job.completed"), "my-secret", "https://a.example.com/hook")
        );
        repository.createSubscription(
            testSubscription("sub-B", List.of("job.completed"), "my-secret", "https://b.example.com/hook")
        );

        service.onJobEvent(new JobEvent.Complete("job-1", 42.5, 7));

        waitUntil(
            () -> client.requests.size() == 2 && metrics.getWebhookDispatched() == 2 && metrics.getWebhookSuccess() == 2,
            2_000L
        );

        assertEquals(2, client.requests.size(), "Both subscriptions should receive a delivery");
        assertEquals(2, metrics.getWebhookDispatched(), "Both dispatches should be counted");
        assertEquals(2, metrics.getWebhookSuccess(), "Both deliveries should succeed");

        Set<String> urls = client.requests.stream().map(request -> request.uri().toString()).collect(java.util.stream.Collectors.toSet());
        assertTrue(urls.contains("https://a.example.com/hook"));
        assertTrue(urls.contains("https://b.example.com/hook"));
    }

    @Test
    void deliverWithRetryIncludesHmacSignatureWhenSecretIsPresent() {
        RecordingWebhookHttpClient client = createMockClient(request -> new WebhookHttpResponse(200));
        WebhookConfig config = webhookConfig(true, 0, 5_000L, 30_000L, 10, Set.of(), 7, 60);
        WebhookService service = trackService(new WebhookService(client, repository, config, deliveryExecutor));

        WebhookSubscription subscription = testSubscription("sub-1", List.of("job.completed"), "test-secret-key", "https://example.com/hook");
        repository.createSubscription(subscription);

        String payload = "{\"jobId\":\"job-1\"}";
        service.deliverWithRetry(subscription, "job.completed", payload);

        assertEquals(1, client.requests.size());
        String signatureHeader = client.requests.get(0).signatureHeader();
        assertNotNull(signatureHeader, "Signature header should be present when secret is set");
        assertTrue(signatureHeader.startsWith("sha256="), "Signature should start with sha256=");

        String expectedHmac = WebhookService.computeHmac("test-secret-key", payload);
        assertEquals("sha256=" + expectedHmac, signatureHeader);
    }

    @Test
    void deliverWithRetryAppliesExponentialBackoffBetweenRetries() {
        int[] attemptCount = new int[] { 0 };
        List<Long> attemptTimestamps = new ArrayList<>();
        RecordingWebhookHttpClient client = createMockClient(request -> {
            attemptCount[0] += 1;
            attemptTimestamps.add(System.nanoTime());
            return new WebhookHttpResponse(503);
        });

        RecordingMetricsCollector metrics = new RecordingMetricsCollector();
        WebhookConfig config = webhookConfig(true, 2, 10L, 30_000L, 10, Set.of(), 7, 60);
        WebhookService service = trackService(new WebhookService(client, repository, config, deliveryExecutor, metrics));

        repository.createSubscription(testSubscription());
        service.deliverWithRetry(testSubscription(), "job.completed", "{\"test\":true}");

        assertEquals(3, attemptCount[0], "Should make 1 initial + 2 retry attempts");
        assertEquals(1, metrics.getWebhookFailure(), "Should record one final failure after exhausting retries");

        List<WebhookDelivery> deliveries = repository.listDeliveries("sub-1");
        assertEquals(3, deliveries.size());
        for (WebhookDelivery delivery : deliveries) {
            assertEquals(503, delivery.getStatusCode());
        }
    }

    @Test
    void deliverWithRetryWithUrlValidatorFailureShortCircuitsWithoutHttpCall() {
        RecordingWebhookHttpClient client = createMockClient(request -> new WebhookHttpResponse(200));

        RecordingMetricsCollector metrics = new RecordingMetricsCollector();
        WebhookConfig config = webhookConfig(true, 2, 5_000L, 30_000L, 10, Set.of(), 7, 60);
        WebhookUrlValidator urlValidator = new WebhookUrlValidator();
        WebhookService service = trackService(
            new WebhookService(client, repository, config, deliveryExecutor, metrics, urlValidator)
        );

        WebhookSubscription subscription = webhookSubscription(
            "sub-priv",
            "http://192.168.1.1/webhook",
            null,
            List.of("job.completed"),
            true,
            "2026-01-01T00:00:00Z",
            "2026-01-01T00:00:00Z"
        );
        repository.createSubscription(subscription);

        service.deliverWithRetry(subscription, "job.completed", "{\"test\":true}");

        assertTrue(client.requests.isEmpty(), "No HTTP request should be made for blocked URLs");
        assertEquals(1, metrics.getWebhookFailure(), "Should record failure for SSRF-blocked URL");

        List<WebhookDelivery> deliveries = repository.listDeliveries("sub-priv");
        assertEquals(0, deliveries.size(), "No delivery records for SSRF-blocked URL");
    }

    @Test
    void recordFailedDeliveryStoresDeliveryWithNullStatusCodeViaIoException() {
        int[] attemptCount = new int[] { 0 };
        RecordingWebhookHttpClient client = createMockClient(request -> {
            attemptCount[0] += 1;
            throw new IOException("Connection reset by peer");
        });

        RecordingMetricsCollector metrics = new RecordingMetricsCollector();
        WebhookConfig config = webhookConfig(true, 0, 10L, 30_000L, 10, Set.of(), 7, 60);
        WebhookService service = trackService(new WebhookService(client, repository, config, deliveryExecutor, metrics));

        WebhookSubscription subscription = testSubscription("sub-io", List.of("job.completed"), "my-secret", "https://example.com/hook");
        repository.createSubscription(subscription);

        service.deliverWithRetry(subscription, "job.completed", "{\"test\":\"io-error\"}");

        assertEquals(1, attemptCount[0]);
        assertEquals(1, metrics.getWebhookFailure());

        List<WebhookDelivery> deliveries = repository.listDeliveries("sub-io");
        assertEquals(1, deliveries.size());
        assertNull(deliveries.get(0).getStatusCode(), "IOException delivery should have null statusCode");
        assertEquals(1, deliveries.get(0).getAttempt());
        assertEquals("job.completed", deliveries.get(0).getEvent());
        assertEquals("{\"test\":\"io-error\"}", deliveries.get(0).getPayload());
    }

    @Test
    void cleanupLoopPurgesOldDeliveries() {
        repository.createSubscription(
            webhookSubscription(
                "sub-1",
                "https://example.com/hook",
                null,
                List.of("job.completed"),
                true,
                Instant.now().toString(),
                Instant.now().toString()
            )
        );

        repository.recordDelivery(
            webhookDelivery(
                "del-old",
                "sub-1",
                "job.completed",
                "{\"test\":true}",
                "2020-01-01T00:00:00Z",
                200,
                1,
                null
            )
        );

        WebhookConfig config = webhookConfig(true, 3, 5_000L, 30_000L, 10, Set.of(), 1, 1);
        RecordingWebhookHttpClient client = createMockClient(request -> new WebhookHttpResponse(200));
        TestScheduledExecutorService scheduler = new TestScheduledExecutorService();
        WebhookService service = trackService(
            new WebhookService(client, repository, config, deliveryExecutor, null, null, Clock.systemUTC(), scheduler)
        );

        scheduler.runScheduledTask();

        List<WebhookDelivery> after = repository.listDeliveries("sub-1");
        assertTrue(after.isEmpty());
        service.shutdown();
    }

    @Test
    void cleanupLoopPurgesOldDeliveriesWithRealIo() {
        repository.createSubscription(
            webhookSubscription(
                "sub-purge",
                "https://example.com/hook",
                null,
                List.of("job.completed"),
                true,
                Instant.now().toString(),
                Instant.now().toString()
            )
        );

        repository.recordDelivery(
            webhookDelivery(
                "del-purge",
                "sub-purge",
                "job.completed",
                "{\"test\":true}",
                "2020-01-01T00:00:00Z",
                200,
                1,
                null
            )
        );

        List<WebhookDelivery> before = repository.listDeliveries("sub-purge");
        assertEquals(1, before.size());

        String threshold = Instant.now().minus(Duration.ofDays(1)).toString();
        int purged = repository.purgeOldDeliveries(threshold);
        assertTrue(purged > 0, "Should purge at least one old delivery");

        List<WebhookDelivery> after = repository.listDeliveries("sub-purge");
        assertTrue(after.isEmpty());
    }

    private WebhookService trackService(WebhookService service) {
        services.add(service);
        return service;
    }

    private WebhookSubscription testSubscription() {
        return testSubscription("sub-1", List.of("job.completed"), "my-secret", "https://example.com/hook");
    }

    private WebhookSubscription testSubscription(String id, List<String> events, String secret, String url) {
        return webhookSubscription(
            id,
            url,
            secret,
            events,
            true,
            "2026-01-01T00:00:00Z",
            "2026-01-01T00:00:00Z"
        );
    }

    private RecordingWebhookHttpClient createMockClient(RequestHandler handler) {
        return new RecordingWebhookHttpClient(handler);
    }

    private WebhookConfig webhookConfig(
        boolean enabled,
        int maxRetries,
        long retryBackoffMs,
        long timeoutMs,
        int maxConcurrentDeliveries,
        Set<String> allowedHosts,
        int deliveryRetentionDays,
        int cleanupIntervalMinutes
    ) {
        return new WebhookConfig(
            enabled,
            maxRetries,
            retryBackoffMs,
            timeoutMs,
            maxConcurrentDeliveries,
            allowedHosts,
            deliveryRetentionDays,
            cleanupIntervalMinutes
        );
    }

    private WebhookSubscription webhookSubscription(
        String id,
        String url,
        String secret,
        List<String> events,
        boolean isActive,
        String createdAt,
        String updatedAt
    ) {
        return new WebhookSubscription(id, url, secret, events, isActive, createdAt, updatedAt);
    }

    private WebhookDelivery webhookDelivery(
        String id,
        String subscriptionId,
        String event,
        String payload,
        String createdAt
    ) {
        return new WebhookDelivery(id, subscriptionId, event, payload, createdAt);
    }

    private WebhookDelivery webhookDelivery(
        String id,
        String subscriptionId,
        String event,
        String payload,
        String createdAt,
        Integer statusCode,
        int attempt,
        String deliveredAt
    ) {
        return new WebhookDelivery(id, subscriptionId, event, payload, statusCode, attempt, deliveredAt, createdAt);
    }

    private void waitUntil(BooleanSupplier condition, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10L);
        }
    }

    private void deleteRecursively(Path root) throws Exception {
        if (root == null || Files.notExists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private String runInJvmWithEncoding(String encoding, String mainClass, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Dfile.encoding=" + encoding);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(mainClass);
        command.addAll(List.of(args));

        Process process = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, process.waitFor(), output);
        return output;
    }

    private record RecordedRequest(URI uri, String payload, String signatureHeader) {}

    @FunctionalInterface
    private interface RequestHandler {
        WebhookHttpResponse handle(RecordedRequest request) throws IOException;
    }

    private static final class RecordingWebhookHttpClient implements WebhookHttpClient {
        private final RequestHandler handler;
        private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
        private volatile boolean closed;

        private RecordingWebhookHttpClient(RequestHandler handler) {
            this.handler = handler;
        }

        @Override
        public WebhookHttpResponse postJson(URI uri, String payloadJson, String signatureHeader) throws IOException {
            if (closed) {
                throw new IllegalStateException("Webhook HTTP client is closed");
            }
            RecordedRequest request = new RecordedRequest(uri, payloadJson, signatureHeader);
            requests.add(request);
            return handler.handle(request);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    public static final class HmacProbe {
        public static void main(String[] args) {
            System.out.print(WebhookService.computeHmac(args[0], args[1]));
        }
    }
}
