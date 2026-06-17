package com.nyx.http;

import java.util.Locale;

public final class ContentType {
    public static final Companion Companion = new Companion();
    public static final ApplicationTypes Application = new ApplicationTypes();
    public static final TextContentTypes Text = new TextContentTypes();
    public static final ImageContentTypes Image = new ImageContentTypes();
    public static final AudioContentTypes Audio = new AudioContentTypes();
    public static final VideoContentTypes Video = new VideoContentTypes();

    private final String value;

    public ContentType(String value) {
        this.value = value;
    }

    public ContentType(String contentType, String contentSubtype) {
        this(contentType + "/" + contentSubtype);
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ContentType that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }

    public boolean match(ContentType other) {
        String left = normalize(value);
        String right = normalize(other.value);
        if (left.equals(right)) {
            return true;
        }
        if (left.endsWith("/*")) {
            return right.startsWith(left.substring(0, left.length() - 1));
        }
        if (right.endsWith("/*")) {
            return left.startsWith(right.substring(0, right.length() - 1));
        }
        return false;
    }

    public static ContentType parse(String value) {
        return new ContentType(value);
    }

    private static String normalize(String value) {
        int parameterStart = value.indexOf(';');
        String base = parameterStart >= 0 ? value.substring(0, parameterStart) : value;
        return base.trim().toLowerCase(Locale.ROOT);
    }

    public static final class Companion {
        private Companion() {
        }

        public ContentType parse(String value) {
            return ContentType.parse(value);
        }
    }

    public static final class ApplicationTypes {
        public final ApplicationTypes INSTANCE = this;
        public final ContentType Json = new ContentType("application/json");
        public final ContentType Xml = new ContentType("application/xml");
        public final ContentType Zip = new ContentType("application/zip");
        public final ContentType OctetStream = new ContentType("application/octet-stream");

        private ApplicationTypes() {
        }

        public ContentType getJson() {
            return Json;
        }

        public ContentType getXml() {
            return Xml;
        }

        public ContentType getZip() {
            return Zip;
        }

        public ContentType getOctetStream() {
            return OctetStream;
        }
    }

    public static final class TextContentTypes {
        public final TextContentTypes INSTANCE = this;
        public final ContentType Plain = new ContentType("text/plain");
        public final ContentType Html = new ContentType("text/html");

        private TextContentTypes() {
        }

        public ContentType getPlain() {
            return Plain;
        }

        public ContentType getHtml() {
            return Html;
        }
    }

    public static final class ImageContentTypes {
        public final ImageContentTypes INSTANCE = this;
        public final ContentType Any = new ContentType("image/*");
        public final ContentType JPEG = new ContentType("image/jpeg");

        private ImageContentTypes() {
        }

        public ContentType getAny() {
            return Any;
        }

        public ContentType getJPEG() {
            return JPEG;
        }
    }

    public static final class AudioContentTypes {
        public final AudioContentTypes INSTANCE = this;
        public final ContentType Any = new ContentType("audio/*");

        private AudioContentTypes() {
        }

        public ContentType getAny() {
            return Any;
        }
    }

    public static final class VideoContentTypes {
        public final VideoContentTypes INSTANCE = this;
        public final ContentType Any = new ContentType("video/*");

        private VideoContentTypes() {
        }

        public ContentType getAny() {
            return Any;
        }
    }
}
