package com.nyx.transcode.webhook;

import static com.nyx.common.SqliteWriteTransactions.inTransactionUnchecked;
import static com.nyx.common.SqliteWriteTransactions.withHandleUnchecked;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.common.DatabaseFactory;
import com.nyx.common.DatabaseResources;
import com.nyx.config.DatabaseConfig;
import com.nyx.json.NyxJson;
import com.nyx.transcode.contracts.webhook.WebhookDelivery;
import com.nyx.transcode.contracts.webhook.WebhookEventTypes;
import com.nyx.transcode.contracts.webhook.WebhookStore;
import com.nyx.transcode.contracts.webhook.WebhookSubscription;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.List;
import org.jdbi.v3.core.Jdbi;

public final class WebhookRepository implements WebhookStore {
    private static final TypeReference<List<String>> EVENTS_TYPE = new TypeReference<>() {};

    private final Jdbi jdbi;
    private final ObjectMapper json = NyxJson.newMapper();

    public WebhookRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public WebhookSubscription createSubscription(WebhookSubscription sub) {
        return inTransactionUnchecked(jdbi, handle -> {
            handle.createUpdate("""
                    INSERT INTO webhook_subscriptions(
                        id, url, secret, events, is_active, created_at, updated_at
                    ) VALUES (
                        :id, :url, :secret, :events, :isActive, :createdAt, :updatedAt
                    )
                    """)
                .bind("id", sub.getId())
                .bind("url", sub.getUrl())
                .bind("secret", sub.getSecret())
                .bind("events", writeJson(sub.getEvents()))
                .bind("isActive", sub.isActive() ? 1 : 0)
                .bind("createdAt", sub.getCreatedAt())
                .bind("updatedAt", sub.getUpdatedAt())
                .execute();
            return sub;
        });
    }

    @Override
    public WebhookSubscription getSubscription(String id) {
        return withHandleUnchecked(jdbi, handle -> handle
            .createQuery("SELECT * FROM webhook_subscriptions WHERE id = :id")
            .bind("id", id)
            .map((resultSet, ctx) -> toSubscription(resultSet))
            .findOne()
            .orElse(null));
    }

    @Override
    public List<WebhookSubscription> listSubscriptions() {
        return withHandleUnchecked(jdbi, handle -> handle.createQuery("""
                SELECT *
                FROM webhook_subscriptions
                ORDER BY created_at DESC
                """)
            .map((resultSet, ctx) -> toSubscription(resultSet))
            .list());
    }

    @Override
    public boolean deleteSubscription(String id) {
        return inTransactionUnchecked(jdbi, handle -> handle
            .createUpdate("DELETE FROM webhook_subscriptions WHERE id = :id")
            .bind("id", id)
            .execute() > 0);
    }

    @Override
    public List<WebhookSubscription> listActiveForEvent(String eventType) {
        if (!WebhookEventTypes.INSTANCE.getALL().contains(eventType)) {
            throw new IllegalArgumentException("Unknown event type: " + eventType);
        }
        return withHandleUnchecked(jdbi, handle -> handle.createQuery("""
                SELECT *
                FROM webhook_subscriptions
                WHERE is_active = 1 AND events LIKE :eventPattern
                """)
            .bind("eventPattern", "%\"" + eventType + "\"%")
            .map((resultSet, ctx) -> toSubscription(resultSet))
            .list());
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
        return inTransactionUnchecked(jdbi, handle -> {
            StringBuilder sql = new StringBuilder("UPDATE webhook_subscriptions SET updated_at = :updatedAt");
            if (url != null) {
                sql.append(", url = :url");
            }
            if (secret != null) {
                sql.append(", secret = :secret");
            }
            if (events != null) {
                sql.append(", events = :events");
            }
            if (isActive != null) {
                sql.append(", is_active = :isActive");
            }
            sql.append(" WHERE id = :id");

            var update = handle.createUpdate(sql.toString())
                .bind("id", id)
                .bind("updatedAt", updatedAt);
            if (url != null) {
                update.bind("url", url);
            }
            if (secret != null) {
                update.bind("secret", secret);
            }
            if (events != null) {
                update.bind("events", writeJson(events));
            }
            if (isActive != null) {
                update.bind("isActive", isActive ? 1 : 0);
            }
            return update.execute() > 0;
        });
    }

