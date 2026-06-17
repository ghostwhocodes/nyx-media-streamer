package com.nyx.common;

import org.junit.jupiter.api.Test;

class AuditLoggerTest {
    @Test
    void logDoesNotThrowForNormalInput() {
        AuditLogger.log("DELETE", "/api/v1/items/123", "alice", "123", "204");
    }

    @Test
    void logHandlesAnonymousUser() {
        AuditLogger.log("POST", "/api/v1/items", "anonymous", null, "201");
    }

    @Test
    void logAcceptsNullResourceId() {
        AuditLogger.log("PATCH", "/api/v1/config", "bob", null, "200");
    }

    @Test
    void logAcceptsErrorResult() {
        AuditLogger.log("DELETE", "/api/v1/items/123", "alice", "123", "error");
    }

    @Test
    void logHandlesEmptyStringsWithoutThrowing() {
        AuditLogger.log("", "", "", "", "");
    }

    @Test
    void logHandlesAllMutatingMethods() {
        AuditLogger.log("POST", "/p", "u", "1", "201");
        AuditLogger.log("PUT", "/p", "u", "1", "200");
        AuditLogger.log("PATCH", "/p", "u", "1", "200");
        AuditLogger.log("DELETE", "/p", "u", "1", "204");
    }
}
