package com.nyx.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.common.ErrorCode;
import com.nyx.common.HealthMonitor;
import com.nyx.common.NyxException;
import com.nyx.common.storage.InMemoryStorageBackend;
import com.nyx.ffmpeg.TrickplayAssetKind;
import com.nyx.ffmpeg.VideoTrickplayAssetOutput;
import com.nyx.ffmpeg.VideoTrickplayAssetPlan;
import com.nyx.ffmpeg.VideoTrickplayGenerator;
import com.nyx.ffmpeg.VideoTrickplayPlan;
import com.nyx.ffmpeg.VideoTrickplayRequest;
import com.nyx.ffmpeg.VideoTrickplayTimelineEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VideoTrickplayServiceTest {
    private Path tempDir;
    private InMemoryStorageBackend backend;

    @BeforeEach
    void setup() throws Exception {
        tempDir = Files.createTempDirectory("nyx-video-trickplay-service");
        backend = new InMemoryStorageBackend();
    }

    @AfterEach
    void teardown() throws Exception {
        ImageCoreTestSupport.deleteRecursively(tempDir);
    }

    @Test
    void getTrickplayCachesGeneratedAssetBytesUnderDedicatedPrefix() throws Exception {
        Path source = ImageCoreTestSupport.createVideoPlaceholder(tempDir, "video.mp4", 512);
        AtomicInteger generateCalls = new AtomicInteger();
        VideoTrickplayGenerator generator = new VideoTrickplayGenerator() {
            @Override
            public VideoTrickplayPlan plan(Path sourcePath, VideoTrickplayRequest request) {
                return samplePlan(false);
            }

            @Override
            public byte[] generate(Path sourcePath, VideoTrickplayAssetPlan plan) {
                generateCalls.incrementAndGet();
                return ("asset-" + plan.assetIndex()).getBytes();
            }
        };
        VideoTrickplayService service = new VideoTrickplayService(generator, backend);

        VideoTrickplayResult first = service.getTrickplay(source, new VideoTrickplayRequest());
        VideoTrickplayResult second = service.getTrickplay(source, new VideoTrickplayRequest());

        assertEquals(2, first.getAssets().size());
        assertEquals(2, second.getAssets().size());
        assertEquals(2, generateCalls.get());
        assertTrue(backend.getObjects().keySet().stream().allMatch(key -> key.startsWith("video-trickplay/")));
        assertArrayEquals(first.getAssets().getFirst().bytes(), second.getAssets().getFirst().bytes());
    }

    @Test
    void getAssetRegeneratesWhenSourceMetadataChanges() throws Exception {
        Path source = ImageCoreTestSupport.createVideoPlaceholder(tempDir, "video.mp4", 512);
        AtomicInteger generateCalls = new AtomicInteger();
        VideoTrickplayGenerator generator = new VideoTrickplayGenerator() {
            @Override
            public VideoTrickplayPlan plan(Path sourcePath, VideoTrickplayRequest request) {
                return samplePlan(true);
            }

            @Override
            public byte[] generate(Path sourcePath, VideoTrickplayAssetPlan plan) {
                return ("asset-v" + generateCalls.incrementAndGet()).getBytes();
            }
        };
        VideoTrickplayService service = new VideoTrickplayService(generator, backend);

        VideoTrickplayAssetOutput first = service.getAsset(source, new VideoTrickplayRequest(), 0);
        Files.write(source, new byte[1024]);
        VideoTrickplayAssetOutput second = service.getAsset(source, new VideoTrickplayRequest(), 0);

        assertEquals(2, generateCalls.get());
        assertFalse(java.util.Arrays.equals(first.bytes(), second.bytes()));
    }

    @Test
    void getPlanThrowsFfmpegUnavailableWhenHealthServiceReportsUnavailable() throws Exception {
        Path source = ImageCoreTestSupport.createVideoPlaceholder(tempDir, "video.mp4", 512);
        VideoTrickplayGenerator generator = new VideoTrickplayGenerator() {
            @Override
            public VideoTrickplayPlan plan(Path sourcePath, VideoTrickplayRequest request) {
                throw new AssertionError("unused");
            }

            @Override
            public byte[] generate(Path sourcePath, VideoTrickplayAssetPlan plan) {
                throw new AssertionError("unused");
            }
        };
        HealthMonitor healthService = () -> false;
        VideoTrickplayService service = new VideoTrickplayService(generator, healthService, backend);

        NyxException exception = assertThrows(
            NyxException.class,
            () -> service.getPlan(source, new VideoTrickplayRequest())
        );

        assertEquals(ErrorCode.FFMPEG_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void purgeCacheClearsTrickplayEntries() {
        backend.write("video-trickplay/test/asset.jpg", new byte[16], "image/jpeg");
        VideoTrickplayGenerator generator = new VideoTrickplayGenerator() {
            @Override
            public VideoTrickplayPlan plan(Path sourcePath, VideoTrickplayRequest request) {
                throw new AssertionError("unused");
            }

            @Override
            public byte[] generate(Path sourcePath, VideoTrickplayAssetPlan plan) {
                throw new AssertionError("unused");
            }
        };
        VideoTrickplayService service = new VideoTrickplayService(generator, backend);

        service.purgeCache();

        assertFalse(backend.getObjects().containsKey("video-trickplay/test/asset.jpg"));
    }

    private static VideoTrickplayPlan samplePlan(boolean singleAsset) {
        List<VideoTrickplayAssetPlan> assets = new ArrayList<>();
        assets.add(
            new VideoTrickplayAssetPlan(
                TrickplayAssetKind.STORYBOARD_SHEET,
                0,
                0L,
                150_000L,
                10_000L,
                16,
                4,
                4,
                320,
                180,
                1280,
                720
            )
        );
        if (!singleAsset) {
            assets.add(
                new VideoTrickplayAssetPlan(
                    TrickplayAssetKind.PREVIEW_STRIP,
                    1,
                    0L,
                    50_000L,
                    10_000L,
                    6,
                    6,
                    1,
                    320,
                    180,
                    1920,
                    180
                )
            );
        }

        List<VideoTrickplayTimelineEntry> timeline = new ArrayList<>();
        for (VideoTrickplayAssetPlan asset : assets) {
            for (int index = 0; index < asset.frameCount(); index++) {
                timeline.add(
                    new VideoTrickplayTimelineEntry(
                        asset.startMillis() + (asset.intervalMillis() * index),
                        asset.kind(),
                        asset.assetIndex(),
                        index % asset.tileColumns(),
                        index / asset.tileColumns()
                    )
                );
            }
        }

        return new VideoTrickplayPlan(
            1920,
            1080,
            600_000L,
            10_000L,
            320,
            180,
            4,
            4,
            assets,
            timeline
        );
    }
}
