package com.nyx.transcode.webhook;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.transcode.contracts.webhook.WebhookUrlValidator;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WebhookUrlValidatorTest {
    private final WebhookUrlValidator validator = new WebhookUrlValidator();

    @Test
    void validHttpsUrlSucceeds() {
        assertDoesNotThrow(() -> validator.validateOrThrow("https://example.com/webhook"));
    }

    @Test
    void validHttpUrlSucceeds() {
        assertDoesNotThrow(() -> validator.validateOrThrow("http://example.com/webhook"));
    }

    @Test
    void ftpUrlRejected() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> validator.validateOrThrow("ftp://example.com/file")
        );
        assertTrue(exception.getMessage().contains("HTTP or HTTPS"));
    }

    @Test
    void blankHostRejected() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> validator.validateOrThrow("http:///path")
        );
        assertTrue(exception.getMessage().contains("blank host"));
    }

    @Test
    void loopbackIpv4Rejected() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> validator.validateOrThrow("http://127.0.0.1/hook")
        );
        assertTrue(exception.getMessage().contains("loopback"));
    }

    @Test
    void loopbackIpv6Rejected() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> validator.validateOrThrow("http://[::1]/hook")
        );
        assertTrue(exception.getMessage().contains("loopback"));
    }

    @Test
    void private10RangeRejected() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> validator.validateOrThrow("http://10.0.0.1/hook")
        );
        assertTrue(exception.getMessage().contains("private"));
    }

    @Test
    void private17216RangeRejected() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> validator.validateOrThrow("http://172.16.0.1/hook")
        );
        assertTrue(exception.getMessage().contains("private"));
    }

    @Test
    void private192168RangeRejected() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> validator.validateOrThrow("http://192.168.1.1/hook")
        );
        assertTrue(exception.getMessage().contains("private"));
    }

    @Test
    void linkLocalCloudMetadataRejected() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> validator.validateOrThrow("http://169.254.169.254/latest/meta-data")
        );
        assertTrue(exception.getMessage().contains("link-local"));
    }

    @Test
    void zeroNetworkRejected() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> validator.validateOrThrow("http://0.0.0.0/hook")
        );
        assertTrue(exception.getMessage().contains("reserved") || exception.getMessage().contains("wildcard"));
    }

    @Test
    void unresolvableHostnameRejected() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> validator.validateOrThrow("https://this-host-definitely-does-not-exist-xyz123.invalid/hook")
        );
        assertTrue(exception.getMessage().contains("resolve"));
    }

    @Test
    void allowlistedHostBypassesPrivateIpCheck() {
        WebhookUrlValidator allowedValidator = new WebhookUrlValidator(Set.of("127.0.0.1"));
        assertDoesNotThrow(() -> allowedValidator.validateOrThrow("http://127.0.0.1/hook"));
    }

    @Test
    void invalidUrlFormatRejected() {
        assertThrows(IllegalArgumentException.class, () -> validator.validateOrThrow("not a url at all"));
    }

    @Test
    void localhostRejected() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> validator.validateOrThrow("http://localhost/hook")
        );
        assertTrue(exception.getMessage().contains("loopback"));
    }
}
