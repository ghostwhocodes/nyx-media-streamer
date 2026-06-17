package com.nyx.config;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class ConfigService {
    private final ServerConfig config;
    private final ConfigStore configStore;
    private final ConcurrentHashMap<String, String> runtimeUsers;
    private final ReentrantLock userMutationLock = new ReentrantLock();

    private volatile List<String> runtimeCorsOrigins;

    public ConfigService(ServerConfig config) {
        this(config, NoOpConfigStore.INSTANCE);
    }

    public ConfigService(ServerConfig config, ConfigStore configStore) {
        this(config, configStore, new ConcurrentHashMap<>(config.getAuth().getUsers()));
    }

    public ConfigService(
        ServerConfig config,
        ConfigStore configStore,
        ConcurrentHashMap<String, String> runtimeUsers
    ) {
        this.config = config;
        this.configStore = configStore;
        this.runtimeUsers = runtimeUsers;
        this.runtimeCorsOrigins = List.copyOf(config.getCorsOrigins());
    }

    public void initialize() {
        configStore.getAllUsers().forEach(runtimeUsers::put);
        String raw = configStore.getOverrides().get("corsOrigins");
        if (raw != null) {
            runtimeCorsOrigins = Arrays.stream(raw.split(","))
                .filter(entry -> !entry.isBlank())
                .toList();
        }
    }

    public Map<String, String> getUsers() {
        return Map.copyOf(runtimeUsers);
    }

    public boolean createUser(String username, String password) {
        userMutationLock.lock();
        try {
            if (runtimeUsers.containsKey(username)) {
                return false;
            }

            String hash = AuthUtils.hashPassword(password);
            configStore.upsertUser(username, hash);
            runtimeUsers.put(username, hash);
            return true;
        } finally {
            userMutationLock.unlock();
        }
    }

    public boolean deleteUser(String username) {
        userMutationLock.lock();
        try {
            if (!runtimeUsers.containsKey(username)) {
                return false;
            }

            boolean deleted = configStore.deleteUser(username);
            if (!deleted && configStore != NoOpConfigStore.INSTANCE) {
                return false;
            }

            runtimeUsers.remove(username);
            return true;
        } finally {
            userMutationLock.unlock();
        }
    }

    public void updateCorsOrigins(List<String> origins) {
        configStore.setOverride("corsOrigins", String.join(",", origins));
        runtimeCorsOrigins = List.copyOf(origins);
    }

    public SanitizedConfig getSanitizedConfig() {
        return new SanitizedConfig(
            config.getHost(),
            config.getPort(),
            runtimeCorsOrigins,
            config.getMediaRoots().stream()
                .map(root -> new SanitizedMediaRoot(root.getPath().toString(), root.getFilesystem()))
                .toList(),
            new SanitizedAuth(
                config.getAuth().getEnabled(),
                !config.getAuth().getToken().isBlank(),
                !config.getAuth().getTokens().isEmpty(),
                List.copyOf(config.getAuth().getTokens().values()),
                List.copyOf(runtimeUsers.keySet())
            ),
            new SanitizedTranscode(
                config.getTranscode().getDefaultFormat(),
                config.getFfmpeg().getMaxConcurrentJobs(),
                config.getTranscode().getSegmentCacheGracePeriodMinutes()
            ),
            new SanitizedThumbnails(
                config.getThumbnails().getSizes(),
                config.getThumbnails().getVideoOffsetPercent(),
                config.getThumbnails().getMaxCacheSizeMB()
            ),
            new SanitizedQuota(
                config.getQuota().getEnabled(),
                config.getQuota().getDefaultMaxConcurrentJobs(),
                config.getQuota().getDefaultMaxRequestsPerMinute()
            ),
            new SanitizedRateLimit(
                config.getRateLimit().getEnabled(),
                config.getRateLimit().getRequestsPerSecond(),
                config.getRateLimit().getWindowSeconds(),
                config.getRateLimit().getBurstSize()
            ),
            new SanitizedCsrf(config.getCsrf().getEnabled()),
            new SanitizedTls(
                config.getTls().getEnabled(),
                config.getTls().getPort(),
                !config.getTls().getKeystorePath().isBlank()
            ),
            new SanitizedWebhooks(
                config.getWebhooks().getEnabled(),
                config.getWebhooks().getMaxRetries(),
                config.getWebhooks().getDeliveryRetentionDays()
            ),
            new SanitizedBackup(
                config.getBackup().getEnabled(),
                config.getBackup().getScheduleIntervalMinutes(),
                config.getBackup().getRetainCount()
            )
        );
    }

    public List<String> listUsers() {
        return List.copyOf(runtimeUsers.keySet());
    }
}
