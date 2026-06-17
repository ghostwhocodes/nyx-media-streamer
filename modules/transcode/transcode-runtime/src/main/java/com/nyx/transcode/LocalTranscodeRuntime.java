package com.nyx.transcode;

import com.nyx.transcode.contracts.TranscodeJob;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LocalTranscodeRuntime implements TranscodeRuntimeController {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalTranscodeRuntime.class);

    private final ExecutorService workerExecutor;
    private final TranscodeRuntimeContext context;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final ArrayBlockingQueue<TranscodeJob> jobQueue;

    final ConcurrentHashMap<String, FFmpegProcess> activeProcesses = new ConcurrentHashMap<>();

    public LocalTranscodeRuntime(ExecutorService workerExecutor, TranscodeRuntimeContext context) {
        this.workerExecutor = Objects.requireNonNull(workerExecutor, "workerExecutor");
        this.context = Objects.requireNonNull(context, "context");
        this.jobQueue = new ArrayBlockingQueue<>(
            context.getConfig().getFfmpeg().getMaxConcurrentJobs() + context.getConfig().getFfmpeg().getMaxQueuedJobs()
        );

        for (int workerIndex = 0; workerIndex < context.getConfig().getFfmpeg().getMaxConcurrentJobs(); workerIndex += 1) {
            final int currentWorkerIndex = workerIndex;
            this.workerExecutor.submit(() -> runWorker(currentWorkerIndex));
        }
    }

    @Override
    public boolean enqueue(TranscodeJob job) {
        return !shuttingDown.get() && jobQueue.offer(job);
    }

    @Override
    public void cancel(String jobId) {
        FFmpegProcess process = activeProcesses.get(jobId);
        if (process != null) {
            process.cancelBlocking();
        }
    }

    @Override
    public String activeStderr(String jobId) {
        FFmpegProcess process = activeProcesses.get(jobId);
        return process == null ? null : process.stderrCapture();
    }

    public void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }

        LOGGER.info("Shutting down local transcode runtime...");
        jobQueue.clear();
        activeProcesses.forEach((jobId, process) -> {
            LOGGER.info("Cancelling active job: {}", jobId);
            process.cancelBlocking();
        });
        workerExecutor.shutdown();
        try {
            if (!workerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                workerExecutor.shutdownNow();
            }
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            workerExecutor.shutdownNow();
        }
    }

    private void runWorker(int workerIndex) {
        while (!shuttingDown.get() || !jobQueue.isEmpty()) {
            TranscodeJob job;
            try {
                job = jobQueue.poll(250, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                break;
            }
            if (job == null) {
                continue;
            }
            processJob(job, workerIndex);
        }
    }

    private void processJob(TranscodeJob job, int workerIndex) {
        String jobId = job.getId();
        try {
            context.getJobOrchestrator().process(
                job,
                workerIndex,
                context.getJobRequests(),
                context.getJobProbeResults(),
                context.getSegmentOutputDirs(),
                context.getJobOutputSizes(),
                context.getQuotaCancelledJobs(),
                activeProcesses,
                context.getConsecutiveFailures(),
                context.getSegmentCache(),
                context::invalidateManifest,
                context::emitRuntimeEvent
            );
            if (context.getConsecutiveFailures().get() >= context.getConfig().getTranscode().getCircuitBreakerThreshold()) {
                LOGGER.warn(
                    "Circuit breaker open: {} consecutive failures (threshold: {})",
                    context.getConsecutiveFailures().get(),
                    context.getConfig().getTranscode().getCircuitBreakerThreshold()
                );
            }
        } finally {
            activeProcesses.remove(jobId);
            context.getQuotaCancelledJobs().remove(jobId);
            context.getSegmentCache().startGracePeriod(jobId);
            context.invalidateManifest(jobId);
            context.scheduleDeferredCleanup(jobId);
        }
    }
}
