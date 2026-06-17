package com.nyx.media.contracts;

public record LibraryScanState(
    LibraryScanStatus status,
    String lastScanStartedAt,
    String lastScanCompletedAt,
    String lastScanFailedAt,
    String lastScanError
) {
    public LibraryScanState() {
        this(LibraryScanStatus.IDLE, null, null, null, null);
    }

    public LibraryScanState {
        if (status == null) {
            status = LibraryScanStatus.IDLE;
        }
    }
}
