package com.nyx.transcode.contracts;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record TranscodeJobListing(
    @JsonProperty("jobs") List<TranscodeJob> jobs,
    @JsonProperty("total") int total,
    @JsonProperty("page") int page,
    @JsonProperty("limit") int limit,
    @JsonProperty("status_filter") JobStatus statusFilter,
    @JsonProperty("since_minutes") Integer sinceMinutes
) {
    public TranscodeJobListing(List<TranscodeJob> jobs, int total, int page, int limit) {
        this(jobs, total, page, limit, null, null);
    }

    public TranscodeJobListing {
        jobs = jobs == null ? List.of() : List.copyOf(jobs);
    }

    @JsonIgnore
    public List<TranscodeJob> getJobs() {
        return jobs;
    }

    @JsonIgnore
    public int getTotal() {
        return total;
    }

    @JsonIgnore
    public int getPage() {
        return page;
    }

    @JsonIgnore
    public int getLimit() {
        return limit;
    }

    @JsonIgnore
    public JobStatus getStatusFilter() {
        return statusFilter;
    }

    @JsonIgnore
    public Integer getSinceMinutes() {
        return sinceMinutes;
    }
}
