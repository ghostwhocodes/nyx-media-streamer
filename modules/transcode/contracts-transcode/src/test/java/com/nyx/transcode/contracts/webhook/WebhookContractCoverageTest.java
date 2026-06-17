package com.nyx.transcode.contracts.webhook;

import com.nyx.json.NyxJson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookContractCoverageTest {
    @Test
    void webhookModelsPreserveDefaultsAndPublishedEventNames() {
        WebhookSubscription subscription = new WebhookSubscription(
            "sub-1",
            "https://hooks.example.test/nyx",
            List.of(WebhookEventTypes.JOB_COMPLETED),
            "2026-05-02T19:00:00Z",
            "2026-05-02T19:00:00Z"
        );
        CreateWebhookRequest createRequest = new CreateWebhookRequest(
            "https://hooks.example.test/nyx",
            List.of(WebhookEventTypes.JOB_COMPLETED, WebhookEventTypes.JOB_FAILED)
        );
        WebhookDelivery delivery = new WebhookDelivery(
            "delivery-1",
            "sub-1",
            WebhookEventTypes.JOB_COMPLETED,
            "{\"jobId\":\"job-1\"}",
            "2026-05-02T19:00:01Z"
        );
        WebhookPayload payload = new WebhookPayload(
            "evt-1",
            WebhookEventTypes.JOB_PROGRESS,
            "2026-05-02T19:00:02Z",
            NyxJson.newMapper().createObjectNode().put("percent", 42)
        );
        WebhookSubscriptionResponse response = new WebhookSubscriptionResponse(
            subscription.id(),
            subscription.url(),
            subscription.events(),
            subscription.isActive(),
            subscription.createdAt(),
            subscription.updatedAt()
        );
        UpdateWebhookRequest update = new UpdateWebhookRequest();
        WebhookSubscriptionDetail detail = new WebhookSubscriptionDetail(response, List.of(delivery));

        assertNull(subscription.secret());
        assertTrue(subscription.isActive());
        assertNull(delivery.statusCode());
        assertEquals(1, delivery.attempt());
        assertNull(delivery.deliveredAt());
        assertEquals(42, payload.data().get("percent").asInt());
        assertNull(update.url());
        assertNull(update.events());
        assertEquals("sub-1", detail.subscription().id());
        assertEquals("delivery-1", detail.recentDeliveries().get(0).id());
        assertTrue(WebhookEventTypes.ALL.contains(WebhookEventTypes.JOB_RETRYING));
        assertEquals(4, WebhookEventTypes.ALL.size());
        assertEquals(2, createRequest.events().size());
    }

    @Test
    void webhookStoreDefaultsForwardOmittedOptionalFields() {
        RecordingWebhookStore store = new RecordingWebhookStore();
        WebhookSubscription subscription = new WebhookSubscription(
            "sub-1",
            "https://hooks.example.test/nyx",
            List.of(WebhookEventTypes.JOB_COMPLETED),
            "2026-05-02T19:00:00Z",
            "2026-05-02T19:00:00Z"
        );
        WebhookDelivery delivery = new WebhookDelivery(
            "delivery-1",
            subscription.id(),
            WebhookEventTypes.JOB_COMPLETED,
            "{}",
            "2026-05-02T19:00:01Z"
        );

        store.createSubscription(subscription);
        store.getSubscription(subscription.id());
        store.listSubscriptions();
        store.deleteSubscription(subscription.id());
        store.listActiveForEvent(WebhookEventTypes.JOB_COMPLETED);
        store.updateSubscription(subscription.id(), "2026-05-02T19:00:02Z");
        store.recordDelivery(delivery);
        store.listDeliveries(subscription.id());
        store.purgeOldDeliveries("2026-05-01T00:00:00Z");

        assertNull(store.lastUpdatedUrl);
        assertNull(store.lastUpdatedSecret);
        assertNull(store.lastUpdatedEvents);
        assertNull(store.lastUpdatedActive);
        assertEquals(20, store.lastListLimit);
        assertEquals("delivery-1", store.lastDeliveryId);
    }

    @Test
    void webhookUrlValidatorAcceptsAllowlistedHostsAndRejectsUnsafeTargets() {
        WebhookUrlValidator allowlisted = new WebhookUrlValidator(java.util.Set.of("hooks.internal"));
        WebhookUrlValidator defaultValidator = new WebhookUrlValidator();

        allowlisted.validateOrThrow("https://hooks.internal/nyx");

        IllegalArgumentException invalidProtocol =
            assertThrows(IllegalArgumentException.class, () -> defaultValidator.validateOrThrow("ftp://example.com/nyx"));
        IllegalArgumentException blankHost =
            assertThrows(IllegalArgumentException.class, () -> defaultValidator.validateOrThrow("https:///nyx"));
        IllegalArgumentException loopback =
            assertThrows(IllegalArgumentException.class, () -> defaultValidator.validateOrThrow("http://127.0.0.1/nyx"));
        IllegalArgumentException zeroNetwork =
            assertThrows(IllegalArgumentException.class, () -> defaultValidator.validateOrThrow("http://0.0.0.1/nyx"));
        IllegalArgumentException unresolvable =
            assertThrows(
                IllegalArgumentException.class,
                () -> defaultValidator.validateOrThrow("http://definitely-not-a-real-host.invalid/nyx")
            );

        assertEquals("Webhook URL must use HTTP or HTTPS", invalidProtocol.getMessage());
        assertTrue(blankHost.getMessage().contains("blank host"));
        assertTrue(loopback.getMessage().contains("loopback"));
        assertTrue(zeroNetwork.getMessage().contains("reserved"));
        assertTrue(unresolvable.getMessage().contains("Cannot resolve webhook host"));
    }

    private static final class RecordingWebhookStore implements WebhookStore {
        private String lastUpdatedUrl;
        private String lastUpdatedSecret;
        private List<String> lastUpdatedEvents;
        private Boolean lastUpdatedActive;
        private Integer lastListLimit;
        private String lastDeliveryId;

        @Override
        public WebhookSubscription createSubscription(WebhookSubscription sub) {
            return sub;
        }

        @Override
        public WebhookSubscription getSubscription(String id) {
            return null;
        }

        @Override
        public List<WebhookSubscription> listSubscriptions() {
            return List.of();
        }

        @Override
        public boolean deleteSubscription(String id) {
            return true;
        }

        @Override
        public List<WebhookSubscription> listActiveForEvent(String eventType) {
            return List.of();
        }

        @Override
        public boolean updateSubscription(
            String id,
            String url,
            String secret,
            List<String> events,
            Boolean isActive,
            String updatedAt
        ) {
            lastUpdatedUrl = url;
            lastUpdatedSecret = secret;
            lastUpdatedEvents = events;
            lastUpdatedActive = isActive;
            return true;
        }

        @Override
        public void recordDelivery(WebhookDelivery delivery) {
            lastDeliveryId = delivery.id();
        }

        @Override
        public List<WebhookDelivery> listDeliveries(String subscriptionId, int limit) {
            lastListLimit = limit;
            return List.of();
        }

        @Override
        public int purgeOldDeliveries(String olderThan) {
            return 0;
        }
    }
}
