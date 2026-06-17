package com.nyx.transcode.contracts.webhook;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record WebhookSubscriptionDetail(
    @JsonProperty("subscription") WebhookSubscriptionResponse subscription,
    @JsonProperty("recentDeliveries") List<WebhookDelivery> recentDeliveries
) {
    public WebhookSubscriptionDetail {
        recentDeliveries = recentDeliveries == null ? List.of() : List.copyOf(recentDeliveries);
    }

    @JsonIgnore
    public WebhookSubscriptionResponse getSubscription() {
        return subscription;
    }

    @JsonIgnore
    public List<WebhookDelivery> getRecentDeliveries() {
        return recentDeliveries;
    }
}
