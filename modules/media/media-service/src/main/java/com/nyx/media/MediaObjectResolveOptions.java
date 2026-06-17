package com.nyx.media;

import com.nyx.media.contracts.MediaKind;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public final class MediaObjectResolveOptions {
    public static final MediaObjectResolveOptions DEFAULT = new MediaObjectResolveOptions();
    public static final MediaObjectResolveOptions AUDIO_ONLY = new MediaObjectResolveOptions(Set.of(MediaKind.AUDIO));
    public static final MediaObjectResolveOptions IMAGE_ONLY = new MediaObjectResolveOptions(Set.of(MediaKind.IMAGE));
    public static final MediaObjectResolveOptions VIDEO_ONLY = new MediaObjectResolveOptions(Set.of(MediaKind.VIDEO));
    public static final MediaObjectResolveOptions IMAGE_OR_VIDEO = new MediaObjectResolveOptions(
        Set.of(MediaKind.IMAGE, MediaKind.VIDEO)
    );

    private final Set<MediaKind> allowedKinds;
    private final boolean bootstrapPrimaryThumbnail;

    public MediaObjectResolveOptions() {
        this(null, true);
    }

    public MediaObjectResolveOptions(Set<MediaKind> allowedKinds) {
        this(allowedKinds, true);
    }

    public MediaObjectResolveOptions(Set<MediaKind> allowedKinds, boolean bootstrapPrimaryThumbnail) {
        this.allowedKinds = allowedKinds == null
            ? Set.copyOf(EnumSet.allOf(MediaKind.class))
            : Set.copyOf(allowedKinds);
        this.bootstrapPrimaryThumbnail = bootstrapPrimaryThumbnail;
    }

    public Set<MediaKind> getAllowedKinds() {
        return allowedKinds;
    }

    public boolean isBootstrapPrimaryThumbnail() {
        return bootstrapPrimaryThumbnail;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MediaObjectResolveOptions that)) {
            return false;
        }
        return bootstrapPrimaryThumbnail == that.bootstrapPrimaryThumbnail
            && allowedKinds.equals(that.allowedKinds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(allowedKinds, bootstrapPrimaryThumbnail);
    }

    @Override
    public String toString() {
        return "MediaObjectResolveOptions{" +
            "allowedKinds=" + allowedKinds +
            ", bootstrapPrimaryThumbnail=" + bootstrapPrimaryThumbnail +
            '}';
    }
}
