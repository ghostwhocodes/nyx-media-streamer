package com.nyx.http;

import io.javalin.http.HttpStatus;

public final class HttpStatusCode {
    public static final Companion Companion = new Companion();

    public static final HttpStatusCode OK = new HttpStatusCode(HttpStatus.OK);
    public static final HttpStatusCode Created = new HttpStatusCode(HttpStatus.CREATED);
    public static final HttpStatusCode Accepted = new HttpStatusCode(HttpStatus.ACCEPTED);
    public static final HttpStatusCode PartialContent = new HttpStatusCode(HttpStatus.PARTIAL_CONTENT);
    public static final HttpStatusCode NoContent = new HttpStatusCode(HttpStatus.NO_CONTENT);
    public static final HttpStatusCode NotModified = new HttpStatusCode(HttpStatus.NOT_MODIFIED);
    public static final HttpStatusCode BadRequest = new HttpStatusCode(HttpStatus.BAD_REQUEST);
    public static final HttpStatusCode Unauthorized = new HttpStatusCode(HttpStatus.UNAUTHORIZED);
    public static final HttpStatusCode Forbidden = new HttpStatusCode(HttpStatus.FORBIDDEN);
    public static final HttpStatusCode NotFound = new HttpStatusCode(HttpStatus.NOT_FOUND);
    public static final HttpStatusCode Conflict = new HttpStatusCode(HttpStatus.CONFLICT);
    public static final HttpStatusCode PayloadTooLarge = new HttpStatusCode(HttpStatus.CONTENT_TOO_LARGE);
    public static final HttpStatusCode TooManyRequests = new HttpStatusCode(HttpStatus.TOO_MANY_REQUESTS);
    public static final HttpStatusCode InternalServerError = new HttpStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
    public static final HttpStatusCode ServiceUnavailable = new HttpStatusCode(HttpStatus.SERVICE_UNAVAILABLE);

    private final HttpStatus status;

    private HttpStatusCode(HttpStatus status) {
        this.status = status;
    }

    public int getValue() {
        return status.getCode();
    }

    public HttpStatus toJavalinStatus() {
        return status;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof HttpStatusCode that && status.getCode() == that.status.getCode();
    }

    @Override
    public int hashCode() {
        return status.getCode();
    }

    @Override
    public String toString() {
        return status.getCode() + " " + status.getMessage();
    }

    public static HttpStatusCode fromValue(int statusCode) {
        HttpStatus status = HttpStatus.forStatus(statusCode);
        HttpStatusCode shared = fromJavalinStatus(status);
        return shared != null ? shared : new HttpStatusCode(status);
    }

    public static HttpStatusCode fromJavalinStatus(HttpStatus status) {
        if (status == null) {
            return null;
        }
        if (status == HttpStatus.OK) {
            return OK;
        }
        if (status == HttpStatus.CREATED) {
            return Created;
        }
        if (status == HttpStatus.ACCEPTED) {
            return Accepted;
        }
        if (status == HttpStatus.PARTIAL_CONTENT) {
            return PartialContent;
        }
        if (status == HttpStatus.NO_CONTENT) {
            return NoContent;
        }
        if (status == HttpStatus.NOT_MODIFIED) {
            return NotModified;
        }
        if (status == HttpStatus.BAD_REQUEST) {
            return BadRequest;
        }
        if (status == HttpStatus.UNAUTHORIZED) {
            return Unauthorized;
        }
        if (status == HttpStatus.FORBIDDEN) {
            return Forbidden;
        }
        if (status == HttpStatus.NOT_FOUND) {
            return NotFound;
        }
        if (status == HttpStatus.CONFLICT) {
            return Conflict;
        }
        if (status == HttpStatus.CONTENT_TOO_LARGE) {
            return PayloadTooLarge;
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            return TooManyRequests;
        }
        if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
            return InternalServerError;
        }
        if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            return ServiceUnavailable;
        }
        return new HttpStatusCode(status);
    }

    public static final class Companion {
        private Companion() {
        }

        public HttpStatusCode getOK() {
            return OK;
        }

        public HttpStatusCode getCreated() {
            return Created;
        }

        public HttpStatusCode getAccepted() {
            return Accepted;
        }

        public HttpStatusCode getPartialContent() {
            return PartialContent;
        }

        public HttpStatusCode getNoContent() {
            return NoContent;
        }

        public HttpStatusCode getNotModified() {
            return NotModified;
        }

        public HttpStatusCode getBadRequest() {
            return BadRequest;
        }

        public HttpStatusCode getUnauthorized() {
            return Unauthorized;
        }

        public HttpStatusCode getForbidden() {
            return Forbidden;
        }

        public HttpStatusCode getNotFound() {
            return NotFound;
        }

        public HttpStatusCode getConflict() {
            return Conflict;
        }

        public HttpStatusCode getPayloadTooLarge() {
            return PayloadTooLarge;
        }

        public HttpStatusCode getTooManyRequests() {
            return TooManyRequests;
        }

        public HttpStatusCode getInternalServerError() {
            return InternalServerError;
        }

        public HttpStatusCode getServiceUnavailable() {
            return ServiceUnavailable;
        }

        public HttpStatusCode fromValue(int statusCode) {
            return HttpStatusCode.fromValue(statusCode);
        }

        public HttpStatusCode fromJavalinStatus(HttpStatus status) {
            return HttpStatusCode.fromJavalinStatus(status);
        }
    }
}
