package com.nyx.playback.contracts;

public record PlaybackDeliverySessionRequest(
    String sessionId,
    String owner,
    PlaybackDeliveryReadiness readiness,
    PlaybackDeliveryStartupPolicy startupPolicy,
    PlaybackDeliveryLeasePolicy leasePolicy
) {
    public PlaybackDeliverySessionRequest(String sessionId, String owner, PlaybackDeliveryReadiness readiness) {
        this(sessionId, owner, readiness, new PlaybackDeliveryStartupPolicy(), new PlaybackDeliveryLeasePolicy());
    }

    public PlaybackDeliverySessionRequest {
        readiness = readiness == null ? new PlaybackDeliveryReadiness() : readiness;
        startupPolicy = startupPolicy == null ? new PlaybackDeliveryStartupPolicy() : startupPolicy;
        leasePolicy = leasePolicy == null ? new PlaybackDeliveryLeasePolicy() : leasePolicy;
    }
}
