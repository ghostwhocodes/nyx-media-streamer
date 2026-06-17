package com.nyx.transcode.contracts;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record BatchCancelRequest(
    @JsonProperty("job_ids") List<String> jobIds
) {
    public BatchCancelRequest {
        jobIds = jobIds == null ? List.of() : List.copyOf(jobIds);
    }

    @JsonIgnore
    public List<String> getJobIds() {
        return jobIds;
    }
}
