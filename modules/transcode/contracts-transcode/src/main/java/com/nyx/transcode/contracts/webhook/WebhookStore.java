package com.nyx.transcode.contracts.webhook;

import java.util.List;

public interface WebhookStore {
    WebhookSubscription createSubscription(WebhookSubscription sub);

    WebhookSubscription getSubscription(String id);

    List<WebhookSubscription> listSubscriptions();

    boolean deleteSubscription(String id);

    List<WebhookSubscription> listActiveForEvent(String eventType);

    boolean updateSubscription(
        String id,
        String url,
        String secret,
        List<String> events,
        Boolean isActive,
        String updatedAt
    );

    default boolean updateSubscription(String id, String updatedAt) {
        return updateSubscription(id, null, null, null, null, updatedAt);
    }

    void recordDelivery(WebhookDelivery delivery);

    default List<WebhookDelivery> listDeliveries(String subscriptionId) {
        return listDeliveries(subscriptionId, 20);
    }

    List<WebhookDelivery> listDeliveries(String subscriptionId, int limit);

    int purgeOldDeliveries(String olderThan);
}
