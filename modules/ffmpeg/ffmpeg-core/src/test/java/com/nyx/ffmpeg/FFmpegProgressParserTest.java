package com.nyx.ffmpeg;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FFmpegProgressParserTest {
    @Test
    void parsesRecordedProgressOutput() throws Exception {
        String text = loadFixture("ffmpeg-progress-output.txt");
        BufferedReader reader = new BufferedReader(new StringReader(text));

        List<TranscodeProgress> events = FFmpegProgressParser.parseProgressStream(reader, 15_000_000L).toList();

        assertEquals(3, events.size());
        assertEquals(120, events.get(0).frame());
        assertEquals(45.20, events.get(0).fps(), 0.01);
        assertEquals(1_024_000L, events.get(0).totalSize());
        assertEquals(5_000_000L, events.get(0).outTimeUs());
        assertEquals(1.8, events.get(0).speed(), 0.01);
        assertEquals(1638.4, events.get(0).bitrate(), 0.1);
        assertEquals(33.33, events.get(0).progressPercent(), 0.1);
        assertEquals(240, events.get(1).frame());
        assertEquals(66.67, events.get(1).progressPercent(), 0.1);
        assertEquals(360, events.get(2).frame());
        assertEquals(100.0, events.get(2).progressPercent());
    }

    @Test
    void handlesPartialAndMalformedLinesGracefully() {
        String text = """
            frame=100
            malformed_line_no_equals
            fps=30.0
            =empty_key
            out_time_us=2000000
            speed=1.5x
            bitrate=500.0kbits/s
            progress=continue
            """.stripIndent();

        BufferedReader reader = new BufferedReader(new StringReader(text));
        List<TranscodeProgress> events = FFmpegProgressParser.parseProgressStream(reader, 10_000_000L).toList();

        assertEquals(1, events.size());
        assertEquals(100, events.get(0).frame());
        assertEquals(30.0, events.get(0).fps(), 0.01);
        assertEquals(20.0, events.get(0).progressPercent(), 0.1);
    }

    @Test
    void calculatesPercentCorrectlyWithZeroDuration() {
        String text = """
            frame=100
            out_time_us=5000000
            progress=continue
            """.stripIndent();

        BufferedReader reader = new BufferedReader(new StringReader(text));
        List<TranscodeProgress> events = FFmpegProgressParser.parseProgressStream(reader, 0L).toList();

        assertEquals(1, events.size());
        assertEquals(0.0, events.get(0).progressPercent());
    }

    private String loadFixture(String name) throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("fixtures/" + name)) {
            if (stream == null) {
                throw new IllegalStateException("Missing fixture " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
