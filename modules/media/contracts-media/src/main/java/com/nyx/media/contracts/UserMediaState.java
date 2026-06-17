package com.nyx.media.contracts;

public record UserMediaState(
    String userId,
    String objectId,
    Long resumePositionMillis,
    boolean watched,
    String watchedAt,
    boolean favorite,
    Integer rating,
    int playCount,
    String lastPlayedAt,
    String lastInteractionAt
) {
    public UserMediaState(String userId, String objectId) {
        this(userId, objectId, null, false, null, false, null, 0, null, null);
    }

    public String getUserId() {
        return userId;
    }

    public String getObjectId() {
        return objectId;
    }

    public Long getResumePositionMillis() {
        return resumePositionMillis;
    }

    public boolean getWatched() {
        return watched;
    }

    public String getWatchedAt() {
        return watchedAt;
    }

    public boolean getFavorite() {
        return favorite;
    }

    public Integer getRating() {
        return rating;
    }

    public int getPlayCount() {
        return playCount;
    }

    public String getLastPlayedAt() {
        return lastPlayedAt;
    }

    public String getLastInteractionAt() {
        return lastInteractionAt;
    }
}
