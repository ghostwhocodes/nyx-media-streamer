package com.nyx.transcode;

import com.nyx.transcode.contracts.TranscodeJob;

public interface TranscodeRuntimeController {
    boolean enqueue(TranscodeJob job);

    void cancel(String jobId);

    String activeStderr(String jobId);
}
