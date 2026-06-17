package com.nyx.admin;

public record BackupStatus(
    String lastBackupTimestamp,
    long lastBackupBytes,
    long successCount,
    long failureCount,
    String backupDir
) {
}
