package com.nyx.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.common.DatabaseResources;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileProbeCacheTest {
    @TempDir
    Path tempDir;

    private final List<HikariDataSource> dataSources = new ArrayList<>();
    private final AudioMetadata sampleMeta = new AudioMetadata(123.45, 320L, 2, "Artist", "Album", "Track Title");

    @AfterEach
    void teardown() {
        ModuleMediaTestSupport.closeDataSources(dataSources);
    }

    private FileProbeCache createCache() {
        DatabaseResources resources = FileProbeCache.createDatabase(tempDir);
        dataSources.add(resources.getDataSource());
        return new FileProbeCache(resources.getJdbi());
    }

    @Test
    void getReturnsNullOnEmptyCache() {
        FileProbeCache cache = createCache();

        assertNull(cache.get("/some/path.mp3", 1000L, 2048L));
    }

    @Test
    void putThenGetWithSameKeyReturnsCachedData() {
        FileProbeCache cache = createCache();

        cache.put("/audio/track.mp3", 1000L, 4096L, sampleMeta);
        AudioMetadata result = cache.get("/audio/track.mp3", 1000L, 4096L);

        assertNotNull(result);
        assertEquals(123.45, result.duration());
        assertEquals(320L, result.bitrate());
        assertEquals(2, result.channels());
        assertEquals("Artist", result.artist());
        assertEquals("Album", result.album());
        assertEquals("Track Title", result.title());
    }

    @Test
    void getReturnsNullWhenMtimeChanged() {
        FileProbeCache cache = createCache();
        cache.put("/audio/track.mp3", 1000L, 4096L, sampleMeta);

        assertNull(cache.get("/audio/track.mp3", 9999L, 4096L));
    }

    @Test
    void getReturnsNullWhenSizeChanged() {
        FileProbeCache cache = createCache();
        cache.put("/audio/track.mp3", 1000L, 4096L, sampleMeta);

        assertNull(cache.get("/audio/track.mp3", 1000L, 8192L));
    }

    @Test
    void putTwiceWithSamePathOverwritesUpsert() {
        FileProbeCache cache = createCache();
        cache.put("/audio/track.mp3", 1000L, 4096L, sampleMeta);

        AudioMetadata updated = new AudioMetadata(200.0, sampleMeta.bitrate(), sampleMeta.channels(), "New Artist", sampleMeta.album(), sampleMeta.title());
        cache.put("/audio/track.mp3", 1000L, 4096L, updated);

        AudioMetadata result = cache.get("/audio/track.mp3", 1000L, 4096L);

        assertNotNull(result);
        assertEquals("New Artist", result.artist());
        assertEquals(200.0, result.duration());
    }

    @Test
    void putThenGetWithNullFieldsRoundTripsCorrectly() {
        FileProbeCache cache = createCache();
        AudioMetadata sparse = new AudioMetadata(60.0, null, null, null, null, null);

        cache.put("/audio/sparse.mp3", 500L, 1024L, sparse);
        AudioMetadata result = cache.get("/audio/sparse.mp3", 500L, 1024L);

        assertNotNull(result);
        assertEquals(60.0, result.duration());
        assertNull(result.bitrate());
        assertNull(result.channels());
        assertNull(result.artist());
        assertNull(result.album());
        assertNull(result.title());
    }

    @Test
    void multipleDistinctPathsAreStoredIndependently() {
        FileProbeCache cache = createCache();
        AudioMetadata meta1 = new AudioMetadata(sampleMeta.duration(), sampleMeta.bitrate(), sampleMeta.channels(), "Artist One", sampleMeta.album(), sampleMeta.title());
        AudioMetadata meta2 = new AudioMetadata(sampleMeta.duration(), sampleMeta.bitrate(), sampleMeta.channels(), "Artist Two", sampleMeta.album(), sampleMeta.title());

        cache.put("/audio/one.mp3", 100L, 1000L, meta1);
        cache.put("/audio/two.mp3", 200L, 2000L, meta2);

        assertEquals("Artist One", cache.get("/audio/one.mp3", 100L, 1000L).artist());
        assertEquals("Artist Two", cache.get("/audio/two.mp3", 200L, 2000L).artist());
    }
}
