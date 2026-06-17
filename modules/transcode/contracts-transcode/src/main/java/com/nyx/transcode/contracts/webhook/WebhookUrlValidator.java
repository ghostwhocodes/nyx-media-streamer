package com.nyx.transcode.contracts.webhook;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

public final class WebhookUrlValidator {
    private final Set<String> allowedHosts;

    public WebhookUrlValidator() {
        this(Set.of());
    }

    public WebhookUrlValidator(Set<String> allowedHosts) {
        this.allowedHosts = allowedHosts == null ? Set.of() : Set.copyOf(allowedHosts);
    }

    public void validate(String rawUrl) {
        validateOrThrow(rawUrl);
    }

    public void validateOrThrow(String rawUrl) {
        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException ignored) {
            throw new IllegalArgumentException("Invalid webhook URL: " + rawUrl);
        }

        validateProtocol(uri);
        String host = validateHost(uri, rawUrl);
        if (allowedHosts.contains(host)) {
            return;
        }
        validateAddresses(resolveHost(host));
    }

    private static void validateProtocol(URI uri) {
        String scheme = uri.getScheme();
        String normalized = scheme == null ? null : scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(normalized) && !"https".equals(normalized)) {
            throw new IllegalArgumentException("Webhook URL must use HTTP or HTTPS");
        }
    }

    private static String validateHost(URI uri, String rawUrl) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Webhook URL has blank host: " + rawUrl);
        }
        return host;
    }

    private static InetAddress[] resolveHost(String host) {
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException ignored) {
            throw new IllegalArgumentException("Cannot resolve webhook host: " + host);
        }
    }

    private static void validateAddresses(InetAddress[] addresses) {
        for (InetAddress address : addresses) {
            String reason = classifyAddress(address);
            if (reason != null) {
                throw new IllegalArgumentException("Webhook URL resolves to " + reason + " address");
            }
        }
    }

    private static String classifyAddress(InetAddress address) {
        if (address.isLoopbackAddress()) {
            return "loopback";
        }
        if (address.isSiteLocalAddress()) {
            return "private";
        }
        if (address.isLinkLocalAddress()) {
            return "link-local";
        }
        if (address.isMulticastAddress()) {
            return "multicast";
        }
        if (address.isAnyLocalAddress()) {
            return "wildcard";
        }
        if (isZeroNetwork(address)) {
            return "reserved";
        }
        return null;
    }

    private static boolean isZeroNetwork(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && bytes[0] == 0;
    }
}
