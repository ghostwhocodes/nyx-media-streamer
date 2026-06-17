package com.nyx.ffmpeg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.json.NyxJson;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoTrickplayModelsTest {
    private final ObjectMapper json = NyxJson.newMapper();

    @Test
    void videoTrickplayRequestDefaultsToAdditiveAssetSet() throws Exception {
        VideoTrickplayRequest decoded = json.readValue("{}", VideoTrickplayRequest.class);

        assertEquals(Set.of(TrickplayAssetKind.STORYBOARD_SHEET, TrickplayAssetKind.PREVIEW_STRIP), decoded.assetKinds());
        assertNull(decoded.intervalMillis());
        assertNull(decoded.thumbnailWidth());
        assertNull(decoded.thumbnailHeight());
        assertNull(decoded.tileColumns());
        assertNull(decoded.tileRows());
    }

    @Test
    void videoTrickplayPlanCacheKeyIsDeterministicFromNormalizedAssetPlans() {
        VideoTrickplayPlan plan = new VideoTrickplayPlan(
            1920,
            1080,
            600_000L,
            10_000L,
            320,
            180,
            4,
            4,
            List.of(
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
                ),
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
            ),
            List.of(
                new VideoTrickplayTimelineEntry(0L, TrickplayAssetKind.STORYBOARD_SHEET, 0, 0, 0),
                new VideoTrickplayTimelineEntry(0L, TrickplayAssetKind.PREVIEW_STRIP, 1, 0, 0)
            )
        );

        assertEquals(22, plan.getTotalFrames());
        assertTrue(plan.getCacheKey().contains("interval-10000"));
        assertTrue(plan.getCacheKey().contains("storyboard_sheet_asset-0"));
        assertTrue(plan.getCacheKey().contains("preview_strip_asset-1"));
        assertEquals(2, plan.timeline().size());
    }

    @Test
    void videoTrickplayAssetPlansSerializeExplicitGeometry() throws Exception {
        String encoded = json.writeValueAsString(
            new VideoTrickplayAssetPlan(
                TrickplayAssetKind.STORYBOARD_SHEET,
                2,
                160_000L,
                319_999L,
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

        assertTrue(encoded.contains("\"kind\":\"STORYBOARD_SHEET\""));
        assertTrue(encoded.contains("\"assetIndex\":2"));
        assertTrue(encoded.contains("\"tileColumns\":4"));
        assertTrue(encoded.contains("\"outputWidth\":1280"));
    }
}
