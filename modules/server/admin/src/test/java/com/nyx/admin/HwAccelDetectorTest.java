package com.nyx.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.ffmpeg.model.HwAccel;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HwAccelDetectorTest {
    @Test
    void parseHwAccelsExtractsAccelerationMethods() throws Exception {
        String output = loadFixture("ffmpeg-hwaccels.txt");
        var accels = HwAccelDetector.parseHwAccels(output);

        assertTrue(accels.contains("vaapi"));
        assertTrue(accels.contains("cuda"));
        assertTrue(accels.contains("vulkan"));
        assertTrue(accels.contains("drm"));
        assertFalse(accels.contains("Hardware"));
    }

    @Test
    void parseHwAccelsReturnsEmptyForInvalidOutput() {
        assertTrue(HwAccelDetector.parseHwAccels("some random output").isEmpty());
    }

    @Test
    void parseEncodersExtractsEncoderNames() throws Exception {
        String output = loadFixture("ffmpeg-encoders.txt");
        var encoders = HwAccelDetector.parseEncoders(output);

        assertTrue(encoders.contains("libx264"));
        assertTrue(encoders.contains("libx265"));
        assertTrue(encoders.contains("libsvtav1"));
        assertTrue(encoders.contains("h264_nvenc"));
        assertTrue(encoders.contains("hevc_nvenc"));
        assertTrue(encoders.contains("h264_vaapi"));
        assertTrue(encoders.contains("h264_qsv"));
        assertTrue(encoders.contains("aac"));
        assertTrue(encoders.contains("libopus"));
        assertTrue(encoders.contains("webvtt"));
    }

    @Test
    void parseVersionExtractsVersionString() throws Exception {
        assertEquals("6.1.1-3ubuntu5", HwAccelDetector.parseVersion(loadFixture("ffmpeg-version.txt")));
    }

    @Test
    void parseVersionReturnsNullForInvalidOutput() {
        assertNull(HwAccelDetector.parseVersion("not a version"));
    }

    @Test
    void isVersionSufficientComparesCorrectly() {
        assertTrue(HwAccelDetector.isVersionSufficient("6.1.1", "6.0"));
        assertTrue(HwAccelDetector.isVersionSufficient("6.0", "6.0"));
        assertTrue(HwAccelDetector.isVersionSufficient("7.0", "6.0"));
        assertFalse(HwAccelDetector.isVersionSufficient("5.4", "6.0"));
        assertTrue(HwAccelDetector.isVersionSufficient("6.1.1-3ubuntu5", "6.0"));
    }

    @Test
    void validateOrFallbackReturnsNoneWhenHardwareUnavailable() {
        HwAccelDetector detector = new HwAccelDetector("ffmpeg");
        assertEquals(HwAccel.None, detector.validateOrFallback(new HwAccel.Vaapi("/dev/dri/renderD128")));
    }

    @Test
    void validateOrFallbackAcceptsNoneAndAuto() {
        HwAccelDetector detector = new HwAccelDetector("ffmpeg");
        assertEquals(HwAccel.None, detector.validateOrFallback(HwAccel.None));
        assertEquals(HwAccel.Auto, detector.validateOrFallback(HwAccel.Auto));
    }

    @Test
    void isAvailableReturnsTrueForNoneAndAutoWithoutDetection() {
        HwAccelDetector detector = new HwAccelDetector("ffmpeg");
        assertTrue(detector.isAvailable(HwAccel.None));
        assertTrue(detector.isAvailable(HwAccel.Auto));
    }

    @Test
    void isAvailableReturnsFalseForVaapiWithoutDetection() {
        assertFalse(new HwAccelDetector("ffmpeg").isAvailable(new HwAccel.Vaapi("/dev/dri/renderD128")));
    }

    @Test
    void isAvailableReturnsFalseForNvencWithoutDetection() {
        assertFalse(new HwAccelDetector("ffmpeg").isAvailable(HwAccel.Nvenc));
    }

    @Test
    void isAvailableReturnsFalseForQsvWithoutDetection() {
        assertFalse(new HwAccelDetector("ffmpeg").isAvailable(HwAccel.Qsv));
    }

    @Test
    void parseHwAccelsReturnsEmptyForEmptyOutput() {
        assertTrue(HwAccelDetector.parseHwAccels("").isEmpty());
    }

    @Test
    void parseEncodersReturnsEmptyForOutputWithoutSeparator() {
        assertTrue(HwAccelDetector.parseEncoders("some output without separator line").isEmpty());
    }

    @Test
    void parseEncodersReturnsEmptyForEmptyOutput() {
        assertTrue(HwAccelDetector.parseEncoders("").isEmpty());
    }

    @Test
    void isVersionSufficientHandlesEqualVersions() {
        assertTrue(HwAccelDetector.isVersionSufficient("6.0.0", "6.0.0"));
    }

    @Test
    void isVersionSufficientHandlesDifferentPartCounts() {
        assertTrue(HwAccelDetector.isVersionSufficient("7.0", "6.0.1"));
        assertFalse(HwAccelDetector.isVersionSufficient("5.9.9", "6.0"));
    }

    @Test
    void validateOrFallbackFallsBackForNvenc() {
        assertEquals(HwAccel.None, new HwAccelDetector("ffmpeg").validateOrFallback(HwAccel.Nvenc));
    }

    @Test
    void validateOrFallbackFallsBackForQsv() {
        assertEquals(HwAccel.None, new HwAccelDetector("ffmpeg").validateOrFallback(HwAccel.Qsv));
    }

    private String loadFixture(String name) throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("fixtures/" + name)) {
            if (stream == null) {
                throw new IllegalStateException("Missing fixture " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
