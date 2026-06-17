package com.nyx.transcode.contracts;

import java.util.List;

public interface TranscodeJobStore {
    TranscodeJob create(TranscodeJob job);

    TranscodeJob createWithQuotaCheck(TranscodeJob job, int maxConcurrent, Long maxStorageBytes);

    default TranscodeJob createWithQuotaCheck(TranscodeJob job, int maxConcurrent) {
        return createWithQuotaCheck(job, maxConcurrent, null);
    }

    TranscodeJob getById(String id);

    void updateStatus(String id, JobStatus newStatus);

    void updateProgress(String id, int segmentsProduced);

    void updateRetryCount(String id, int count);

    void storeStderr(String id, String initial, String fallback);

    TranscodeJob findActiveBySpecKey(String specKey);

    List<TranscodeJob> listFiltered(JobStatus status, Integer sinceMinutes, int limit, long offset, String owner);

    default List<TranscodeJob> listFiltered(JobStatus status, Integer sinceMinutes) {
        return listFiltered(status, sinceMinutes, 50, 0L, null);
    }

    default List<TranscodeJob> listFiltered(JobStatus status, Integer sinceMinutes, int limit, long offset) {
        return listFiltered(status, sinceMinutes, limit, offset, null);
    }

    int countFiltered(JobStatus status, Integer sinceMinutes, String owner);

    default int countFiltered(JobStatus status, Integer sinceMinutes) {
        return countFiltered(status, sinceMinutes, null);
    }

    List<TranscodeJob> listActive();

    List<TranscodeJob> listByBatchId(String batchId);

    int countActiveByOwner(String ownerId);

    void updateOutputSize(String id, long sizeBytes);

    long sumStorageByOwner(String ownerId);

    int countAll(String owner);

    default int countAll() {
        return countAll(null);
    }

    List<TranscodeJob> listRecent(int limit, long offset, String owner);

    default List<TranscodeJob> listRecent() {
        return listRecent(50, 0L, null);
    }

    default List<TranscodeJob> listRecent(int limit) {
        return listRecent(limit, 0L, null);
    }

    default List<TranscodeJob> listRecent(int limit, long offset) {
        return listRecent(limit, offset, null);
    }
}
