package com.nyx.common;

import com.nyx.http.HttpStatusCode;

public enum ErrorCode {
    FILE_NOT_FOUND(HttpStatusCode.Companion.getNotFound()),
    PATH_NOT_ALLOWED(HttpStatusCode.Companion.getForbidden()),
    INVALID_REQUEST(HttpStatusCode.Companion.getBadRequest()),
    CODEC_UNSUPPORTED(HttpStatusCode.Companion.getBadRequest()),
    TRANSCODE_FAILED(HttpStatusCode.Companion.getInternalServerError()),
    JOB_NOT_FOUND(HttpStatusCode.Companion.getNotFound()),
    QUEUE_FULL(HttpStatusCode.Companion.getServiceUnavailable()),
    PROBE_FAILED(HttpStatusCode.Companion.getInternalServerError()),
    FORM_NOT_FOUND(HttpStatusCode.Companion.getNotFound()),
    VALIDATION_ERROR(HttpStatusCode.Companion.getBadRequest()),
    METADATA_NOT_FOUND(HttpStatusCode.Companion.getNotFound()),
    SCHEMA_CONFLICT(HttpStatusCode.Companion.getConflict()),
    IMPORT_ERROR(HttpStatusCode.Companion.getBadRequest()),
    EXPORT_ERROR(HttpStatusCode.Companion.getInternalServerError()),
    IMAGE_NOT_FOUND(HttpStatusCode.Companion.getNotFound()),
    AUDIO_NOT_FOUND(HttpStatusCode.Companion.getNotFound()),
    MEDIA_OBJECT_NOT_FOUND(HttpStatusCode.Companion.getNotFound()),
    LIBRARY_NOT_FOUND(HttpStatusCode.Companion.getNotFound()),
    LIBRARY_ITEM_NOT_FOUND(HttpStatusCode.Companion.getNotFound()),
    LIBRARY_COLLECTION_NOT_FOUND(HttpStatusCode.Companion.getNotFound()),
    PLAYLIST_NOT_FOUND(HttpStatusCode.Companion.getNotFound()),
    CHAPTER_MARK_NOT_FOUND(HttpStatusCode.Companion.getNotFound()),
    INVALID_THUMBNAIL_SIZE(HttpStatusCode.Companion.getBadRequest()),
    TRANSCODE_ERROR(HttpStatusCode.Companion.getInternalServerError()),
    INVALID_JOB_TRANSITION(HttpStatusCode.Companion.getConflict()),
    RATE_LIMITED(HttpStatusCode.Companion.getTooManyRequests()),
    REQUEST_TOO_LARGE(HttpStatusCode.Companion.getPayloadTooLarge()),
    VIRTUAL_ROOT_NOT_FOUND(HttpStatusCode.Companion.getNotFound()),
    USER_NOT_FOUND(HttpStatusCode.Companion.getNotFound()),
    USER_ALREADY_EXISTS(HttpStatusCode.Companion.getConflict()),
    FFMPEG_UNAVAILABLE(HttpStatusCode.Companion.getServiceUnavailable()),
    WEBHOOK_NOT_FOUND(HttpStatusCode.Companion.getNotFound()),
    WEBHOOK_URL_NOT_ALLOWED(HttpStatusCode.Companion.getForbidden()),
    QUOTA_EXCEEDED(HttpStatusCode.Companion.getTooManyRequests()),
    BACKUP_FAILED(HttpStatusCode.Companion.getInternalServerError()),
    STORAGE_ERROR(HttpStatusCode.Companion.getInternalServerError());

    private final HttpStatusCode httpStatus;

    ErrorCode(HttpStatusCode httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatusCode getHttpStatus() {
        return httpStatus;
    }
}
