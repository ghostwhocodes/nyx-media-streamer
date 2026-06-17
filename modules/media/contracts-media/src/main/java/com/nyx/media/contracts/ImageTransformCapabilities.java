package com.nyx.media.contracts;

import java.util.Set;

public record ImageTransformCapabilities(
    Boolean supportsWidth,
    Boolean supportsHeight,
    Boolean supportsMaxWidth,
    Boolean supportsMaxHeight,
    Boolean supportsQuality,
    Set<ImageTransformFit> supportedFits,
    Boolean privacyStrippedByDefault
) {
    private static final Set<ImageTransformFit> DEFAULT_FITS =
        Set.of(ImageTransformFit.CONTAIN, ImageTransformFit.COVER, ImageTransformFit.FILL);

    public ImageTransformCapabilities() {
        this(true, true, true, true, true, DEFAULT_FITS, true);
    }

    public ImageTransformCapabilities {
        if (supportsWidth == null) {
            supportsWidth = true;
        }
        if (supportsHeight == null) {
            supportsHeight = true;
        }
        if (supportsMaxWidth == null) {
            supportsMaxWidth = true;
        }
        if (supportsMaxHeight == null) {
            supportsMaxHeight = true;
        }
        if (supportsQuality == null) {
            supportsQuality = true;
        }
        if (supportedFits == null) {
            supportedFits = DEFAULT_FITS;
        } else {
            supportedFits = ContractCollections.immutableSet(supportedFits);
        }
        if (privacyStrippedByDefault == null) {
            privacyStrippedByDefault = true;
        }
    }
}
