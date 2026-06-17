package com.nyx;

import com.nyx.admin.MetricsService;
import com.nyx.config.ServerConfig;
import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Singleton;

@Singleton
@Component(modules = AppCompositionModule.class)
public interface AppComponent {
    PrometheusMeterRegistry metricsRegistry();

    ScheduledExecutorService cleanupScheduler();

    ExecutorService backgroundExecutor();

    MetricsService metricsService();

    @Component.Factory
    interface Factory {
        AppComponent create(
            @BindsInstance ServerConfig serverConfig,
            @BindsInstance ConcurrentHashMap<String, String> runtimeUsers
        );
    }
}

@Module
final class AppCompositionModule {
    private AppCompositionModule() {}

    @Provides
    @Singleton
    static PrometheusMeterRegistry providePrometheusRegistry() {
        return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }

    @Provides
    @Singleton
    static MetricsService provideMetricsService(PrometheusMeterRegistry registry) {
        return new MetricsService(registry);
    }

    @Provides
    @Singleton
    static ScheduledExecutorService provideCleanupScheduler() {
        return Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "nyx-cache-cleanup");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Provides
    @Singleton
    static ExecutorService provideBackgroundExecutor() {
        int workerCount = Math.max(4, Runtime.getRuntime().availableProcessors() / 2);
        return Executors.newFixedThreadPool(workerCount, runnable -> {
            Thread thread = new Thread(runnable, "nyx-service-worker");
            thread.setDaemon(true);
            return thread;
        });
    }
}
