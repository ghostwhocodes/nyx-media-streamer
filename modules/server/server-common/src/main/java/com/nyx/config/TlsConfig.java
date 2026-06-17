package com.nyx.config;

import java.util.Objects;

public record TlsConfig(
    boolean enabled,
    String keystorePath,
    String keystorePassword,
    String keyAlias,
    String keyPassword,
    int port
) {
    public TlsConfig {
        keystorePath = keystorePath == null ? "" : keystorePath;
        keystorePassword = keystorePassword == null ? "" : keystorePassword;
        keyAlias = keyAlias == null ? "nyx" : keyAlias;
        keyPassword = keyPassword == null ? "" : keyPassword;
    }

    public TlsConfig() {
        this(false, "", "", "nyx", "", 8443);
    }

    public boolean getEnabled() {
        return enabled;
    }

    public String getKeystorePath() {
        return keystorePath;
    }

    public String getKeystorePassword() {
        return keystorePassword;
    }

    public String getKeyAlias() {
        return keyAlias;
    }

    public String getKeyPassword() {
        return keyPassword;
    }

    public int getPort() {
        return port;
    }
}
