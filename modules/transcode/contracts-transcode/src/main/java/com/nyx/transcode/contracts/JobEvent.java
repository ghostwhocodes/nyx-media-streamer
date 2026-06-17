package com.nyx.transcode.contracts;

public sealed interface JobEvent permits JobEvent.Progress, JobEvent.Segment, JobEvent.Complete, JobEvent.Retry, JobEvent.Error {
    String jobId();

    record Progress(
        String jobId,
        double percent,
        double speed,
        double fps
    ) implements JobEvent {
        public String getJobId() {
            return jobId;
        }

        public double getPercent() {
            return percent;
        }

        public double getSpeed() {
            return speed;
        }

        public double getFps() {
            return fps;
        }
    }

    record Segment(
        String jobId,
        String name,
        String representationId,
        double durationSecs
    ) implements JobEvent {
        public String getJobId() {
            return jobId;
        }

        public String getName() {
            return name;
        }

        public String getRepresentationId() {
            return representationId;
        }

        public double getDurationSecs() {
            return durationSecs;
        }
    }

    record Complete(
        String jobId,
        double durationSecs,
        int segmentsTotal
    ) implements JobEvent {
        public String getJobId() {
            return jobId;
        }

        public double getDurationSecs() {
            return durationSecs;
        }

        public int getSegmentsTotal() {
            return segmentsTotal;
        }
    }

    record Retry(
        String jobId,
        int attempt,
        String reason
    ) implements JobEvent {
        public String getJobId() {
            return jobId;
        }

        public int getAttempt() {
            return attempt;
        }

        public String getReason() {
            return reason;
        }
    }

    record Error(
        String jobId,
        String code,
        String message
    ) implements JobEvent {
        public String getJobId() {
            return jobId;
        }

        public String getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }
    }
}
