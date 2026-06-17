package com.nyx.transcode;

import com.nyx.common.QuotaService;
import com.nyx.transcode.contracts.ManagedTranscodeApplicationService;
import com.nyx.transcode.contracts.SegmentCacheService;
import com.nyx.transcode.contracts.TranscodeApplicationService;

public record TranscodeBindings(
    TranscodeCommandFactory commandFactory,
    ManifestGenerator manifestGenerator,
    SegmentCache segmentCache,
    SegmentCacheService segmentCacheService,
    QuotaService quotaService,
    TranscodeService transcodeService,
    TranscodeApplicationService transcodeApplicationService,
    ManagedTranscodeApplicationService managedTranscodeApplicationService,
    WebhookResources webhookResources
) {
}
