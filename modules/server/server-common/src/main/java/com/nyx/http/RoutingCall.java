package com.nyx.http;

import io.javalin.http.Context;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;

public final class RoutingCall {
    private static final String REQUEST_ABORTED_ATTRIBUTE = "nyx.request.aborted";

    private final Context context;
    private final Parameters parameters;
    private final Parameters pathParameters;
    private final Parameters queryParameters;
    private final RoutingRequest request;
    private final RoutingResponse response;

    public RoutingCall(Context context) {
        this.context = context;
        this.parameters = new Parameters(name -> context.pathParamMap().get(name));
        this.pathParameters = parameters;
        this.queryParameters = new Parameters(context::queryParam);
        this.request = new RoutingRequest(context);
        this.response = new RoutingResponse(context);
    }

    public Context getContext() {
        return context;
    }

    public Parameters getParameters() {
        return parameters;
    }

    public Parameters getPathParameters() {
        return pathParameters;
    }

    public Parameters getQueryParameters() {
        return queryParameters;
    }

    public RoutingRequest getRequest() {
        return request;
    }

    public RoutingResponse getResponse() {
        return response;
    }

    @SuppressWarnings("unchecked")
    public <T> T receive(Class<T> type) {
        try {
            if (type == byte[].class) {
                return (T) context.bodyAsBytes();
            }
            if (type == String.class) {
                return (T) context.body();
            }
            return context.bodyAsClass(type);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid request body", exception);
        }
    }

    public String receiveText() {
        return context.body();
    }

    public <T> T principal(Class<T> type) {
        Object value = context.attribute(Route.AUTH_PRINCIPAL_ATTRIBUTE);
        return type.isInstance(value) ? type.cast(value) : null;
    }

    public void respond(HttpStatusCode status) {
        context.status(status.toJavalinStatus());
    }

    public void respond(HttpStatusCode status, Object body) {
        context.status(status.toJavalinStatus());
        respond(body);
    }

    public void respond(Object body) {
        if (body instanceof byte[] bytes) {
            context.result(bytes);
            return;
        }
        if (body instanceof String text) {
            context.result(text);
            return;
        }
        context.json(body);
    }

    public void respondText(String text, ContentType contentType, HttpStatusCode status) {
        if (status != null) {
            context.status(status.toJavalinStatus());
        }
        if (contentType != null) {
            context.contentType(contentType.getValue());
        }
        context.result(text);
    }

    public void respondText(String text, ContentType contentType) {
        respondText(text, contentType, null);
    }

    public void respondText(String text) {
        respondText(text, null, null);
    }

    public void respondBytes(byte[] bytes, ContentType contentType) {
        if (contentType != null) {
            context.contentType(contentType.getValue());
            maybeDisableCompression(contentType.getValue());
        }
        context.result(bytes);
    }

    public void respondBytes(byte[] bytes) {
        respondBytes(bytes, null);
    }

    public void respondBytesWriter(Consumer<? super OutputStream> block) {
        block.accept(context.outputStream());
    }

    public void respondOutputStream(ContentType contentType, Consumer<? super OutputStream> block) {
        if (contentType != null) {
            context.contentType(contentType.getValue());
            maybeDisableCompression(contentType.getValue());
        }
        block.accept(context.outputStream());
    }

    public void respondOutputStream(Consumer<? super OutputStream> block) {
        respondOutputStream(null, block);
    }

    public void respondFile(Path path, String contentType) {
        context.contentType(contentType);
        maybeDisableCompression(contentType);
        try {
            context.writeSeekableStream(Files.newInputStream(path), contentType, Files.size(path));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to stream file: " + path, exception);
        }
    }

    public void attribute(String name, Object value) {
        context.attribute(name, value);
    }

    public void redirect(String location) {
        context.redirect(location);
    }

    public String requestIp() {
        return context.ip();
    }

    public void abort() {
        context.attribute(REQUEST_ABORTED_ATTRIBUTE, true);
        context.skipRemainingHandlers();
    }

    private void maybeDisableCompression(String contentType) {
        String normalized = substringBefore(contentType, ";").trim().toLowerCase(Locale.ROOT);
        if (
            normalized.startsWith("video/")
                || normalized.startsWith("audio/")
                || normalized.startsWith("image/")
                || normalized.equals("application/octet-stream")
        ) {
            context.disableCompression();
        }
    }

    private static String substringBefore(String value, String delimiter) {
        int index = value.indexOf(delimiter);
        return index >= 0 ? value.substring(0, index) : value;
    }
}