    @Override
    public void recordDelivery(WebhookDelivery delivery) {
        inTransactionUnchecked(jdbi, handle -> {
            handle.createUpdate("""
                    INSERT INTO webhook_deliveries(
                        id, subscription_id, event, payload, status_code, attempt, delivered_at, created_at
                    ) VALUES (
                        :id, :subscriptionId, :event, :payload, :statusCode, :attempt, :deliveredAt, :createdAt
                    )
                    """)
                .bind("id", delivery.getId())
                .bind("subscriptionId", delivery.getSubscriptionId())
                .bind("event", delivery.getEvent())
                .bind("payload", delivery.getPayload())
                .bind("statusCode", delivery.getStatusCode())
                .bind("attempt", delivery.getAttempt())
                .bind("deliveredAt", delivery.getDeliveredAt())
                .bind("createdAt", delivery.getCreatedAt())
                .execute();
            return null;
        });
    }

    public List<WebhookDelivery> listDeliveries(String subscriptionId) {
        return listDeliveries(subscriptionId, 20);
    }

    @Override
    public List<WebhookDelivery> listDeliveries(String subscriptionId, int limit) {
        return withHandleUnchecked(jdbi, handle -> handle.createQuery("""
                SELECT *
                FROM webhook_deliveries
                WHERE subscription_id = :subscriptionId
                ORDER BY created_at DESC
                LIMIT :limit
                """)
            .bind("subscriptionId", subscriptionId)
            .bind("limit", limit)
            .map((resultSet, ctx) -> toDelivery(resultSet))
            .list());
    }

    @Override
    public int purgeOldDeliveries(String olderThan) {
        return inTransactionUnchecked(jdbi, handle -> handle
            .createUpdate("DELETE FROM webhook_deliveries WHERE created_at < :olderThan")
            .bind("olderThan", olderThan)
            .execute());
    }

    private WebhookSubscription toSubscription(ResultSet resultSet) {
        List<String> events;
        try {
            String raw = getStringUnchecked(resultSet, "events");
            events = raw == null || raw.isBlank() ? List.of() : json.readValue(raw, EVENTS_TYPE);
        } catch (Exception exception) {
            events = List.of();
        }
        return new WebhookSubscription(
            getStringUnchecked(resultSet, "id"),
            getStringUnchecked(resultSet, "url"),
            getStringUnchecked(resultSet, "secret"),
            events,
            getIntUnchecked(resultSet, "is_active") == 1,
            getStringUnchecked(resultSet, "created_at"),
            getStringUnchecked(resultSet, "updated_at")
        );
    }

    private WebhookDelivery toDelivery(ResultSet resultSet) {
        Integer statusCode;
        try {
            int value = resultSet.getInt("status_code");
            statusCode = resultSet.wasNull() ? null : value;
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }

        return new WebhookDelivery(
            getStringUnchecked(resultSet, "id"),
            getStringUnchecked(resultSet, "subscription_id"),
            getStringUnchecked(resultSet, "event"),
            getStringUnchecked(resultSet, "payload"),
            statusCode,
            getIntUnchecked(resultSet, "attempt"),
            getStringUnchecked(resultSet, "delivered_at"),
            getStringUnchecked(resultSet, "created_at")
        );
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new RuntimeException("Failed to serialize webhook payload", exception);
        }
    }

    private String getStringUnchecked(ResultSet resultSet, String column) {
        try {
            return resultSet.getString(column);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private int getIntUnchecked(ResultSet resultSet, String column) {
        try {
            return resultSet.getInt(column);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    public static DatabaseResources createDatabase(Path dbDir) {
        return createDatabase(dbDir, new DatabaseConfig(dbDir, 1, 600_000L, 1_800_000L));
    }

    public static DatabaseResources createDatabase(Path dbDir, DatabaseConfig dbConfig) {
        return DatabaseFactory.create(dbDir, "webhooks", dbConfig);
    }
}
