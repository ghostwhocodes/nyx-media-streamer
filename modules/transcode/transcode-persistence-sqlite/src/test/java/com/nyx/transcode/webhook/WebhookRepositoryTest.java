package com.nyx.transcode.webhook;

import com.nyx.common.DatabaseResources;
import com.nyx.transcode.contracts.webhook.WebhookDelivery;
import com.nyx.transcode.contracts.webhook.WebhookSubscription;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookRepositoryTest {
    @TempDir
    Path tempDir;

    private WebhookRepository repository;
    private HikariDataSource dataSource;

    @BeforeEach
    void setup() throws IOException {
        Path dbDir = Files.createDirectories(tempDir.resolve("webhooks"));
        DatabaseResources resources = WebhookRepository.createDatabase(dbDir);
        dataSource = resources.getDataSource();
        repository = new WebhookRepository(resources.getJdbi());
    }

    @AfterEach
    void teardown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void createSubscriptionAndGetSubscriptionRoundTrip() {
        WebhookSubscription subscription = testSubscription();
        repository.createSubscription(subscription);

        WebhookSubscription fetched = repository.getSubscription("sub-1");
        assertNotNull(fetched);
        assertEquals("sub-1", fetched.id());
        assertEquals("https://example.com/hook", fetched.url());
        assertEquals("test-secret", fetched.secret());
        assertEquals(List.of("job.completed"), fetched.events());
        assertTrue(fetched.isActive());
    }

    @Test
    void getSubscriptionReturnsNullForUnknownId() {
        assertNull(repository.getSubscription("nonexistent"));
    }

    @Test
    void listSubscriptionsReturnsAllSubscriptions() {
        repository.createSubscription(testSubscription("sub-1", "https://example.com/hook", List.of("job.completed")));
        repository.createSubscription(testSubscription("sub-2", "https://other.com/hook", List.of("job.completed")));

        List<WebhookSubscription> all = repository.listSubscriptions();
        assertEquals(2, all.size());
    }

    @Test
    void deleteSubscriptionRemovesSubscription() {
        repository.createSubscription(testSubscription());
        assertTrue(repository.deleteSubscription("sub-1"));
        assertNull(repository.getSubscription("sub-1"));
    }

    @Test
    void deleteSubscriptionReturnsFalseForUnknownId() {
        assertFalse(repository.deleteSubscription("nonexistent"));
    }

    @Test
    void listActiveForEventFiltersByEventType() {
        repository.createSubscription(testSubscription("sub-1", "https://example.com/hook", List.of("job.completed", "job.failed")));
        repository.createSubscription(testSubscription("sub-2", "https://other.com/hook", List.of("job.progress")));

        List<WebhookSubscription> completed = repository.listActiveForEvent("job.completed");
        assertEquals(1, completed.size());
        assertEquals("sub-1", completed.get(0).id());

        List<WebhookSubscription> progress = repository.listActiveForEvent("job.progress");
        assertEquals(1, progress.size());
        assertEquals("sub-2", progress.get(0).id());

        assertTrue(repository.listActiveForEvent("job.retrying").isEmpty());
    }

    @Test
    void listActiveForEventExcludesInactiveSubscriptions() {
        repository.createSubscription(subscription("sub-1", "https://example.com/hook", "test-secret", List.of("job.completed"), false, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"));

        List<WebhookSubscription> result = repository.listActiveForEvent("job.completed");
        assertTrue(result.isEmpty());
    }

    @Test
    void recordDeliveryAndListDeliveries() {
        repository.createSubscription(testSubscription());

        WebhookDelivery delivery = new WebhookDelivery(
            "del-1",
            "sub-1",
            "job.completed",
            "{\"test\":true}",
            200,
            1,
            "2026-01-01T00:01:00Z",
            "2026-01-01T00:00:30Z"
        );
        repository.recordDelivery(delivery);

        List<WebhookDelivery> deliveries = repository.listDeliveries("sub-1");
        assertEquals(1, deliveries.size());
        assertEquals("del-1", deliveries.get(0).id());
        assertEquals(200, deliveries.get(0).statusCode());
        assertEquals(1, deliveries.get(0).attempt());
    }

    @Test
    void listDeliveriesRespectsLimit() {
        repository.createSubscription(testSubscription());

        for (int index = 0; index < 5; index++) {
            repository.recordDelivery(new WebhookDelivery(
                "del-" + index,
                "sub-1",
                "job.completed",
                "{}",
                200,
                1,
                null,
                "2026-01-01T00:0" + index + ":00Z"
            ));
        }

        List<WebhookDelivery> limited = repository.listDeliveries("sub-1", 3);
        assertEquals(3, limited.size());
    }

    @Test
    void deleteSubscriptionCascadesToDeliveries() {
        repository.createSubscription(testSubscription());
        repository.recordDelivery(new WebhookDelivery("del-1", "sub-1", "job.completed", "{}", "2026-01-01T00:00:00Z"));

        repository.deleteSubscription("sub-1");
        assertTrue(repository.listDeliveries("sub-1").isEmpty());
    }

    @Test
    void subscriptionWithoutSecretStoresNull() {
        repository.createSubscription(subscription("sub-1", "https://example.com/hook", null, List.of("job.completed"), true, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"));

        WebhookSubscription fetched = repository.getSubscription("sub-1");
        assertNotNull(fetched);
        assertNull(fetched.secret());
    }

    @Test
    void deliveryWithoutStatusCodeStoresNull() {
        repository.createSubscription(testSubscription());
        repository.recordDelivery(new WebhookDelivery("del-1", "sub-1", "job.failed", "{}", "2026-01-01T00:00:00Z"));

        List<WebhookDelivery> deliveries = repository.listDeliveries("sub-1");
        assertEquals(1, deliveries.size());
        assertNull(deliveries.get(0).statusCode());
    }

    @Test
    void purgeOldDeliveriesRemovesOldRecordsOnly() {
        repository.createSubscription(testSubscription());

        repository.recordDelivery(new WebhookDelivery("del-old", "sub-1", "job.completed", "{}", 200, 1, null, "2025-01-01T00:00:00Z"));
        repository.recordDelivery(new WebhookDelivery("del-new", "sub-1", "job.completed", "{}", 200, 1, null, "2026-06-01T00:00:00Z"));

        int purged = repository.purgeOldDeliveries("2026-01-01T00:00:00Z");
        assertEquals(1, purged);

        List<WebhookDelivery> remaining = repository.listDeliveries("sub-1");
        assertEquals(1, remaining.size());
        assertEquals("del-new", remaining.get(0).id());
    }

    @Test
    void purgeOldDeliveriesReturnsCount() {
        repository.createSubscription(testSubscription());

        for (int index = 0; index < 3; index++) {
            repository.recordDelivery(new WebhookDelivery(
                "del-" + index,
                "sub-1",
                "job.completed",
                "{}",
                "2024-01-0" + (index + 1) + "T00:00:00Z"
            ));
        }

        int purged = repository.purgeOldDeliveries("2025-01-01T00:00:00Z");
        assertEquals(3, purged);
    }

    @Test
    void purgeOldDeliveriesReturnsZeroWhenNothingToPurge() {
        repository.createSubscription(testSubscription());
        repository.recordDelivery(new WebhookDelivery("del-1", "sub-1", "job.completed", "{}", "2026-06-01T00:00:00Z"));

        int purged = repository.purgeOldDeliveries("2025-01-01T00:00:00Z");
        assertEquals(0, purged);
    }

    @Test
    void updateSubscriptionUpdatesUrlOnly() {
        repository.createSubscription(testSubscription());

        boolean updated = repository.updateSubscription(
            "sub-1",
            "https://new-url.com/hook",
            null,
            null,
            null,
            "2026-02-01T00:00:00Z"
        );
        assertTrue(updated);

        WebhookSubscription fetched = repository.getSubscription("sub-1");
        assertNotNull(fetched);
        assertEquals("https://new-url.com/hook", fetched.url());
        assertEquals(List.of("job.completed"), fetched.events());
        assertEquals("2026-02-01T00:00:00Z", fetched.updatedAt());
    }

    @Test
    void updateSubscriptionUpdatesEvents() {
        repository.createSubscription(testSubscription());

        boolean updated = repository.updateSubscription(
            "sub-1",
            null,
            null,
            List.of("job.failed", "job.retrying"),
            null,
            "2026-02-01T00:00:00Z"
        );
        assertTrue(updated);

        WebhookSubscription fetched = repository.getSubscription("sub-1");
        assertNotNull(fetched);
        assertEquals(List.of("job.failed", "job.retrying"), fetched.events());
    }

    @Test
    void updateSubscriptionTogglesIsActive() {
        repository.createSubscription(testSubscription());

        boolean updated = repository.updateSubscription(
            "sub-1",
            null,
            null,
            null,
            false,
            "2026-02-01T00:00:00Z"
        );
        assertTrue(updated);

        WebhookSubscription fetched = repository.getSubscription("sub-1");
        assertNotNull(fetched);
        assertFalse(fetched.isActive());
    }

    @Test
    void updateSubscriptionReturnsFalseForUnknownId() {
        boolean updated = repository.updateSubscription(
            "nonexistent",
            "https://example.com",
            null,
            null,
            null,
            "2026-02-01T00:00:00Z"
        );
        assertFalse(updated);
    }

    @Test
    void listActiveForEventRejectsUnknownEventType() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> repository.listActiveForEvent("UNKNOWN_EVENT")
        );
        assertTrue(exception.getMessage().contains("Unknown event type"));
    }

    @Test
    void toSubscriptionHandlesInvalidEventsJsonGracefully() throws Exception {
        repository.createSubscription(subscription(
            "sub-bad",
            "https://example.com/hook",
            null,
            List.of("job.completed"),
            true,
            Instant.now().toString(),
            Instant.now().toString()
        ));

        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("UPDATE webhook_subscriptions SET events = 'NOT VALID JSON' WHERE id = 'sub-bad'");
        }

        WebhookSubscription fetched = repository.getSubscription("sub-bad");
        assertNotNull(fetched);
        assertTrue(fetched.events().isEmpty());
    }

    private WebhookSubscription testSubscription() {
        return testSubscription("sub-1", "https://example.com/hook", List.of("job.completed"));
    }

    private WebhookSubscription testSubscription(String id, String url, List<String> events) {
        return subscription(id, url, "test-secret", events, true, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z");
    }

    private WebhookSubscription subscription(
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
}
