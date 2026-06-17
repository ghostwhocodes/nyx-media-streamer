package com.nyx.common;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ProcessUtils {
    private static final Logger LOG = LoggerFactory.getLogger("ProcessUtils");

    private ProcessUtils() {
    }

    public static String runCommand(List<String> args) {
        try {
            Process process = new ProcessBuilder(args)
                .redirectErrorStream(true)
                .start();
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().reduce("", (left, right) -> left.isEmpty() ? right : left + "\n" + right);
            }
            process.waitFor();
            return output;
        } catch (Exception exception) {
            LOG.warn("Command failed: {}: {}", args.isEmpty() ? "<empty>" : args.getFirst(), exception.toString());
            return ProcessUtils.<RuntimeException, String>throwUnchecked(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable, T> T throwUnchecked(Throwable throwable) throws E {
        throw (E) throwable;
    }
}
