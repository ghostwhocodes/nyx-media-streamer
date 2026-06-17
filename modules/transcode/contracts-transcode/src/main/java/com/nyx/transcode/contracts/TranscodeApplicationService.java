package com.nyx.transcode.contracts;

import com.nyx.playback.contracts.PlaybackDecision;
import com.nyx.playback.contracts.PlaybackRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

public interface TranscodeApplicationService {
    boolean getCircuitBreakerOpen();

    Consumer<JobEvent> getOnJobEvent();

    void setOnJobEvent(Consumer<? super JobEvent> onJobEvent);

    Flow.Publisher<JobEvent> eventFlow(String jobId);

    TranscodeJob submit(TranscodeRequest request, String batchId, String owner);

    TranscodeJob submit(PlaybackRequest request, PlaybackDecision decision, String batchId, String owner);

    default TranscodeJob submit(TranscodeRequest request) {
        return submit(request, null, null);
    }

    default TranscodeJob submit(TranscodeRequest request, String batchId) {
        return submit(request, batchId, null);
    }

    default TranscodeJob submit(PlaybackRequest request, PlaybackDecision decision) {
        return submit(request, decision, null, null);
    }

    default TranscodeJob submit(PlaybackRequest request, PlaybackDecision decision, String batchId) {
        return submit(request, decision, batchId, null);
    }

    BatchSubmitResponse submitBatch(List<TranscodeRequest> requests, String owner);

    default BatchSubmitResponse submitBatch(List<TranscodeRequest> requests) {
        return submitBatch(requests, null);
    }

    void cancel(String jobId, String owner);

    default void cancel(String jobId) {
        cancel(jobId, null);
    }

    BatchCancelResponse cancelBatch(String batchId, String owner);

    default BatchCancelResponse cancelBatch(String batchId) {
        return cancelBatch(batchId, null);
    }

    BatchStatusResponse getBatchStatus(String batchId, String owner);

    default BatchStatusResponse getBatchStatus(String batchId) {
        return getBatchStatus(batchId, null);
    }

    TranscodeJob getJob(String jobId);

    TranscodeJobListing listJobs(int page, int limit, String owner);

    default TranscodeJobListing listJobs() {
        return listJobs(1, 50, null);
    }

    default TranscodeJobListing listJobs(int page, int limit) {
        return listJobs(page, limit, null);
    }

    TranscodeJobListing listJobsFiltered(JobStatus status, Integer sinceMinutes, int page, int limit, String owner);

    default TranscodeJobListing listJobsFiltered(JobStatus status, Integer sinceMinutes) {
        return listJobsFiltered(status, sinceMinutes, 1, 50, null);
    }

    default TranscodeJobListing listJobsFiltered(JobStatus status, Integer sinceMinutes, int page, int limit) {
        return listJobsFiltered(status, sinceMinutes, page, limit, null);
    }

    String getLogs(String jobId);

    String getManifestMpd(String jobId);

    String getManifestM3u8(String jobId);

    String getSubtitlePlaylist(String jobId, int trackIndex);

    String getHlsMediaPlaylist(String jobId, String representationId);

    Path getSegmentOutputDir(String jobId);
}
