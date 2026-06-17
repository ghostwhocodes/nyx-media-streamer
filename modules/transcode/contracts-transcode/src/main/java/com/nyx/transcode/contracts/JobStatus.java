package com.nyx.transcode.contracts;

public enum JobStatus {
    QUEUED,
    PROBING,
    TRANSCODING,
    RETRYING,
    COMPLETED,
    FAILED,
    CANCELLED
}
