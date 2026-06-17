package com.nyx.ffmpeg;

public record VideoPreviewPlan(
    int sourceWidth,
    int sourceHeight,
    long seekMillis,
    int outputWidth,
    int outputHeight,
    String outputFormat,
    String outputExtension,
    String outputMimeType
) {
    public VideoPreviewPlan(
        int sourceWidth,
        int sourceHeight,
        long seekMillis,
        int outputWidth,
        int outputHeight
    ) {
        this(sourceWidth, sourceHeight, seekMillis, outputWidth, outputHeight, "jpeg", "jpg", "image/jpeg");
    }

    public String getCacheKey() {
        return "ms-" + seekMillis
            + "_out-" + outputWidth + 'x' + outputHeight
            + "_fmt-" + outputExtension;
    }

    public int getSourceWidth() {
        return sourceWidth;
    }

    public int getSourceHeight() {
        return sourceHeight;
    }

    public long getSeekMillis() {
        return seekMillis;
    }

    public int getOutputWidth() {
        return outputWidth;
    }

    public int getOutputHeight() {
        return outputHeight;
    }

    public String getOutputFormat() {
        return outputFormat;
    }

    public String getOutputExtension() {
        return outputExtension;
    }

    public String getOutputMimeType() {
        return outputMimeType;
    }
}
