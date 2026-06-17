package com.nyx.playback.contracts;

import com.nyx.stream.representation.contracts.StreamingProtocol;
import java.util.Set;

public record PlaybackDeliveryReadiness(
    Set<PlaybackDeliveryRequirement> requirements,
    StreamingProtocol manifestProtocol
) {
    public PlaybackDeliveryReadiness() {
        this(Set.of(), null);
    }

    public PlaybackDeliveryReadiness(Set<PlaybackDeliveryRequirement> requirements) {
        this(requirements, inferManifestProtocol(requirements));
    }

    public PlaybackDeliveryReadiness {
        requirements = PlaybackContractSupport.immutableSet(requirements);
        manifestProtocol = manifestProtocol == null ? inferManifestProtocol(requirements) : manifestProtocol;
    }

    public static PlaybackDeliveryReadiness hlsManifest() {
        return new PlaybackDeliveryReadiness(
            Set.of(PlaybackDeliveryRequirement.HLS_MANIFEST),
            StreamingProtocol.HLS
        );
    }

    public static PlaybackDeliveryReadiness hlsManifestWithBackingJob() {
        return new PlaybackDeliveryReadiness(
            Set.of(PlaybackDeliveryRequirement.HLS_MANIFEST, PlaybackDeliveryRequirement.BACKING_JOB),
            StreamingProtocol.HLS
        );
    }

    public static PlaybackDeliveryReadiness dashManifest() {
        return new PlaybackDeliveryReadiness(
            Set.of(PlaybackDeliveryRequirement.DASH_MANIFEST),
            StreamingProtocol.DASH
        );
    }

    public static PlaybackDeliveryReadiness directFile() {
        return new PlaybackDeliveryReadiness(
            Set.of(PlaybackDeliveryRequirement.DIRECT_FILE),
            StreamingProtocol.FILE
        );
    }

    public boolean requires(PlaybackDeliveryRequirement requirement) {
        return requirements.contains(requirement);
    }

    private static StreamingProtocol inferManifestProtocol(Set<PlaybackDeliveryRequirement> requirements) {
        if (requirements == null) {
            return null;
        }
        if (requirements.contains(PlaybackDeliveryRequirement.HLS_MANIFEST)) {
            return StreamingProtocol.HLS;
        }
        if (requirements.contains(PlaybackDeliveryRequirement.DASH_MANIFEST)) {
            return StreamingProtocol.DASH;
        }
        if (requirements.contains(PlaybackDeliveryRequirement.DIRECT_FILE)) {
            return StreamingProtocol.FILE;
        }
        return null;
    }
}
