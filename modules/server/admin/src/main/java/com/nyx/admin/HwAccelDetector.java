package com.nyx.admin;

import com.nyx.ffmpeg.model.HwAccel;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HwAccelDetector {
    private final Logger logger = LoggerFactory.getLogger(HwAccelDetector.class);
    private final String ffmpegPath;
    private Set<String> detectedAccels = Set.of();
    private Set<String> detectedEncoders = Set.of();

    public HwAccelDetector() {
        this("ffmpeg");
    }

    public HwAccelDetector(String ffmpegPath) {
        this.ffmpegPath = ffmpegPath;
    }

    public Set<String> detect() {
        detectedAccels = parseHwAccels(runCommand(Arrays.asList(ffmpegPath, "-hwaccels")));
        detectedEncoders = parseEncoders(runCommand(Arrays.asList(ffmpegPath, "-encoders")));
        return detectedAccels;
    }

    public boolean isAvailable(HwAccel hwAccel) {
        if (hwAccel instanceof HwAccel.None || hwAccel instanceof HwAccel.Auto) {
            return true;
        }
        if (hwAccel instanceof HwAccel.Vaapi) {
            return detectedAccels.contains("vaapi");
        }
        if (hwAccel instanceof HwAccel.Nvenc) {
            return detectedAccels.contains("cuda") && detectedEncoders.stream().anyMatch(encoder -> encoder.contains("nvenc"));
        }
        if (hwAccel instanceof HwAccel.Qsv) {
            return detectedAccels.contains("qsv") && detectedEncoders.stream().anyMatch(encoder -> encoder.contains("qsv"));
        }
        return false;
    }

    public HwAccel validateOrFallback(HwAccel requested) {
        if (isAvailable(requested)) {
            return requested;
        }
        logger.warn("Requested HW acceleration {} not available, falling back to software", requested);
        return HwAccel.None;
    }

    public static Set<String> parseHwAccels(String output) {
        String[] lines = output.split("\\R");
        int headerIndex = -1;
        for (int index = 0; index < lines.length; index++) {
            if (lines[index].startsWith("Hardware acceleration methods:")) {
                headerIndex = index;
                break;
            }
        }
        if (headerIndex < 0) {
            return Set.of();
        }
        return Arrays.stream(lines, headerIndex + 1, lines.length)
            .map(String::trim)
            .filter(line -> !line.isEmpty())
            .collect(Collectors.toSet());
    }

    public static Set<String> parseEncoders(String output) {
        String[] lines = output.split("\\R");
        int separatorIndex = -1;
        for (int index = 0; index < lines.length; index++) {
            if (lines[index].trim().startsWith("------")) {
                separatorIndex = index;
                break;
            }
        }
        if (separatorIndex < 0) {
            return Set.of();
        }
        return Arrays.stream(lines, separatorIndex + 1, lines.length)
            .map(String::trim)
            .filter(line -> !line.isEmpty())
            .map(line -> line.split("\\s+", 3))
            .filter(parts -> parts.length >= 2)
            .map(parts -> parts[1])
            .collect(Collectors.toSet());
    }

    public static String parseVersion(String output) {
        var matcher = java.util.regex.Pattern.compile("ffmpeg version (\\S+)").matcher(output);
        return matcher.find() ? matcher.group(1) : null;
    }

    public static boolean isVersionSufficient(String version, String minVersion) {
        var versionParts = extractNumericVersion(version);
        var minVersionParts = extractNumericVersion(minVersion);
        int maxLength = Math.max(versionParts.length, minVersionParts.length);
        for (int index = 0; index < maxLength; index++) {
            int versionPart = index < versionParts.length ? versionParts[index] : 0;
            int minVersionPart = index < minVersionParts.length ? minVersionParts[index] : 0;
            if (versionPart > minVersionPart) {
                return true;
            }
            if (versionPart < minVersionPart) {
                return false;
            }
        }
        return true;
    }

    private static int[] extractNumericVersion(String version) {
        return Arrays.stream(version.split("-", 2)[0].split("\\."))
            .mapToInt(part -> {
                try {
                    return Integer.parseInt(part);
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            })
            .toArray();
    }

    private static String runCommand(java.util.List<String> args) {
        try {
            Process process = new ProcessBuilder(args)
                .redirectErrorStream(true)
                .start();
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                StringWriter writer = new StringWriter();
                reader.transferTo(writer);
                process.waitFor();
                return writer.toString();
            }
        } catch (Exception exception) {
            return "";
        }
    }
}
