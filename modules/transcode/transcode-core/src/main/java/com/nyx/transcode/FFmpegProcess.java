package com.nyx.transcode;

import com.nyx.concurrent.BlockingStream;
import com.nyx.ffmpeg.FFmpegCommand;
import com.nyx.ffmpeg.FFmpegProgressParser;
import com.nyx.ffmpeg.TranscodeProgress;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

public final class FFmpegProcess {
    public static final int MAX_STDERR_BYTES = 65_536;
    private static final long CANCEL_POLL_INTERVAL_MS = 100L;

    private final FFmpegCommand command;
    private final String ffmpegPath;
    private final ExecutorService ioExecutor;
    private final Runnable onProcessStart;
    private final Runnable onProcessFinish;
    private final long cancelGracePeriodMs;
    private final BiConsumer<Long, Boolean> onProcessComplete;
    private final BlockingState<ProcessState> state = new BlockingState<>(ProcessState.IDLE);

    private Process process;
    private final StringBuilder stderrBuilder = new StringBuilder();
    private Future<?> stderrTask;
    private Future<ProcessState> completionTask;
    private BufferedReader progressReader;

    public FFmpegProcess(FFmpegCommand command, String ffmpegPath, ExecutorService ioExecutor) {
        this(command, ffmpegPath, ioExecutor, null, null, 5_000L, null);
    }

    public FFmpegProcess(FFmpegCommand command, String ffmpegPath, ExecutorService ioExecutor, long cancelGracePeriodMs) {
        this(command, ffmpegPath, ioExecutor, null, null, cancelGracePeriodMs, null);
    }

    public FFmpegProcess(
        FFmpegCommand command,
        String ffmpegPath,
        ExecutorService ioExecutor,
        Runnable onProcessStart,
        Runnable onProcessFinish,
        long cancelGracePeriodMs,
        BiConsumer<Long, Boolean> onProcessComplete
    ) {
        this.command = Objects.requireNonNull(command, "command");
        this.ffmpegPath = Objects.requireNonNull(ffmpegPath, "ffmpegPath");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.onProcessStart = onProcessStart;
        this.onProcessFinish = onProcessFinish;
        this.cancelGracePeriodMs = cancelGracePeriodMs;
        this.onProcessComplete = onProcessComplete;
    }

    public BlockingState<ProcessState> getState() {
        return state;
    }

    public void start() {
        try {
            if (state.getValue() != ProcessState.IDLE) {
                throw new IllegalStateException("Cannot start process in state " + state.getValue());
            }

            java.util.List<String> args = new java.util.ArrayList<>();
            args.add(ffmpegPath);
            args.addAll(command.toArgList());

            ProcessBuilder processBuilder = new ProcessBuilder(args).redirectErrorStream(false);
            Process proc = processBuilder.start();
            process = proc;
            state.update(ProcessState.RUNNING);
            long processStartNanos = System.nanoTime();
            if (onProcessStart != null) {
                onProcessStart.run();
            }

            stderrTask = ioExecutor.submit(() -> {
                try (BufferedReader stderrReader = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
                    String line;
                    while ((line = stderrReader.readLine()) != null) {
                        if (stderrBuilder.length() < MAX_STDERR_BYTES) {
                            stderrBuilder.append(line).append(System.lineSeparator());
                        }
                    }
                } catch (Exception ignored) {
                    // Process may have been destroyed.
                }
            });

            progressReader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            completionTask = ioExecutor.submit(() -> {
                int exitCode = -1;
                try {
                    exitCode = proc.waitFor();
                    if (stderrTask != null) {
                        stderrTask.get();
                    }
                    if (state.getValue() == ProcessState.RUNNING) {
                        state.update(exitCode == 0 ? ProcessState.COMPLETED : ProcessState.FAILED);
                    }
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    if (state.getValue() == ProcessState.RUNNING) {
                        state.update(ProcessState.CANCELLED);
                    }
                } finally {
                    long elapsedNanos = System.nanoTime() - processStartNanos;
                    if (onProcessComplete != null) {
                        onProcessComplete.accept(elapsedNanos, exitCode == 0);
                    }
                    if (onProcessFinish != null) {
                        onProcessFinish.run();
                    }
                }
                return state.getValue();
            });
        } catch (Throwable throwable) {
            if (throwable instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throwUnchecked(throwable);
        }
    }

    public void cancel() {
        try {
            Process proc = process;
            if (proc == null || !proc.isAlive()) {
                return;
            }

            state.update(ProcessState.CANCELLED);
            proc.destroy();

            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(cancelGracePeriodMs);
            while (proc.isAlive() && System.nanoTime() < deadlineNanos) {
                Thread.sleep(CANCEL_POLL_INTERVAL_MS);
            }

            if (proc.isAlive()) {
                proc.destroyForcibly();
                proc.waitFor(cancelGracePeriodMs, TimeUnit.MILLISECONDS);
            }

            closeQuietly(progressReader);
            closeQuietly(proc.getInputStream());
            closeQuietly(proc.getErrorStream());
            closeQuietly(proc.getOutputStream());

            if (stderrTask != null) {
                try {
                    stderrTask.get(cancelGracePeriodMs, TimeUnit.MILLISECONDS);
                } catch (Exception exception) {
                    stderrTask.cancel(true);
                }
            }
        } catch (Throwable throwable) {
            if (throwable instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throwUnchecked(throwable);
        }
    }

    public void cancelBlocking() {
        cancel();
    }

    public ProcessState awaitTerminalState() {
        try {
            if (completionTask != null) {
                completionTask.get();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to await terminal process state", exception);
        }
        return state.first(value -> value != ProcessState.IDLE && value != ProcessState.RUNNING);
    }

    public BlockingStream<TranscodeProgress> progressFlow() {
        BufferedReader reader = progressReader;
        if (reader == null) {
            throw new IllegalStateException("Process not started");
        }
        return FFmpegProgressParser.parseProgressStream(reader, command.getSourceDurationUs());
    }

    public String stderrCapture() {
        return stderrBuilder.toString();
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void throwUnchecked(Throwable throwable) throws E {
        throw (E) throwable;
    }
}
