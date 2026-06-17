package com.nyx.playback.contracts;

import com.nyx.media.contracts.UserMediaState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MediaPlaystateProjectionTest {

    @Test
    void heartbeatBelowThresholdUpdatesResumeWithoutMarkingWatched() {
        UserMediaState projected = MediaPlaystateProjection.projectUserMediaStateFromPlaybackReport(
            new UserMediaState("alice", "object-1", null, false, null, true, 8, 0, null, null),
            "alice",
            "object-1",
            new MediaSessionPlaybackReport(MediaSessionPlaybackEvent.HEARTBEAT, null, null, 90_000L, 120_000L, null, null, null, null),
            "2026-04-12T10:00:00Z"
        );

        assertThat(projected).isNotNull();
        assertThat(projected.getResumePositionMillis()).isEqualTo(90_000L);
        assertThat(projected.getWatched()).isFalse();
        assertThat(projected.getPlayCount()).isZero();
        assertThat(projected.getLastPlayedAt()).isEqualTo("2026-04-12T10:00:00Z");
        assertThat(projected.getLastInteractionAt()).isEqualTo("2026-04-12T10:00:00Z");
        assertThat(projected.getFavorite()).isTrue();
        assertThat(projected.getRating()).isEqualTo(8);
    }

    @Test
    void thresholdHeartbeatMarksWatchedClearsResumeAndPreservesManualFields() {
        UserMediaState projected = MediaPlaystateProjection.projectUserMediaStateFromPlaybackReport(
            new UserMediaState("alice", "object-1", 30_000L, false, null, true, 9, 0, null, null),
            "alice",
            "object-1",
            new MediaSessionPlaybackReport(MediaSessionPlaybackEvent.HEARTBEAT, null, null, 95_000L, 100_000L, null, null, null, null),
            "2026-04-12T10:05:00Z"
        );

        assertThat(projected).isNotNull();
        assertThat(projected.getWatched()).isTrue();
        assertThat(projected.getResumePositionMillis()).isNull();
        assertThat(projected.getPlayCount()).isEqualTo(1);
        assertThat(projected.getWatchedAt()).isEqualTo("2026-04-12T10:05:00Z");
        assertThat(projected.getLastPlayedAt()).isEqualTo("2026-04-12T10:05:00Z");
        assertThat(projected.getFavorite()).isTrue();
        assertThat(projected.getRating()).isEqualTo(9);
    }

    @Test
    void completedMarksWatchedWithoutDurationAndDoesNotIncrementPlayCountTwice() {
        UserMediaState existing = new UserMediaState(
            "alice",
            "object-1",
            null,
            true,
            "2026-04-10T08:00:00Z",
            true,
            7,
            3,
            null,
            null
        );

        UserMediaState projected = MediaPlaystateProjection.projectUserMediaStateFromPlaybackReport(
            existing,
            "alice",
            "object-1",
            new MediaSessionPlaybackReport(MediaSessionPlaybackEvent.COMPLETED),
            "2026-04-12T10:10:00Z"
        );

        assertThat(projected).isNotNull();
        assertThat(projected.getWatched()).isTrue();
        assertThat(projected.getResumePositionMillis()).isNull();
        assertThat(projected.getPlayCount()).isEqualTo(3);
        assertThat(projected.getWatchedAt()).isEqualTo("2026-04-10T08:00:00Z");
        assertThat(projected.getLastPlayedAt()).isEqualTo("2026-04-12T10:10:00Z");
        assertThat(projected.getFavorite()).isTrue();
        assertThat(projected.getRating()).isEqualTo(7);
    }

    @Test
    void startedReportDoesNotCreateADurablePlaystateMutation() {
        UserMediaState projected = MediaPlaystateProjection.projectUserMediaStateFromPlaybackReport(
            null,
            "alice",
            "object-1",
            new MediaSessionPlaybackReport(MediaSessionPlaybackEvent.STARTED, null, null, 1_000L, 120_000L, null, null, null, null),
            "2026-04-12T10:15:00Z"
        );

        assertThat(projected).isNull();
    }
}
