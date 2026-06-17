package com.nyx.config;

public record SanitizedBackup(
    boolean enabled,
    int scheduleIntervalMinutes,
    int retainCount
) {
    public SanitizedBackup() {
        this(false, 0, 5);
    }
}
