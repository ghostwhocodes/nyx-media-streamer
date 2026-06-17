package com.nyx.common;

import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import io.javalin.Javalin;

public final class ErrorResponsesConfig {
    private ErrorResponsesConfig() {
    }

    public static void configureErrorHandling(Javalin application) {
        application.unsafe.routes.exception(NyxException.class, (cause, ctx) -> {
            if (cause.getErrorCode() == ErrorCode.QUOTA_EXCEEDED) {
                ctx.header(HttpHeaders.RetryAfter, "60");
            }
            ctx.status(cause.getErrorCode().getHttpStatus().getValue());
            ctx.json(new ErrorResponse(new ErrorDetail(
                cause.getErrorCode().name(),
                cause.getMessage(),
                cause.getDetails()
            )));
        });
        application.unsafe.routes.exception(PathNotAllowedException.class, (cause, ctx) -> {
            ctx.status(HttpStatusCode.Companion.getForbidden().getValue());
            ctx.json(new ErrorResponse(new ErrorDetail(
                ErrorCode.PATH_NOT_ALLOWED.name(),
                cause.getMessage() != null ? cause.getMessage() : "Path is not allowed"
            )));
        });
        application.unsafe.routes.exception(PathNotFoundException.class, (cause, ctx) -> {
            ctx.status(HttpStatusCode.Companion.getNotFound().getValue());
            ctx.json(new ErrorResponse(new ErrorDetail(
                ErrorCode.FILE_NOT_FOUND.name(),
                cause.getMessage() != null ? cause.getMessage() : "File not found"
            )));
        });
        application.unsafe.routes.exception(VirtualRootNotFoundException.class, (cause, ctx) -> {
            ctx.status(HttpStatusCode.Companion.getNotFound().getValue());
            ctx.json(new ErrorResponse(new ErrorDetail(
                ErrorCode.VIRTUAL_ROOT_NOT_FOUND.name(),
                cause.getMessage() != null ? cause.getMessage() : "Virtual root not found"
            )));
        });
        application.unsafe.routes.exception(InvalidPathRequestException.class, (cause, ctx) -> {
            ctx.status(HttpStatusCode.Companion.getBadRequest().getValue());
            ctx.json(new ErrorResponse(new ErrorDetail(
                ErrorCode.INVALID_REQUEST.name(),
                cause.getMessage() != null ? cause.getMessage() : "Invalid path request"
            )));
        });
        application.unsafe.routes.exception(PathNotDirectoryException.class, (cause, ctx) -> {
            ctx.status(HttpStatusCode.Companion.getBadRequest().getValue());
            ctx.json(new ErrorResponse(new ErrorDetail(
                ErrorCode.INVALID_REQUEST.name(),
                cause.getMessage() != null ? cause.getMessage() : "Path is not a directory"
            )));
        });
        application.unsafe.routes.exception(InvalidJobTransitionException.class, (cause, ctx) -> {
            ctx.status(HttpStatusCode.Companion.getConflict().getValue());
            ctx.json(new ErrorResponse(new ErrorDetail(
                ErrorCode.INVALID_JOB_TRANSITION.name(),
                cause.getMessage()
            )));
        });
        application.unsafe.routes.exception(IllegalArgumentException.class, (cause, ctx) -> {
            ctx.status(HttpStatusCode.Companion.getBadRequest().getValue());
            ctx.json(new ErrorResponse(new ErrorDetail(
                ErrorCode.INVALID_REQUEST.name(),
                cause.getMessage() != null ? cause.getMessage() : "Invalid request"
            )));
        });
    }
}
