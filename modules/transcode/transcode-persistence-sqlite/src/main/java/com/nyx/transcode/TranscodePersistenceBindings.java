package com.nyx.transcode;

import com.nyx.transcode.contracts.TranscodeJobStore;
import com.nyx.transcode.contracts.webhook.WebhookStore;

public record TranscodePersistenceBindings(
    JobRepositoryResources jobResources,
    JobRepository jobRepository,
    TranscodeJobStore jobStore,
    WebhookPersistenceResources webhookPersistenceResources,
    WebhookStore webhookStore
) {}
