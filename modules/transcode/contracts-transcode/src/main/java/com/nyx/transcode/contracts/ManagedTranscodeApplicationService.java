package com.nyx.transcode.contracts;

public interface ManagedTranscodeApplicationService extends TranscodeApplicationService {
    void shutdown();
}
