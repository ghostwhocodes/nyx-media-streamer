package com.nyx;

import static com.nyx.config.ConfigLoader.loadProfileConfig;

import com.nyx.config.ServerConfig;
import java.io.File;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String profile = System.getenv("NYX_CONFIG_PROFILE");
        String resolvedProfile = profile == null || profile.isBlank() ? "dev" : profile;
        ServerConfig config = loadProfileConfig().toServerConfig();
        ConcurrentHashMap<String, String> runtimeUsers = new ConcurrentHashMap<>(config.getAuth().getUsers());

        if ("prod".equals(resolvedProfile)) {
            if (!config.getAuth().getEnabled()) {
                System.err.println("WARNING [prod]: auth is disabled. Set NYX_AUTH_ENABLED=true.");
            }
            if (!config.getTls().getEnabled()) {
                System.err.println("WARNING [prod]: TLS is disabled. Consider NYX_TLS_ENABLED=true.");
            }
        }

        var application = AppRouting.createApplicationServerGroup(config, runtimeUsers);
        startApplication(application, config);
    }

    private static void configureTls(io.javalin.Javalin app, ServerConfig config) throws Exception {
        var tls = config.getTls();
        if (!tls.getEnabled()) {
            return;
        }
        if (tls.getKeystorePath().isBlank()) {
            System.err.println(
                "WARNING: NYX_TLS_ENABLED=true but NYX_TLS_KEYSTORE is not set. "
                    + "Falling back to HTTP-only. Provide a JKS keystore to enable HTTPS."
            );
            return;
        }

        KeyStore loadedKeyStore = KeyStore.getInstance("JKS");
        try (var input = Files.newInputStream(new File(tls.getKeystorePath()).toPath())) {
            loadedKeyStore.load(input, tls.getKeystorePassword().toCharArray());
        }

        app.unsafe.jetty.addConnector((Server server, HttpConfiguration httpConfig) -> {
            HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
            httpsConfig.addCustomizer(new SecureRequestCustomizer());
            SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
            sslContextFactory.setKeyStore(loadedKeyStore);
            sslContextFactory.setKeyStorePassword(tls.getKeystorePassword());
            sslContextFactory.setKeyManagerPassword(tls.getKeyPassword());
            sslContextFactory.setCertAlias(tls.getKeyAlias());

            ServerConnector connector = new ServerConnector(
                server,
                new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()),
                new HttpConnectionFactory(httpsConfig)
            );
            connector.setHost(config.getHost());
            connector.setPort(tls.getPort());
            return connector;
        });
    }

    static void startApplication(ApplicationServerGroup application, ServerConfig config) throws Exception {
        try {
            configureTls(application.mainApp(), config);
            application.start();
        } catch (Exception error) {
            closeOnStartupFailure(application, error);
            throw error;
        } catch (Error error) {
            closeOnStartupFailure(application, error);
            throw error;
        }
    }

    private static void closeOnStartupFailure(ApplicationServerGroup application, Throwable error) {
        if (application.closed()) {
            return;
        }
        try {
            application.close();
        } catch (Throwable cleanupFailure) {
            error.addSuppressed(cleanupFailure);
        }
    }
}
