package com.nyx.playback.contracts;

import com.nyx.media.contracts.UserMediaState;

public final class MediaPlaystateProjection {
    public static final double PLAYBACK_WATCHED_PROGRESS_THRESHOLD_PERCENT = 95.0;

    private MediaPlaystateProjection() {
    }

    public static UserMediaState projectUserMediaStateFromPlaybackReport(
        UserMediaState existing,
        String userId,
        String objectId,
        MediaSessionPlaybackReport report,
        String occurredAt
    ) {
        UserMediaState state = existing == null
            ? new UserMediaState(userId, objectId, null, false, null, false, null, 0, null, null)
            : existing;
        boolean shouldMarkWatched = shouldMarkWatched(report);
        boolean shouldUpdateResume = updatesResumePosition(report.event()) && report.positionMillis() != null;

        if (!shouldMarkWatched && !shouldUpdateResume) {
            return null;
        }

        boolean watched = state.getWatched() || shouldMarkWatched;
        String watchedAt;
        if (!watched) {
            watchedAt = null;
        } else if (state.getWatched()) {
            watchedAt = state.getWatchedAt() != null ? state.getWatchedAt() : occurredAt;
        } else {
            watchedAt = occurredAt;
        }

        int playCount = watched && !state.getWatched() ? state.getPlayCount() + 1 : state.getPlayCount();
        Long resumePositionMillis = watched ? null : (shouldUpdateResume ? report.positionMillis() : state.getResumePositionMillis());
        String lastPlayedAt = watched || shouldUpdateResume ? occurredAt : state.getLastPlayedAt();
        String lastInteractionAt = watched || shouldUpdateResume ? occurredAt : state.getLastInteractionAt();

        return new UserMediaState(
            state.getUserId(),
            state.getObjectId(),
            resumePositionMillis,
            watched,
            watchedAt,
            state.getFavorite(),
            state.getRating(),
            playCount,
            lastPlayedAt,
            lastInteractionAt
        );
    }

    private static boolean shouldMarkWatched(MediaSessionPlaybackReport report) {
        return switch (report.event()) {
            case COMPLETED -> true;
            case HEARTBEAT, STOPPED -> {
                Double progressPercent = progressPercent(report);
                yield progressPercent != null && progressPercent >= PLAYBACK_WATCHED_PROGRESS_THRESHOLD_PERCENT;
            }
            case STARTED -> false;
        };
    }

    private static boolean updatesResumePosition(MediaSessionPlaybackEvent event) {
        return event == MediaSessionPlaybackEvent.HEARTBEAT
            || event == MediaSessionPlaybackEvent.STOPPED;
    }

    private static Double progressPercent(MediaSessionPlaybackReport report) {
        Long positionMillis = report.positionMillis();
        Long durationMillis = report.durationMillis();
        if (positionMillis == null || durationMillis == null || durationMillis <= 0L) {
            return null;
        }
        return (positionMillis.doubleValue() / durationMillis.doubleValue()) * 100.0;
    }
}
