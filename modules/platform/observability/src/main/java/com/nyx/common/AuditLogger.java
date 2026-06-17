package com.nyx.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Structured audit logger for security-relevant destructive operations.
 * Log entries are written to the "audit" logger.
 */
public final class AuditLogger {
    private static final Logger LOGGER = LoggerFactory.getLogger("audit");

    private AuditLogger() {
    }

    public static void log(String method, String endpoint, String user, String resourceId, String result) {
        LOGGER.info(
            "method={} endpoint={} user={} resourceId={} result={}",
            method,
            endpoint,
            user,
            resourceId == null ? "-" : resourceId,
            result
        );
    }
}
