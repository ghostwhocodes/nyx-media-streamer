package com.nyx.media;

import com.nyx.media.contracts.ImageTransformFit;
import com.nyx.media.contracts.ImageTransformResult;
import java.util.Locale;

public record ImageTransformPlan(
    int sourceWidth,
    int sourceHeight,
    int scaledWidth,
    int scaledHeight,
    int outputWidth,
    int outputHeight,
    int cropX,
    int cropY,
    String outputFormat,
    String outputExtension,
    String outputMimeType,
    Integer quality,
    ImageTransformFit fit
) {
    public ImageTransformPlan(
        int sourceWidth,
        int sourceHeight,
        int scaledWidth,
        int scaledHeight,
        int outputWidth,
        int outputHeight,
        String outputFormat,
        String outputExtension,
        String outputMimeType,
        Integer quality,
        ImageTransformFit fit
    ) {
        this(
            sourceWidth,
            sourceHeight,
            scaledWidth,
            scaledHeight,
            outputWidth,
            outputHeight,
            0,
            0,
            outputFormat,
            outputExtension,
            outputMimeType,
            quality,
            fit
        );
    }

    public ImageTransformPlan {
        if (fit == null) {
            fit = ImageTransformFit.CONTAIN;
        }
    }

    public boolean isRequiresResize() {
        return sourceWidth != scaledWidth || sourceHeight != scaledHeight;
    }

    public boolean getRequiresResize() {
        return isRequiresResize();
    }

    public boolean isRequiresCrop() {
        return cropX != 0 || cropY != 0 || scaledWidth != outputWidth || scaledHeight != outputHeight;
    }

    public boolean getRequiresCrop() {
        return isRequiresCrop();
    }

    public boolean isRequiresTransformation() {
        return isRequiresResize() || isRequiresCrop() || quality != null;
    }

    public boolean getRequiresTransformation() {
        return isRequiresTransformation();
    }

    public String getCacheKey() {
        return new StringBuilder()
            .append("fmt-").append(outputExtension)
            .append("_fit-").append(fit.name().toLowerCase(Locale.ROOT))
            .append("_out-").append(outputWidth).append('x').append(outputHeight)
            .append("_scaled-").append(scaledWidth).append('x').append(scaledHeight)
            .append("_crop-").append(cropX).append('x').append(cropY)
            .append("_q-").append(quality == null ? "default" : quality)
            .toString();
    }

    public ImageTransformResult toResult() {
        return new ImageTransformResult(outputWidth, outputHeight, outputMimeType, quality, fit, isRequiresTransformation(), true);
    }

    public int getSourceWidth() {
        return sourceWidth;
    }

    public int getSourceHeight() {
        return sourceHeight;
    }

    public int getScaledWidth() {
        return scaledWidth;
    }

    public int getScaledHeight() {
        return scaledHeight;
    }

    public int getOutputWidth() {
        return outputWidth;
    }

    public int getOutputHeight() {
        return outputHeight;
    }

    public int getCropX() {
        return cropX;
    }

    public int getCropY() {
        return cropY;
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

    public Integer getQuality() {
        return quality;
    }

    public ImageTransformFit getFit() {
        return fit;
    }
}
