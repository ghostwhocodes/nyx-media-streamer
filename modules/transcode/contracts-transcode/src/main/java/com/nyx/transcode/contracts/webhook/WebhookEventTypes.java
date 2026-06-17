package com.nyx.transcode.contracts.webhook;

import java.util.Set;

public final class WebhookEventTypes {
    public static final WebhookEventTypes INSTANCE = new WebhookEventTypes();

    public static final String JOB_COMPLETED = "job.completed";
    public static final String JOB_FAILED = "job.failed";
    public static final String JOB_PROGRESS = "job.progress";
    public static final String JOB_RETRYING = "job.retrying";
    public static final Set<String> ALL = Set.of(JOB_COMPLETED, JOB_FAILED, JOB_PROGRESS, JOB_RETRYING);

    private WebhookEventTypes() {
    }

    public Set<String> getALL() {
        return ALL;
    }
}
