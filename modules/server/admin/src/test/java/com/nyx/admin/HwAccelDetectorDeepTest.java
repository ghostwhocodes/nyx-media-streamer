package com.nyx.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.ffmpeg.model.HwAccel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class HwAccelDetectorDeepTest {
    @Test
    void isAvailableNoneAlwaysTrueEvenWithoutDetection() {
        HwAccelDetector detector = new HwAccelDetector("ffmpeg");
        assertTrue(detector.isAvailable(HwAccel.None));
    }

    @Test
    void isAvailableAutoAlwaysTrueEvenWithoutDetection() {
        HwAccelDetector detector = new HwAccelDetector("ffmpeg");
        assertTrue(detector.isAvailable(HwAccel.Auto));
    }

    @Test
    void isAvailableVaapiFalseWhenNoAccelsDetected() {
        HwAccelDetector detector = new HwAccelDetector("ffmpeg");
        assertFalse(detector.isAvailable(new HwAccel.Vaapi("/dev/dri/renderD128")));
    }

    @Test
    void isAvailableNvencFalseWhenNoAccelsDetected() {
        HwAccelDetector detector = new HwAccelDetector("ffmpeg");
        assertFalse(detector.isAvailable(HwAccel.Nvenc));
    }

    @Test
    void isAvailableQsvFalseWhenNoAccelsDetected() {
        HwAccelDetector detector = new HwAccelDetector("ffmpeg");
        assertFalse(detector.isAvailable(HwAccel.Qsv));
    }

    @Test
    void validateOrFallbackReturnsNoneWhenRequested() {
        HwAccelDetector detector = new HwAccelDetector("ffmpeg");
        assertEquals(HwAccel.None, detector.validateOrFallback(HwAccel.None));
    }

    @Test
    void validateOrFallbackReturnsAutoWhenRequested() {
        HwAccelDetector detector = new HwAccelDetector("ffmpeg");
        assertEquals(HwAccel.Auto, detector.validateOrFallback(HwAccel.Auto));
    }

    @Test
    void validateOrFallbackFallsBackVaapiToNoneWhenUnavailable() {
        HwAccelDetector detector = new HwAccelDetector("ffmpeg");
        HwAccel result = detector.validateOrFallback(new HwAccel.Vaapi("/dev/dri/renderD128"));
        assertEquals(HwAccel.None, result);
    }

    @Test
    void validateOrFallbackFallsBackNvencToNoneWhenUnavailable() {
        HwAccelDetector detector = new HwAccelDetector("ffmpeg");
        assertEquals(HwAccel.None, detector.validateOrFallback(HwAccel.Nvenc));
    }

    @Test
    void validateOrFallbackFallsBackQsvToNoneWhenUnavailable() {
        HwAccelDetector detector = new HwAccelDetector("ffmpeg");
        assertEquals(HwAccel.None, detector.validateOrFallback(HwAccel.Qsv));
    }

    @Test
    void detectPopulatesAccelsAndEncodersFromFakeFfmpeg() throws Exception {
        Path tempDir = Files.createTempDirectory("hwaccel-detect-test");
        try {
            Path fakeScript = tempDir.resolve("fake-ffmpeg");
            Files.writeString(fakeScript, """
                #!/bin/bash
                if [[ "$1" == "-hwaccels" ]]; then
                    echo "Hardware acceleration methods:"
                    echo "vaapi"
                    echo "cuda"
                elif [[ "$1" == "-encoders" ]]; then
                    echo " V..... = Video"
                    echo " ------"
                    echo " V....D h264_nvenc           NVIDIA NVENC H.264"
                    echo " V....D h264_vaapi           VAAPI H.264"
                fi
                """);
            fakeScript.toFile().setExecutable(true);

            HwAccelDetector detector = new HwAccelDetector(fakeScript.toString());
            Set<String> accels = detector.detect();

            assertTrue(accels.contains("vaapi"));
            assertTrue(accels.contains("cuda"));
            assertTrue(detector.isAvailable(new HwAccel.Vaapi("/dev/dri/renderD128")));
            assertTrue(detector.isAvailable(HwAccel.Nvenc));
            assertFalse(detector.isAvailable(HwAccel.Qsv));

            HwAccel vaapi = new HwAccel.Vaapi("/dev/dri/renderD128");
            assertEquals(vaapi, detector.validateOrFallback(vaapi));
            assertEquals(HwAccel.Nvenc, detector.validateOrFallback(HwAccel.Nvenc));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    void detectWithQsvAccelsAndEncodersMakesQsvAvailable() throws Exception {
        Path tempDir = Files.createTempDirectory("hwaccel-detect-qsv");
        try {
            Path fakeScript = tempDir.resolve("fake-ffmpeg");
            Files.writeString(fakeScript, """
                #!/bin/bash
                if [[ "$1" == "-hwaccels" ]]; then
                    echo "Hardware acceleration methods:"
                    echo "qsv"
                elif [[ "$1" == "-encoders" ]]; then
                    echo " ------"
                    echo " V....D h264_qsv             Intel QSV H.264"
                fi
                """);
            fakeScript.toFile().setExecutable(true);

            HwAccelDetector detector = new HwAccelDetector(fakeScript.toString());
            detector.detect();

            assertTrue(detector.isAvailable(HwAccel.Qsv));
            assertEquals(HwAccel.Qsv, detector.validateOrFallback(HwAccel.Qsv));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    void detectWithCudaButNoNvencEncoderMakesNvencUnavailable() throws Exception {
        Path tempDir = Files.createTempDirectory("hwaccel-detect-cuda-no-nvenc");
        try {
            Path fakeScript = tempDir.resolve("fake-ffmpeg");
            Files.writeString(fakeScript, """
                #!/bin/bash
                if [[ "$1" == "-hwaccels" ]]; then
                    echo "Hardware acceleration methods:"
                    echo "cuda"
                elif [[ "$1" == "-encoders" ]]; then
                    echo " ------"
                    echo " V....D libx264              libx264 H.264"
                fi
                """);
            fakeScript.toFile().setExecutable(true);

            HwAccelDetector detector = new HwAccelDetector(fakeScript.toString());
            detector.detect();

            assertFalse(detector.isAvailable(HwAccel.Nvenc));
            assertEquals(HwAccel.None, detector.validateOrFallback(HwAccel.Nvenc));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    void parseHwAccelsWithEmptyOutputReturnsEmptySet() {
        assertEquals(Set.of(), HwAccelDetector.parseHwAccels(""));
    }

    @Test
    void parseHwAccelsWithNoHeaderLineReturnsEmptySet() {
        assertEquals(Set.of(), HwAccelDetector.parseHwAccels("some random text\nwithout header"));
    }

    @Test
    void parseHwAccelsWithHeaderButNoMethodsReturnsEmptySet() {
        assertEquals(Set.of(), HwAccelDetector.parseHwAccels("Hardware acceleration methods:\n"));
    }

    @Test
    void parseHwAccelsWithHeaderAndBlankLinesAfterReturnsEmptySet() {
        assertEquals(Set.of(), HwAccelDetector.parseHwAccels("Hardware acceleration methods:\n\n\n"));
    }

    @Test
    void parseHwAccelsTrimsWhitespaceFromMethodNames() {
        Set<String> result = HwAccelDetector.parseHwAccels("Hardware acceleration methods:\n  vaapi  \n  cuda  \n");
        assertTrue(result.contains("vaapi"));
        assertTrue(result.contains("cuda"));
    }

    @Test
    void parseHwAccelsWithTextBeforeHeaderIgnoresIt() {
        assertEquals(
            Set.of("vaapi", "cuda"),
            HwAccelDetector.parseHwAccels("ffmpeg version 6.1.1\nHardware acceleration methods:\nvaapi\ncuda\n")
        );
    }

    @Test
    void parseEncodersWithEmptyOutputReturnsEmptySet() {
        assertEquals(Set.of(), HwAccelDetector.parseEncoders(""));
    }

    @Test
    void parseEncodersWithNoSeparatorReturnsEmptySet() {
        assertEquals(Set.of(), HwAccelDetector.parseEncoders("some text without separator"));
    }

    @Test
    void parseEncodersWithSeparatorButNoEncodersReturnsEmptySet() {
        assertEquals(Set.of(), HwAccelDetector.parseEncoders("Legend:\n ------\n"));
    }

    @Test
    void parseEncodersWithSingleWordLinesSkipsThem() {
        assertEquals(Set.of(), HwAccelDetector.parseEncoders(" ------\n V....D\n"));
    }

    @Test
    void parseEncodersExtractsEncoderFromMultiPartLine() {
        assertEquals(
            Set.of("libx264"),
            HwAccelDetector.parseEncoders(" ------\n V....D libx264              libx264 H.264\n")
        );
    }

    @Test
    void parseEncodersHandlesMultipleEncoders() {
        String output = """
            ------
            V....D libx264              libx264 H.264
            V....D libx265              libx265 H.265
            A....D aac                  AAC
            """;
        Set<String> result = HwAccelDetector.parseEncoders(output);
        assertTrue(result.contains("libx264"));
        assertTrue(result.contains("libx265"));
        assertTrue(result.contains("aac"));
    }

    @Test
    void parseVersionExtractsVersionFromStandardFfmpegOutput() {
        assertEquals("6.1.1-3ubuntu5", HwAccelDetector.parseVersion("ffmpeg version 6.1.1-3ubuntu5 Copyright (c) 2000-2023"));
    }

    @Test
    void parseVersionExtractsSimpleVersion() {
        assertEquals("7.0", HwAccelDetector.parseVersion("ffmpeg version 7.0 Copyright (c) 2000-2024"));
    }

    @Test
    void parseVersionReturnsNullForEmptyString() {
        assertNull(HwAccelDetector.parseVersion(""));
    }

    @Test
    void parseVersionReturnsNullForUnrelatedOutput() {
        assertNull(HwAccelDetector.parseVersion("not a version line"));
    }

    @Test
    void parseVersionFindsVersionAnywhereInMultiLineOutput() {
        assertEquals("5.1.4", HwAccelDetector.parseVersion("some preamble\nffmpeg version 5.1.4 Copyright ...\nmore stuff"));
    }

    @Test
    void isVersionSufficientEqualVersionsReturnsTrue() {
        assertTrue(HwAccelDetector.isVersionSufficient("6.0.0", "6.0.0"));
    }

    @Test
    void isVersionSufficientEqualShortVersionsReturnsTrue() {
        assertTrue(HwAccelDetector.isVersionSufficient("6.0", "6.0"));
    }

    @Test
    void isVersionSufficientHigherMajorReturnsTrue() {
        assertTrue(HwAccelDetector.isVersionSufficient("7.0", "6.0"));
    }

    @Test
    void isVersionSufficientLowerMajorReturnsFalse() {
        assertFalse(HwAccelDetector.isVersionSufficient("5.9", "6.0"));
    }

    @Test
    void isVersionSufficientHigherMinorReturnsTrue() {
        assertTrue(HwAccelDetector.isVersionSufficient("6.2", "6.1"));
    }

    @Test
    void isVersionSufficientLowerMinorReturnsFalse() {
        assertFalse(HwAccelDetector.isVersionSufficient("6.0", "6.1"));
    }

    @Test
    void isVersionSufficientHigherPatchReturnsTrue() {
        assertTrue(HwAccelDetector.isVersionSufficient("6.1.2", "6.1.1"));
    }

    @Test
    void isVersionSufficientLowerPatchReturnsFalse() {
        assertFalse(HwAccelDetector.isVersionSufficient("6.1.0", "6.1.1"));
    }

    @Test
    void isVersionSufficientVersionWithSuffixVsPlain() {
        assertTrue(HwAccelDetector.isVersionSufficient("6.1.1-3ubuntu5", "6.0"));
    }

    @Test
    void isVersionSufficientDifferentPartCountsVersionLonger() {
        assertTrue(HwAccelDetector.isVersionSufficient("7.0", "6.0.1"));
    }

    @Test
    void isVersionSufficientDifferentPartCountsMinVersionLonger() {
        assertTrue(HwAccelDetector.isVersionSufficient("6.1", "6.1.0"));
    }

    @Test
    void isVersionSufficientMissingPartsInVersionStillWorks() {
        assertTrue(HwAccelDetector.isVersionSufficient("6", "6.0.0"));
    }

    @Test
    void isVersionSufficientMissingPartsInMinVersionStillWorks() {
        assertTrue(HwAccelDetector.isVersionSufficient("6.0.0", "6"));
    }

    @Test
    void isVersionSufficientNonNumericSuffixStripped() {
        assertFalse(HwAccelDetector.isVersionSufficient("n5.0-git", "5.0"));
    }

    @Test
    void isVersionSufficientBothVersionsWithSuffixes() {
        assertTrue(HwAccelDetector.isVersionSufficient("7.0.1-2ubuntu1", "6.1.1-3ubuntu5"));
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new IllegalStateException("Failed to delete " + path, exception);
                }
            });
        }
    }
}
