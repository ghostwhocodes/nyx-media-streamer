package com.nyx.media;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaylistServiceM3UNormalizationTest {
    @Test
    void absolutePathKeptAsIs() {
        List<String> result = PlaylistService.parseM3U("/media/music/song.mp3");
        assertEquals(List.of("/media/music/song.mp3"), result);
    }

    @Test
    void relativePathResolvedAgainstBaseDir() {
        List<String> result = PlaylistService.parseM3U("song.mp3", "/media/music");
        assertEquals(List.of("/media/music/song.mp3"), result);
    }

    @Test
    void fileUriWithTripleSlashConvertsToLocalPath() {
        List<String> result = PlaylistService.parseM3U("file:///media/song.mp3");
        assertEquals(List.of("/media/song.mp3"), result);
    }

    @Test
    void fileUriWithLocalhostConvertsToLocalPath() {
        List<String> result = PlaylistService.parseM3U("file://localhost/media/song.mp3");
        assertEquals(List.of("/media/song.mp3"), result);
    }

    @Test
    void fileUriWithSingleSlashConvertsToLocalPath() {
        List<String> result = PlaylistService.parseM3U("file:/media/song.mp3");
        assertEquals(List.of("/media/song.mp3"), result);
    }

    @Test
    void fileUriWithPercentEncodedSpacesDecoded() {
        List<String> result = PlaylistService.parseM3U("file:///media/my%20music/song.mp3");
        assertEquals(List.of("/media/my music/song.mp3"), result);
    }

    @Test
    void fileUriNotResolvedAgainstBaseDir() {
        List<String> result = PlaylistService.parseM3U("file:///media/song.mp3", "/other");
        assertEquals(List.of("/media/song.mp3"), result);
    }

    @Test
    void httpUrlStoredAsIs() {
        String url = "http://example.com/stream/song.mp3";
        List<String> result = PlaylistService.parseM3U(url);
        assertEquals(List.of(url), result);
    }

    @Test
    void httpsUrlStoredAsIs() {
        String url = "https://cdn.example.com/music/song.flac";
        List<String> result = PlaylistService.parseM3U(url);
        assertEquals(List.of(url), result);
    }

    @Test
    void rtspUrlStoredAsIs() {
        String url = "rtsp://camera.local:554/stream1";
        List<String> result = PlaylistService.parseM3U(url);
        assertEquals(List.of(url), result);
    }

    @Test
    void httpUrlNotResolvedAgainstBaseDir() {
        String url = "http://example.com/song.mp3";
        List<String> result = PlaylistService.parseM3U(url, "/media");
        assertEquals(List.of(url), result);
    }

    @Test
    void rtspUrlNotResolvedAgainstBaseDir() {
        String url = "rtsp://camera.local:554/stream1";
        List<String> result = PlaylistService.parseM3U(url, "/media");
        assertEquals(List.of(url), result);
    }

    @Test
    void backslashesNormalizedToForwardSlashesWithBaseDir() {
        List<String> result = PlaylistService.parseM3U("music\\sub\\song.mp3", "/media");
        assertEquals(List.of("/media/music/sub/song.mp3"), result);
    }

    @Test
    void backslashPrefixedPathTreatedAsAbsoluteAfterNormalization() {
        List<String> result = PlaylistService.parseM3U("\\media\\song.mp3");
        assertEquals(List.of("/media/song.mp3"), result);
    }

    @Test
    void percentEncodedSpacesInLocalPathDecoded() {
        List<String> result = PlaylistService.parseM3U("/media/my%20music/song.mp3");
        assertEquals(List.of("/media/my music/song.mp3"), result);
    }

    @Test
    void percentEncodedUtf8CharsDecoded() {
        List<String> result = PlaylistService.parseM3U("/media/caf%C3%A9/song.mp3");
        assertEquals(List.of("/media/café/song.mp3"), result);
    }

    @Test
    void relativePercentEncodedPathResolvedAgainstBaseDir() {
        List<String> result = PlaylistService.parseM3U("my%20music/song.mp3", "/media");
        assertEquals(List.of("/media/my music/song.mp3"), result);
    }

    @Test
    void mixedM3UWithLocalRemoteFileBackslashAndEncodedEntries() {
        String m3u = """
            #EXTM3U
            #EXTINF:-1,Local Song
            /media/song.mp3
            #EXTINF:-1,Remote Song
            https://cdn.example.com/song.flac
            #EXTINF:-1,File URI
            file:///media/another.mp3
            #EXTINF:-1,Windows Path
            music\\windows\\track.ogg
            #EXTINF:-1,Encoded
            /media/my%20music/encoded.wav
            """.stripIndent();

        List<String> result = PlaylistService.parseM3U(m3u, "/base");
        assertEquals(5, result.size());
        assertEquals("/media/song.mp3", result.get(0));
        assertEquals("https://cdn.example.com/song.flac", result.get(1));
        assertEquals("/media/another.mp3", result.get(2));
        assertEquals("/base/music/windows/track.ogg", result.get(3));
        assertEquals("/media/my music/encoded.wav", result.get(4));
    }
}
