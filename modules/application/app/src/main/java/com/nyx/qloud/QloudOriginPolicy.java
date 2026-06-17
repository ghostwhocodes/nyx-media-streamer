package com.nyx.qloud;

import com.nyx.config.QloudCompatibilityConfig;
import com.nyx.http.RoutingCall;
import java.util.Locale;

final class QloudOriginPolicy {
    private final QloudCompatibilityConfig config;

    QloudOriginPolicy(QloudCompatibilityConfig config) {
        this.config = config;
    }

    RequestOrigin from(RoutingCall call) {
        String scheme = normalizedScheme(firstForwardedHeader(call.getRequest().getHeaders().get("X-Forwarded-Proto")));
        Integer forwardedPort = explicitPort(firstForwardedHeader(call.getRequest().getHeaders().get("X-Forwarded-Port")));
        HostPort hostHeader = parseAuthority(call.getRequest().getHeaders().get("Host"));
        HostPort effectiveHostPort = hostHeader;

        HostPort forwardedHost = parseAuthority(firstForwardedHeader(call.getRequest().getHeaders().get("X-Forwarded-Host")));
        if (forwardedHost != null && forwardedHost.host() != null && !forwardedHost.host().isBlank()) {
            Integer hostHeaderPort = forwardedHostFallbackPort(forwardedHost, hostHeader);
            effectiveHostPort = new HostPort(
                forwardedHost.host(),
                forwardedHost.port() != null ? forwardedHost.port() : hostHeaderPort
            );
        }

        if (effectiveHostPort == null || effectiveHostPort.host() == null || effectiveHostPort.host().isBlank()) {
            return configuredOrigin(scheme);
        }

        String host = effectiveHostPort.host();
        if (host == null || host.isBlank()) {
            return configuredOrigin(scheme);
        }
        Integer explicitPort = forwardedPort != null ? forwardedPort : effectiveHostPort.port();
        int port = explicitPort != null ? explicitPort : defaultPort(scheme);
        return new RequestOrigin(scheme, host, port, explicitPort != null);
    }

    private RequestOrigin configuredOrigin(String scheme) {
        return new RequestOrigin(scheme, config.host(), config.port(), true);
    }

    static HostPort parseHostPort(String authority) {
        String host = authority.trim();
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            if (close >= 0) {
                if (close + 2 < host.length() && host.charAt(close + 1) == ':') {
                    return new HostPort(host.substring(1, close), explicitPort(host.substring(close + 2)));
                }
                return new HostPort(host.substring(1, close), null);
            }
        }
        int firstColon = host.indexOf(':');
        int lastColon = host.lastIndexOf(':');
        if (firstColon > 0 && firstColon == lastColon) {
            return new HostPort(host.substring(0, firstColon), explicitPort(host.substring(firstColon + 1)));
        }
        return new HostPort(host, null);
    }

    static HostPort parseAuthority(String authority) {
        if (authority == null || authority.isBlank()) {
            return null;
        }
        return parseHostPort(authority);
    }

    static boolean sameHost(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.toLowerCase(Locale.ROOT).equals(right.toLowerCase(Locale.ROOT));
    }

    static Integer forwardedHostFallbackPort(HostPort forwardedHost, HostPort hostHeader) {
        if (hostHeader == null) {
            return null;
        }
        if (sameHost(forwardedHost.host(), hostHeader.host())) {
            return hostHeader.port();
        }
        return isLoopbackHost(hostHeader.host()) ? null : hostHeader.port();
    }

    static boolean isLoopbackHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized)
            || "::1".equals(normalized)
            || "0:0:0:0:0:0:0:1".equals(normalized)
            || normalized.startsWith("127.");
    }

    static String normalizedScheme(String scheme) {
        if (scheme == null || scheme.isBlank()) {
            return "http";
        }
        return scheme.trim().toLowerCase(Locale.ROOT);
    }

    static int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    static Integer explicitPort(String raw) {
        try {
            int port = Integer.parseInt(raw);
            return port >= 0 && port <= 65_535 ? port : null;
        } catch (NumberFormatException | NullPointerException ignored) {
            return null;
        }
    }

    static String firstForwardedHeader(String value) {
        if (value == null) {
            return null;
        }
        int comma = value.indexOf(',');
        return (comma >= 0 ? value.substring(0, comma) : value).trim();
    }

    record HostPort(
        String host,
        Integer port
    ) {
    }

    record RequestOrigin(
        String scheme,
        String host,
        int port,
        boolean explicitPort
    ) {
        String baseUrl() {
            return scheme + "://" + authority();
        }

        private String authority() {
            String authorityHost = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
            return explicitPort ? authorityHost + ":" + port : authorityHost;
        }
    }
}
