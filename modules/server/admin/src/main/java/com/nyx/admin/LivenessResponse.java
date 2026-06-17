package com.nyx.admin;

public record LivenessResponse(String status) {
    public LivenessResponse() {
        this("alive");
    }
}
