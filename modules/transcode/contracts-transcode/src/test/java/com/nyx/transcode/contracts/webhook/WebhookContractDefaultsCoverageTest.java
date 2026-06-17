package com.nyx.transcode.contracts.webhook;

import com.nyx.json.NyxJson;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookContractDefaultsCoverageTest {
    @Test
    void migratedWebhookDtosKeepGettersDefaultsAndDefensiveCopies() {
        CreateWebhookRequest create = new CreateWebhookRequest(
            "https://hooks.example.test/nyx",
            new ArrayList<>(List.of(WebhookEventTypes.JOB_COMPLETED))
        );
        WebhookSubscription subscription = new WebhookSubscription(
            "sub-1",
            "https://hooks.example.test/nyx",
            new ArrayList<>(List.of(WebhookEventTypes.JOB_COMPLETED)),
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
        WebhookPayload payload = new WebhookPayload(
            "evt-1",
            WebhookEventTypes.JOB_PROGRESS,
            "2026-05-02T19:00:02Z",
            NyxJson.newMapper().createObjectNode().put("percent", 42)
        );
        UpdateWebhookRequest update = new UpdateWebhookRequest(
            "https://hooks.example.test/updated",
            "secret",
            new ArrayList<>(List.of(WebhookEventTypes.JOB_FAILED)),
            Boolean.TRUE
        );
        WebhookSubscriptionResponse response = new WebhookSubscriptionResponse(
            subscription.id(),
            subscription.url(),
            new ArrayList<>(subscription.events()),
            subscription.isActive(),
            subscription.createdAt(),
            subscription.updatedAt()
        );
        WebhookSubscriptionDetail detail = new WebhookSubscriptionDetail(response, new ArrayList<>(List.of(delivery)));

        assertEquals("https://hooks.example.test/nyx", create.getUrl());
        assertNull(create.getSecret());
        assertEquals(List.of(WebhookEventTypes.JOB_COMPLETED), create.getEvents());
        assertEquals("sub-1", subscription.getId());
        assertEquals("https://hooks.example.test/nyx", subscription.getUrl());
        assertNull(subscription.getSecret());
        assertEquals(List.of(WebhookEventTypes.JOB_COMPLETED), subscription.getEvents());
        assertEquals("2026-05-02T19:00:00Z", subscription.getCreatedAt());
        assertEquals("2026-05-02T19:00:00Z", subscription.getUpdatedAt());
        assertEquals("delivery-1", delivery.getId());
        assertEquals("sub-1", delivery.getSubscriptionId());
        assertEquals(WebhookEventTypes.JOB_COMPLETED, delivery.getEvent());
        assertEquals("{}", delivery.getPayload());
        assertNull(delivery.getStatusCode());
        assertEquals(1, delivery.getAttempt());
        assertNull(delivery.getDeliveredAt());
        assertEquals("2026-05-02T19:00:01Z", delivery.getCreatedAt());
        assertEquals("evt-1", payload.getId());
        assertEquals(WebhookEventTypes.JOB_PROGRESS, payload.getEvent());
        assertEquals("2026-05-02T19:00:02Z", payload.getTimestamp());
        assertEquals(42, payload.getData().get("percent").asInt());
        assertEquals("https://hooks.example.test/updated", update.getUrl());
        assertEquals("secret", update.getSecret());
        assertEquals(List.of(WebhookEventTypes.JOB_FAILED), update.getEvents());
        assertEquals(Boolean.TRUE, update.getIsActive());
        assertEquals("sub-1", response.getId());
        assertEquals("https://hooks.example.test/nyx", response.getUrl());
        assertEquals(List.of(WebhookEventTypes.JOB_COMPLETED), response.getEvents());
        assertEquals("2026-05-02T19:00:00Z", response.getCreatedAt());
        assertEquals("2026-05-02T19:00:00Z", response.getUpdatedAt());
        assertNotNull(detail.getSubscription());
        assertEquals(1, detail.getRecentDeliveries().size());

        assertThrows(UnsupportedOperationException.class, () -> create.getEvents().add(WebhookEventTypes.JOB_FAILED));
        assertThrows(UnsupportedOperationException.class, () -> subscription.getEvents().add(WebhookEventTypes.JOB_FAILED));
        assertThrows(UnsupportedOperationException.class, () -> update.getEvents().add(WebhookEventTypes.JOB_COMPLETED));
        assertThrows(UnsupportedOperationException.class, () -> response.getEvents().add(WebhookEventTypes.JOB_FAILED));
        assertThrows(UnsupportedOperationException.class, () -> detail.getRecentDeliveries().add(delivery));
    }

    @Test
    void validatorResultApisPreserveSuccessFailureAndAddressClassification() {
        WebhookUrlValidator allowlisted = new WebhookUrlValidator(Set.of("hooks.internal"));
        WebhookUrlValidator validator = new WebhookUrlValidator();

        assertDoesNotThrow(() -> allowlisted.validate("https://hooks.internal/nyx"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("ftp://example.com/nyx"));

        IllegalArgumentException linkLocal = assertThrows(
            IllegalArgumentException.class,
            () -> validator.validateOrThrow("http://169.254.10.1/nyx")
        );
        IllegalArgumentException multicast = assertThrows(
            IllegalArgumentException.class,
            () -> validator.validateOrThrow("http://224.0.0.1/nyx")
        );

        assertTrue(linkLocal.getMessage().contains("link-local"));
        assertTrue(multicast.getMessage().contains("multicast"));
    }
}
