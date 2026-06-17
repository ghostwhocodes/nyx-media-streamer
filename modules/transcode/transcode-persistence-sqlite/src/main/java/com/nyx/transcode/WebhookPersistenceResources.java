package com.nyx.transcode;

import com.nyx.common.DatabaseResources;
import com.nyx.config.ServerConfig;
import com.nyx.transcode.contracts.webhook.WebhookStore;
import com.nyx.transcode.webhook.WebhookRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.jdbi.v3.core.Jdbi;

public record WebhookPersistenceResources(
    Jdbi jdbi,
    HikariDataSource dataSource,
    WebhookStore store
) {
    public WebhookPersistenceResources() {
        this(null, null, null);
    }

    public static WebhookPersistenceResources create(ServerConfig config) {
        DatabaseResources resources =
            WebhookRepository.createDatabase(config.getDatabase().getDir(), config.getDatabase());
        return new WebhookPersistenceResources(
            resources.getJdbi(),
            resources.getDataSource(),
            new WebhookRepository(resources.getJdbi())
        );
    }
}
