package com.nyx.media;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ExifExtractor {
    private static final Set<String> SENSITIVE_DIRECTORY_PREFIXES = Set.of("GPS");
    private static final Set<String> SENSITIVE_TAG_NAMES = Set.of(
        "Camera Serial Number",
        "Body Serial Number",
        "Lens Serial Number",
        "Serial Number",
        "Owner Name",
        "Camera Owner Name",
        "Artist",
        "Software"
    );

    private final Logger log = LoggerFactory.getLogger(ExifExtractor.class);

    public Map<String, Object> extractExif(Path path) {
        try {
            var metadata = ImageMetadataReader.readMetadata(path.toFile());
            Map<String, Object> result = new HashMap<>();
            for (Directory directory : metadata.getDirectories()) {
                String directoryName = directory.getName();
                for (Tag tag : directory.getTags()) {
                    String key = directoryName + "/" + tag.getTagName();
                    result.put(key, tag.getDescription() == null ? "" : tag.getDescription());
                }
            }
            return result;
        } catch (Exception exception) {
            log.debug("Could not extract EXIF from {}: {}", path.getFileName(), exception.getMessage());
            return Map.of();
        }
    }

    public Map<String, Object> extractStructured(Path path) {
        try {
            var metadata = ImageMetadataReader.readMetadata(path.toFile());
            Map<String, Object> result = new HashMap<>();

            ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (ifd0 != null) {
                putString(result, "cameraMake", ifd0.getString(ExifIFD0Directory.TAG_MAKE));
                putString(result, "cameraModel", ifd0.getString(ExifIFD0Directory.TAG_MODEL));
                putString(result, "software", ifd0.getString(ExifIFD0Directory.TAG_SOFTWARE));
                putInteger(result, "orientation", ifd0.getInteger(ExifIFD0Directory.TAG_ORIENTATION));
            }

            ExifSubIFDDirectory subIfd = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            if (subIfd != null) {
                putString(result, "exposureTime", subIfd.getString(ExifSubIFDDirectory.TAG_EXPOSURE_TIME));
                putString(result, "fNumber", subIfd.getString(ExifSubIFDDirectory.TAG_FNUMBER));
                putString(result, "iso", subIfd.getString(ExifSubIFDDirectory.TAG_ISO_EQUIVALENT));
                putString(result, "focalLength", subIfd.getString(ExifSubIFDDirectory.TAG_FOCAL_LENGTH));
                if (subIfd.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL) != null) {
                    result.put(
                        "dateTaken",
                        subIfd.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL).toInstant().toString()
                    );
                }
                putInteger(result, "imageWidth", subIfd.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH));
                putInteger(result, "imageHeight", subIfd.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT));
            }

            GpsDirectory gps = metadata.getFirstDirectoryOfType(GpsDirectory.class);
            if (gps != null) {
                if (gps.getGeoLocation() != null) {
                    result.put("gpsLatitude", gps.getGeoLocation().getLatitude());
                    result.put("gpsLongitude", gps.getGeoLocation().getLongitude());
                }
                putString(result, "gpsAltitude", gps.getString(GpsDirectory.TAG_ALTITUDE));
            }

            return result;
        } catch (Exception exception) {
            log.debug("Could not extract structured EXIF from {}: {}", path.getFileName(), exception.getMessage());
            return Map.of();
        }
    }

    public static boolean isSensitiveTag(String directoryName, String tagName) {
        for (String prefix : SENSITIVE_DIRECTORY_PREFIXES) {
            if (directoryName.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return true;
            }
        }
        return SENSITIVE_TAG_NAMES.contains(tagName);
    }

    public byte[] stripSensitiveExif(Path path) {
        String extension = extension(path).toLowerCase();
        return switch (extension) {
            case "jpg", "jpeg" -> stripJpegExif(path);
            default -> stripViaImageIo(path, extension);
        };
    }

    private byte[] stripViaImageIo(Path path, String extension) {
        String formatName = switch (extension) {
            case "png" -> "png";
            case "webp" -> "webp";
            case "bmp" -> "bmp";
            case "gif" -> "gif";
            case "tiff", "tif" -> "tiff";
            default -> null;
        };

        if (formatName != null) {
            try {
                var image = ImageIO.read(path.toFile());
                if (image != null) {
                    var writers = ImageIO.getImageWritersByFormatName(formatName);
                    if (writers.hasNext()) {
                        ByteArrayOutputStream output = new ByteArrayOutputStream();
                        var writer = writers.next();
                        try (var imageOutputStream = ImageIO.createImageOutputStream(output)) {
                            writer.setOutput(imageOutputStream);
                            writer.write(image);
                            imageOutputStream.flush();
                        } finally {
                            writer.dispose();
                        }
                        return output.toByteArray();
                    }
                }
            } catch (IOException exception) {
                return sneakyThrow(exception);
            }
        }

        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            return sneakyThrow(exception);
        }
    }

    private byte[] stripJpegExif(Path path) {
        try {
            var image = ImageIO.read(path.toFile());
            if (image == null) {
                throw new IOException("Could not read image: " + path);
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            var writers = ImageIO.getImageWritersByFormatName("jpeg");
            if (!writers.hasNext()) {
                throw new IllegalStateException("No JPEG writer available");
            }

            var writer = writers.next();
            try {
                ImageWriteParam param = writer.getDefaultWriteParam();
                if (param.canWriteCompressed()) {
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(0.95f);
                }

                var imageOutputStream = ImageIO.createImageOutputStream(output);
                writer.setOutput(imageOutputStream);
                writer.write(null, new IIOImage(image, null, null), param);
                imageOutputStream.flush();
            } finally {
                writer.dispose();
            }

            return output.toByteArray();
        } catch (IOException exception) {
            return sneakyThrow(exception);
        }
    }

    private static void putString(Map<String, Object> target, String key, String value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static void putInteger(Map<String, Object> target, String key, Integer value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static String extension(Path path) {
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex >= 0 ? fileName.substring(dotIndex + 1) : "";
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }
}
