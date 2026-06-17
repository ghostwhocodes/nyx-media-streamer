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
import com.nyx.ffmpeg.VideoPreviewGenerator;
import com.nyx.ffmpeg.VideoPreviewPlan;
import com.nyx.ffmpeg.VideoPreviewRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VideoPreviewServiceTest {
    private Path tempDir;
    private InMemoryStorageBackend backend;

    @BeforeEach
    void setup() throws Exception {
        tempDir = Files.createTempDirectory("nyx-video-preview-service");
        backend = new InMemoryStorageBackend();
    }

    @AfterEach
    void teardown() throws Exception {
        ImageCoreTestSupport.deleteRecursively(tempDir);
    }

    @Test
    void getPreviewCachesGeneratedPreviewBytes() throws Exception {
        Path source = ImageCoreTestSupport.createVideoPlaceholder(tempDir, "video.mp4", 256);
        AtomicInteger generateCalls = new AtomicInteger();
        VideoPreviewGenerator generator = new VideoPreviewGenerator() {
            @Override
            public VideoPreviewPlan plan(Path sourcePath, VideoPreviewRequest request) {
                return new VideoPreviewPlan(1920, 1080, 12_000L, 320, 180);
            }

            @Override
            public byte[] generate(Path sourcePath, VideoPreviewPlan plan) {
                generateCalls.incrementAndGet();
                return ("preview-" + plan.getSeekMillis()).getBytes();
            }
        };
        VideoPreviewService service = new VideoPreviewService(generator, backend);

        var preview1 = service.getPreview(source, new VideoPreviewRequest());
        var preview2 = service.getPreview(source, new VideoPreviewRequest());

        assertArrayEquals(preview1.bytes(), preview2.bytes());
        assertEquals(1, generateCalls.get());
        assertTrue(backend.getObjects().keySet().stream().anyMatch(key -> key.startsWith("video-previews/")));
    }

    @Test
    void getPreviewThrowsFfmpegUnavailableWhenHealthServiceReportsUnavailable() throws Exception {
        Path source = ImageCoreTestSupport.createVideoPlaceholder(tempDir, "video.mp4", 256);
        VideoPreviewGenerator generator = new VideoPreviewGenerator() {
            @Override
            public VideoPreviewPlan plan(Path sourcePath, VideoPreviewRequest request) {
                throw new AssertionError("unused");
            }

            @Override
            public byte[] generate(Path sourcePath, VideoPreviewPlan plan) {
                throw new AssertionError("unused");
            }
        };
        HealthMonitor healthService = () -> false;
        VideoPreviewService service = new VideoPreviewService(generator, healthService, backend);

        NyxException exception = assertThrows(
            NyxException.class,
            () -> service.getPreview(source, new VideoPreviewRequest())
        );

        assertEquals(ErrorCode.FFMPEG_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void purgeCacheClearsPreviewEntries() {
        backend.write("video-previews/test/frame.jpg", new byte[16], "image/jpeg");
        VideoPreviewGenerator generator = new VideoPreviewGenerator() {
            @Override
            public VideoPreviewPlan plan(Path sourcePath, VideoPreviewRequest request) {
                throw new AssertionError("unused");
            }

            @Override
            public byte[] generate(Path sourcePath, VideoPreviewPlan plan) {
                throw new AssertionError("unused");
            }
        };
        VideoPreviewService service = new VideoPreviewService(generator, backend);

        service.purgeCache();

        assertFalse(backend.getObjects().containsKey("video-previews/test/frame.jpg"));
    }
}
