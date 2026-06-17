package com.nyx.config;

public record BackupConfig(
    boolean enabled,
    String dir,
    int scheduleIntervalMinutes,
    int retainCount
) {
    public BackupConfig {
        dir = dir == null ? "" : dir;
    }

    public BackupConfig() {
        this(false, "", 0, 5);
    }

    public boolean getEnabled() {
        return enabled;
    }

    public String getDir() {
        return dir;
    }

    public int getScheduleIntervalMinutes() {
        return scheduleIntervalMinutes;
    }

    public int getRetainCount() {
        return retainCount;
    }
}
