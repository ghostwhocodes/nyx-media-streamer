package com.nyx.media;

import com.nyx.common.MediaTypes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AudioTranscoderTest {
    private final AudioTranscoder transcoder = new AudioTranscoder();
    private Path tempDir;

    @BeforeEach
    void setup() throws IOException {
        tempDir = Files.createTempDirectory("nyx-audio-trans-test");
    }

    @AfterEach
    void teardown() throws IOException {
        if (tempDir != null) {
            Files.walk(tempDir)
                .sorted((left, right) -> right.compareTo(left))
                .forEach(path -> path.toFile().delete());
        }
    }

    @Test
    void negotiateFormatReturnsNullForNullAcceptHeader() {
        assertThat(transcoder.negotiateFormat(null, MediaTypes.AUDIO_FLAC)).isNull();
    }

    @Test
    void negotiateFormatReturnsNullForEmptyAcceptHeader() {
        assertThat(transcoder.negotiateFormat("", MediaTypes.AUDIO_FLAC)).isNull();
    }

    @Test
    void negotiateFormatReturnsNullForWildcardAccept() {
        assertThat(transcoder.negotiateFormat("*/*", MediaTypes.AUDIO_FLAC)).isNull();
    }

    @Test
    void negotiateFormatReturnsNullWhenClientAcceptsSourceFormat() {
        assertThat(transcoder.negotiateFormat("audio/flac", MediaTypes.AUDIO_FLAC)).isNull();
    }

    @Test
    void negotiateFormatReturnsAacWhenClientRequestsItForFlacSource() {
        AudioTranscoder.TranscodeTarget target = transcoder.negotiateFormat("audio/aac", MediaTypes.AUDIO_FLAC);
        assertThat(target).isNotNull();
        assertThat(target.mimeType()).isEqualTo(MediaTypes.AUDIO_AAC);
        assertThat(target.codec()).isEqualTo("aac");
    }

    @Test
    void negotiateFormatReturnsMp3WhenClientRequestsIt() {
        AudioTranscoder.TranscodeTarget target = transcoder.negotiateFormat("audio/mpeg", MediaTypes.AUDIO_FLAC);
        assertThat(target).isNotNull();
        assertThat(target.mimeType()).isEqualTo(MediaTypes.AUDIO_MP3);
    }

    @Test
    void negotiateFormatSelectsFirstMatchingFromMultipleAcceptTypes() {
        AudioTranscoder.TranscodeTarget target = transcoder.negotiateFormat("audio/aac, audio/mpeg", MediaTypes.AUDIO_FLAC);
        assertThat(target).isNotNull();
        assertThat(target.mimeType()).isEqualTo(MediaTypes.AUDIO_AAC);
    }

    @Test
    void negotiateFormatReturnsNullForUnsupportedTargetFormat() {
        assertThat(transcoder.negotiateFormat("audio/x-custom", MediaTypes.AUDIO_FLAC)).isNull();
    }

    @Test
    void negotiateFormatHandlesAcceptWithQualityParameters() {
        AudioTranscoder.TranscodeTarget target = transcoder.negotiateFormat("audio/aac;q=0.9, audio/mpeg;q=0.8", MediaTypes.AUDIO_FLAC);
        assertThat(target).isNotNull();
        assertThat(target.mimeType()).isEqualTo(MediaTypes.AUDIO_AAC);
    }

    @Test
    void negotiateFormatReturnsNullWhenSourceFormatIsInAcceptList() {
        assertThat(transcoder.negotiateFormat("audio/flac, audio/aac", MediaTypes.AUDIO_FLAC)).isNull();
    }

    @Test
    void negotiateFormatReturnsOpusWhenClientRequestsIt() {
        AudioTranscoder.TranscodeTarget target = transcoder.negotiateFormat("audio/opus", MediaTypes.AUDIO_FLAC);
        assertThat(target).isNotNull();
        assertThat(target.mimeType()).isEqualTo("audio/opus");
        assertThat(target.codec()).isEqualTo("libopus");
        assertThat(target.format()).isEqualTo("opus");
    }

    @Test
    void negotiateFormatReturnsNullForBlankAcceptHeader() {
        assertThat(transcoder.negotiateFormat("   ", MediaTypes.AUDIO_FLAC)).isNull();
    }

    @Test
    void negotiateFormatPrefersAacOverMp3WhenBothAccepted() {
        AudioTranscoder.TranscodeTarget target = transcoder.negotiateFormat("audio/mpeg, audio/aac", MediaTypes.AUDIO_FLAC);
        assertThat(target).isNotNull();
        assertThat(target.mimeType()).isEqualTo(MediaTypes.AUDIO_AAC);
    }

    @Test
    void negotiateFormatReturnsNullWhenOnlySourceMimeInList() {
        assertThat(transcoder.negotiateFormat("audio/wav", MediaTypes.AUDIO_WAV)).isNull();
    }

    @Test
    void transcodeTargetHoldsCorrectValues() {
        AudioTranscoder.TranscodeTarget target = new AudioTranscoder.TranscodeTarget("audio/aac", "adts", "aac", "256k");
        assertThat(target.mimeType()).isEqualTo("audio/aac");
        assertThat(target.format()).isEqualTo("adts");
        assertThat(target.codec()).isEqualTo("aac");
        assertThat(target.bitrate()).isEqualTo("256k");
    }

    @Test
    void transcodeWritesOutputFromFfmpegStdoutToStream() throws IOException {
        Path script = createScript("audio-ok.sh", """
            echo -n "transcoded audio data"
            exit 0
            """);
        AudioTranscoder localTranscoder = new AudioTranscoder(script.toString(), null, null, new com.nyx.config.AudioConfig());
        Path sourceFile = tempDir.resolve("source.flac");
        Files.write(sourceFile, "fake audio data".getBytes());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        localTranscoder.transcode(sourceFile, new AudioTranscoder.TranscodeTarget(MediaTypes.AUDIO_AAC, "adts", "aac", "256k"), output);

        assertThat(output.toString()).contains("transcoded audio data");
    }

    @Test
    void transcodeHandlesNonZeroExitCode() throws IOException {
        Path script = createScript("audio-fail.sh", """
            echo "some data" >&1
            echo "error: codec not found" >&2
            exit 1
            """);
        AudioTranscoder localTranscoder = new AudioTranscoder(script.toString(), null, null, new com.nyx.config.AudioConfig());
        Path sourceFile = tempDir.resolve("source2.flac");
        Files.write(sourceFile, "fake data".getBytes());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        localTranscoder.transcode(sourceFile, new AudioTranscoder.TranscodeTarget(MediaTypes.AUDIO_MP3, "mp3", "libmp3lame", "192k"), output);
    }

    @Test
    void transcodeHandlesSlowProcessWithStderrOutput() throws IOException {
        Path script = createScript("audio-stderr.sh", """
            echo "stderr line 1" >&2
            echo "stderr line 2" >&2
            echo -n "output data"
            exit 0
            """);
        AudioTranscoder localTranscoder = new AudioTranscoder(script.toString(), null, null, new com.nyx.config.AudioConfig());
        Path sourceFile = tempDir.resolve("source3.flac");
        Files.write(sourceFile, "fake data".getBytes());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        localTranscoder.transcode(sourceFile, new AudioTranscoder.TranscodeTarget("audio/opus", "opus", "libopus", "128k"), output);
        assertThat(output.toString()).contains("output data");
    }

    @Test
    void negotiateFormatHandlesAcceptWithSpacesAfterComma() {
        AudioTranscoder.TranscodeTarget target = transcoder.negotiateFormat("audio/aac , audio/mpeg", MediaTypes.AUDIO_FLAC);
        assertThat(target).isNotNull();
        assertThat(target.mimeType()).isEqualTo(MediaTypes.AUDIO_AAC);
    }

    @Test
    void negotiateFormatReturnsNullForEmptyEntriesAfterParsing() {
        assertThat(transcoder.negotiateFormat(",,,", MediaTypes.AUDIO_FLAC)).isNull();
    }

    @Test
    void negotiateFormatReturnsNullForWildcardAcceptFromImprovements() {
        assertThat(transcoder.negotiateFormat("*/*", "audio/mpeg")).isNull();
    }

    @Test
    void negotiateFormatReturnsNullForSourceTypeInAcceptFromImprovements() {
        assertThat(transcoder.negotiateFormat("audio/mpeg, audio/aac", "audio/mpeg")).isNull();
    }

    @Test
    void negotiateFormatReturnsTargetForSupportedTypeFromImprovements() {
        AudioTranscoder.TranscodeTarget target = transcoder.negotiateFormat("audio/aac", "audio/flac");
        assertThat(target).isNotNull();
        assertThat(target.mimeType()).isEqualTo("audio/aac");
    }

    @Test
    void negotiateFormatReturnsNullForEmptyAcceptFromImprovements() {
        assertThat(transcoder.negotiateFormat("", "audio/mpeg")).isNull();
        assertThat(transcoder.negotiateFormat(null, "audio/mpeg")).isNull();
    }

    @Test
    void negotiateFormatHandlesQualityParametersFromImprovements() {
        AudioTranscoder.TranscodeTarget target = transcoder.negotiateFormat("audio/aac;q=0.9, audio/opus;q=0.8", "audio/flac");
        assertThat(target).isNotNull();
        assertThat(target.mimeType()).isEqualTo("audio/aac");
    }

    @Test
    void audioTranscoderConstantsAreCorrect() {
        assertThat(AudioTranscoder.BUFFER_SIZE).isEqualTo(8192);
    }

    private Path createScript(String name, String content) throws IOException {
        Path script = tempDir.resolve(name);
        Files.writeString(script, "#!/bin/bash\n" + content + "\n");
        Files.setPosixFilePermissions(script, Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE
        ));
        return script;
    }
}
