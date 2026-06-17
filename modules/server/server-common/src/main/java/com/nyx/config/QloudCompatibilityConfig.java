package com.nyx.config;

public record QloudCompatibilityConfig(
    boolean enabled,
    String host,
    int port
) {
    public static final int DEFAULT_PORT_SENTINEL = -1;

    public QloudCompatibilityConfig() {
        this(false, null, DEFAULT_PORT_SENTINEL);
    }

    public static QloudCompatibilityConfig defaultsFor(String defaultHost, int mainPort) {
        return new QloudCompatibilityConfig(false, defaultHost, computedPort(mainPort));
    }

    public QloudCompatibilityConfig resolveDefaults(String defaultHost, int mainPort) {
        String resolvedHost = host == null ? defaultHost : host;
        int resolvedPort = port == DEFAULT_PORT_SENTINEL ? computedPort(mainPort) : port;
        return new QloudCompatibilityConfig(enabled, resolvedHost, resolvedPort);
    }

    private static int computedPort(int mainPort) {
        return mainPort == 0 ? 0 : mainPort + 1;
    }

    public boolean getEnabled() {
        return enabled;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }
}
