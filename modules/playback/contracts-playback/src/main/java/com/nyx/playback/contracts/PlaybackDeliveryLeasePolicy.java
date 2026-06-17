package com.nyx.playback.contracts;

public record PlaybackDeliveryLeasePolicy(
    String leaseKey,
    long idleTtlMillis,
    boolean reuseStartingSessions,
    boolean reuseReadyFileSessions,
    boolean closeDuplicateSessions,
    boolean closeOnStartupFailure,
    boolean leaseOpenedSession
) {
    public PlaybackDeliveryLeasePolicy() {
        this(null, 0L, false, false, true, false, false);
    }

    public PlaybackDeliveryLeasePolicy(String leaseKey, long idleTtlMillis) {
        this(leaseKey, idleTtlMillis, true, true, true, false, true);
    }

    public PlaybackDeliveryLeasePolicy {
        if (idleTtlMillis < 0L) {
            throw new IllegalArgumentException("idleTtlMillis must not be negative");
        }
    }

    public boolean enabled() {
        return leaseKey != null && !leaseKey.isBlank() && idleTtlMillis > 0L;
    }
}
