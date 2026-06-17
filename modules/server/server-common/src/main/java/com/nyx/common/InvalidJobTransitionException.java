package com.nyx.common;

import com.nyx.transcode.contracts.JobStatus;
import java.util.Map;

public class InvalidJobTransitionException extends NyxException {
    public InvalidJobTransitionException(JobStatus from, JobStatus to) {
        super(ErrorCode.INVALID_REQUEST, "Cannot transition job from " + from + " to " + to, Map.of(), null);
    }
}
