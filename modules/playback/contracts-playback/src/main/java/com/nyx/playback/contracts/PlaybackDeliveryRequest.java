package com.nyx.playback.contracts;

public record PlaybackDeliveryRequest(
    PlaybackRequest playbackRequest,
    String owner,
    PlaybackDeliveryReadiness readiness,
    PlaybackDeliveryStartupPolicy startupPolicy,
    PlaybackDeliveryLeasePolicy leasePolicy
) {
    public PlaybackDeliveryRequest(PlaybackRequest playbackRequest, String owner, PlaybackDeliveryReadiness readiness) {
        this(playbackRequest, owner, readiness, new PlaybackDeliveryStartupPolicy(), new PlaybackDeliveryLeasePolicy());
    }

    public PlaybackDeliveryRequest {
        readiness = readiness == null ? new PlaybackDeliveryReadiness() : readiness;
        startupPolicy = startupPolicy == null ? new PlaybackDeliveryStartupPolicy() : startupPolicy;
        leasePolicy = leasePolicy == null ? new PlaybackDeliveryLeasePolicy() : leasePolicy;
    }
}
