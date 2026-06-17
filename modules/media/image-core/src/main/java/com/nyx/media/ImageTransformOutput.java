package com.nyx.media;

public record ImageTransformOutput(
    byte[] bytes,
    ImageTransformPlan plan
) {
    public byte[] getBytes() {
        return bytes;
    }

    public ImageTransformPlan getPlan() {
        return plan;
    }
}
