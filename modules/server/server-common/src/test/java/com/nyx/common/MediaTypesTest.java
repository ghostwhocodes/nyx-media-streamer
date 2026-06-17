package com.nyx.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaTypesTest {
    @TempDir
    Path tempDir;

    @Test
    void mimeTypeForExtensionReturnsVideoMp4ForMp4() {
        assertEquals(MediaTypes.VIDEO_MP4, MediaTypes.mimeTypeForExtension("mp4"));
    }

    @Test
    void mimeTypeForExtensionIsCaseInsensitive() {
        assertEquals(MediaTypes.VIDEO_MP4, MediaTypes.mimeTypeForExtension("MP4"));
    }

    @Test
    void mimeTypeForExtensionReturnsNullForUnknownExtension() {
        assertNull(MediaTypes.mimeTypeForExtension("xyz"));
    }

    @Test
    void mimeTypeForExtensionReturnsSegmentTypeForM4s() {
        assertEquals(MediaTypes.SEGMENT_M4S, MediaTypes.mimeTypeForExtension("m4s"));
    }

    @Test
    void mimeTypeForPathExtractsExtensionAndReturnsMimeType() {
        assertEquals(MediaTypes.VIDEO_MKV, MediaTypes.mimeTypeForPath("movie.mkv"));
    }

    @Test
    void mimeTypeForPathReturnsNullForPathWithoutExtension() {
        assertNull(MediaTypes.mimeTypeForPath("noextension"));
    }

    @Test
    void mimeTypeForPathHandlesNestedPath() {
        assertEquals(MediaTypes.DASH_MPD, MediaTypes.mimeTypeForPath("/output/job1/manifest.mpd"));
    }

    @Test
    void detectMimeTypeDetectsImageTypesByExtension() throws Exception {
        Path jpgFile = Files.createFile(tempDir.resolve("photo.jpg"));
        Path pngFile = Files.createFile(tempDir.resolve("image.png"));
        Path webpFile = Files.createFile(tempDir.resolve("pic.webp"));
        Path gifFile = Files.createFile(tempDir.resolve("anim.gif"));
        Path tiffFile = Files.createFile(tempDir.resolve("scan.tiff"));
        Path bmpFile = Files.createFile(tempDir.resolve("old.bmp"));

        assertTrue(MediaTypes.isImage(MediaTypes.detectMimeType(jpgFile)));
        assertTrue(MediaTypes.isImage(MediaTypes.detectMimeType(pngFile)));
        assertTrue(MediaTypes.isImage(MediaTypes.detectMimeType(webpFile)));
        assertTrue(MediaTypes.isImage(MediaTypes.detectMimeType(gifFile)));
        assertTrue(MediaTypes.isImage(MediaTypes.detectMimeType(tiffFile)));
        assertTrue(MediaTypes.isImage(MediaTypes.detectMimeType(bmpFile)));
    }

    @Test
    void detectMimeTypeDetectsAudioTypesByExtension() throws Exception {
        Path mp3File = Files.createFile(tempDir.resolve("song.mp3"));
        Path flacFile = Files.createFile(tempDir.resolve("lossless.flac"));
        Path wavFile = Files.createFile(tempDir.resolve("raw.wav"));
        Path oggFile = Files.createFile(tempDir.resolve("vorbis.ogg"));
        Path m4aFile = Files.createFile(tempDir.resolve("itunes.m4a"));

        assertTrue(MediaTypes.isAudio(MediaTypes.detectMimeType(mp3File)));
        assertTrue(MediaTypes.isAudio(MediaTypes.detectMimeType(flacFile)));
        assertTrue(MediaTypes.isAudio(MediaTypes.detectMimeType(wavFile)));
        assertTrue(MediaTypes.isAudio(MediaTypes.detectMimeType(oggFile)));
        assertTrue(MediaTypes.isAudio(MediaTypes.detectMimeType(m4aFile)));
    }

    @Test
    void detectMimeTypeDetectsVideoTypesByExtension() throws Exception {
        Path mkvFile = Files.createFile(tempDir.resolve("movie.mkv"));
        Path mp4File = Files.createFile(tempDir.resolve("clip.mp4"));
        Path aviFile = Files.createFile(tempDir.resolve("old.avi"));

        assertTrue(MediaTypes.isVideo(MediaTypes.detectMimeType(mkvFile)));
        assertTrue(MediaTypes.isVideo(MediaTypes.detectMimeType(mp4File)));
        assertTrue(MediaTypes.isVideo(MediaTypes.detectMimeType(aviFile)));
    }

    @Test
    void detectMimeTypeReturnsOctetStreamForUnknownExtension() throws Exception {
        Path unknownFile = Files.createFile(tempDir.resolve("data.xyz123"));

        assertEquals(MediaTypes.APPLICATION_OCTET_STREAM, MediaTypes.detectMimeType(unknownFile));
    }

    @Test
    void isImageCorrectlyClassifiesImageMimeTypes() {
        assertTrue(MediaTypes.isImage(MediaTypes.IMAGE_JPEG));
        assertTrue(MediaTypes.isImage(MediaTypes.IMAGE_PNG));
        assertTrue(MediaTypes.isImage(MediaTypes.IMAGE_WEBP));
        assertTrue(MediaTypes.isImage(MediaTypes.IMAGE_GIF));
        assertTrue(MediaTypes.isImage(MediaTypes.IMAGE_TIFF));
        assertTrue(MediaTypes.isImage(MediaTypes.IMAGE_BMP));
        assertTrue(MediaTypes.isImage(MediaTypes.IMAGE_SVG));
        assertFalse(MediaTypes.isImage(MediaTypes.AUDIO_MP3));
        assertFalse(MediaTypes.isImage(MediaTypes.VIDEO_MP4));
    }

    @Test
    void isAudioCorrectlyClassifiesAudioMimeTypes() {
        assertTrue(MediaTypes.isAudio(MediaTypes.AUDIO_MP3));
        assertTrue(MediaTypes.isAudio(MediaTypes.AUDIO_FLAC));
        assertTrue(MediaTypes.isAudio(MediaTypes.AUDIO_M4A));
        assertFalse(MediaTypes.isAudio(MediaTypes.IMAGE_JPEG));
        assertFalse(MediaTypes.isAudio(MediaTypes.VIDEO_MP4));
    }

    @Test
    void isVideoCorrectlyClassifiesVideoMimeTypes() {
        assertTrue(MediaTypes.isVideo(MediaTypes.VIDEO_MP4));
        assertTrue(MediaTypes.isVideo(MediaTypes.VIDEO_MKV));
        assertFalse(MediaTypes.isVideo(MediaTypes.AUDIO_MP3));
        assertFalse(MediaTypes.isVideo(MediaTypes.IMAGE_JPEG));
    }

    @Test
    void mimeTypeForExtensionWithSubtitleTypes() {
        assertEquals(MediaTypes.SUBTITLE_VTT, MediaTypes.mimeTypeForExtension("vtt"));
        assertEquals(MediaTypes.SUBTITLE_SRT, MediaTypes.mimeTypeForExtension("srt"));
    }

    @Test
    void mimeTypeForExtensionCoversAllNewM3Types() {
        assertEquals(MediaTypes.IMAGE_TIFF, MediaTypes.mimeTypeForExtension("tiff"));
        assertEquals(MediaTypes.IMAGE_TIFF, MediaTypes.mimeTypeForExtension("tif"));
        assertEquals(MediaTypes.IMAGE_BMP, MediaTypes.mimeTypeForExtension("bmp"));
        assertEquals(MediaTypes.AUDIO_M4A, MediaTypes.mimeTypeForExtension("m4a"));
        assertEquals(MediaTypes.AUDIO_AIFF, MediaTypes.mimeTypeForExtension("aiff"));
        assertEquals(MediaTypes.AUDIO_AIFF, MediaTypes.mimeTypeForExtension("aif"));
        assertEquals(MediaTypes.VIDEO_TS, MediaTypes.mimeTypeForExtension("ts"));
    }

    @Test
    void detectMimeTypeHandlesProbeContentTypeFailureForUnknownExtension() throws Exception {
        Path file = tempDir.resolve("unknownfile");
        Files.write(file, new byte[]{0x00, 0x01, 0x02, 0x03});

        String mimeType = MediaTypes.detectMimeType(file);

        assertNotNull(mimeType);
    }

    @Test
    void mediaRouteHelpersEncodePathQueryValues() {
        String virtualPath = "movies/Fast & Curious #1?.mp4";

        assertEquals(
            "movies/Fast+%26+Curious+%231%3F.mp4",
            MediaTypes.encodePathQueryValue(virtualPath)
        );
        assertEquals(
            "/api/v1/images/thumb?path=movies/Fast+%26+Curious+%231%3F.mp4&size=150",
            MediaTypes.buildThumbnailUrls(virtualPath, java.util.List.of(150)).get("150")
        );
        assertEquals(
            "/api/v1/images/view?path=movies/Fast+%26+Curious+%231%3F.mp4",
            MediaTypes.buildImageViewUrl(virtualPath)
        );
    }
}
