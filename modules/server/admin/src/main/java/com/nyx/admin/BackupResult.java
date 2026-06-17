package com.nyx.admin;

import java.util.List;

public record BackupResult(
    String timestamp,
    List<String> databases,
    long totalBytes,
    String backupDir
) {
}
