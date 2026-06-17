package com.nyx.transcode;

import com.nyx.config.ServerConfig;

public final class TranscodePersistenceSqliteModule {
    private TranscodePersistenceSqliteModule() {}

    public static TranscodePersistenceBindings createTranscodePersistenceBindings(ServerConfig config) {
        JobRepositoryResources jobResources = JobRepositoryResources.create(config);
        JobRepository jobRepository = new JobRepository(jobResources.jdbi());
        WebhookPersistenceResources webhookPersistenceResources = config.getWebhooks().getEnabled()
            ? WebhookPersistenceResources.create(config)
            : new WebhookPersistenceResources();
        return new TranscodePersistenceBindings(
            jobResources,
            jobRepository,
            jobRepository,
            webhookPersistenceResources,
            webhookPersistenceResources.store()
        );
    }
}
