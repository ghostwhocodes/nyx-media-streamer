package com.nyx.media;

import com.nyx.common.MediaTypes;
import com.nyx.common.storage.StorageBackend;
import com.nyx.common.storage.StorageCacheHelper;
import com.nyx.media.contracts.ImageTransformFit;
import com.nyx.media.contracts.ImageTransformRequest;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ImageTransformService {
    private static final Set<String> JPEG_EXTENSIONS = Set.of("jpg", "jpeg");
    private static final Set<String> TIFF_EXTENSIONS = Set.of("tif", "tiff");

    private final StrippedImageCache strippedImageCache;
    private final long maxCacheSizeBytes;
    private final StorageBackend storageBackend;
    private final Logger log = LoggerFactory.getLogger(ImageTransformService.class);
    @SuppressWarnings("unused")
    private final ScheduledFuture<?> cleanupTask;

    public ImageTransformService(StrippedImageCache strippedImageCache, StorageBackend storageBackend) {
        this(strippedImageCache, 1024L * 1024 * 1024, null, 60, storageBackend);
    }

    public ImageTransformService(
        StrippedImageCache strippedImageCache,
        long maxCacheSizeBytes,
        StorageBackend storageBackend
    ) {
        this(strippedImageCache, maxCacheSizeBytes, null, 60, storageBackend);
    }

    public ImageTransformService(
        StrippedImageCache strippedImageCache,
        long maxCacheSizeBytes,
        ScheduledExecutorService cleanupScheduler,
        int cleanupIntervalMinutes,
        StorageBackend storageBackend
    ) {
        this.strippedImageCache = strippedImageCache;
        this.maxCacheSizeBytes = maxCacheSizeBytes;
        this.storageBackend = storageBackend;
        this.cleanupTask = cleanupScheduler == null ? null : scheduleCleanup(cleanupScheduler, cleanupIntervalMinutes);
    }

    public ImageTransformPlan plan(Path sourcePath, ImageTransformRequest request) {
        if (!Files.isRegularFile(sourcePath)) {
            throw new IllegalArgumentException("Source file does not exist: " + sourcePath);
        }

        String mimeType = MediaTypes.detectMimeType(sourcePath);
        if (!MediaTypes.isImage(mimeType)) {
            throw new IllegalArgumentException("Source file is not an image: " + sourcePath);
        }

        ImageTransformRequest normalized = ImageTransformRequestSupport.normalized(request);
        int[] dimensions = readImageDimensions(sourcePath);
        if (dimensions == null) {
            return sneakyThrow(new IOException("Could not read image dimensions: " + sourcePath));
        }

        OutputEncoding encoding = resolveOutputEncoding(sourcePath, mimeType);
        return buildPlan(dimensions[0], dimensions[1], normalized, encoding);
    }

    public ImageTransformOutput getImage(Path sourcePath, ImageTransformRequest request) {
        ImageTransformPlan plan = plan(sourcePath, request);
        if (!plan.isRequiresTransformation()) {
            return new ImageTransformOutput(strippedImageCache.getStrippedImage(sourcePath), plan);
        }

        String hash = StorageCacheHelper.hashPath(sourcePath.toAbsolutePath().toString());
        String storageKey = "image-transforms/" + hash + "/" + plan.getCacheKey() + "." + plan.outputExtension();

        var cacheResult = StorageCacheHelper.getOrGenerate(
            storageBackend,
            storageKey,
            sourcePath,
            plan.outputMimeType(),
            () -> renderTransformedBytes(sourcePath, plan)
        );

        return new ImageTransformOutput(cacheResult.getData(), plan);
    }

    public void cleanupStorageCache() {
        StorageCacheHelper.cleanupLRU(storageBackend, "image-transforms", maxCacheSizeBytes, log);
    }

    public void purgeCache() {
        storageBackend.deletePrefix("image-transforms");
    }

    private ScheduledFuture<?> scheduleCleanup(ScheduledExecutorService scheduler, int cleanupIntervalMinutes) {
        Runnable task = () -> {
            try {
                cleanupStorageCache();
            } catch (Exception exception) {
                log.warn("Image transform cache cleanup failed: {}", exception.getMessage());
            }
        };
        if (cleanupIntervalMinutes > 0) {
            long intervalMs = cleanupIntervalMinutes * 60_000L;
            return scheduler.scheduleWithFixedDelay(task, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        }
        return scheduler.schedule(task, 0L, TimeUnit.MILLISECONDS);
    }

    private byte[] renderTransformedBytes(Path sourcePath, ImageTransformPlan plan) {
        byte[] strippedBytes = strippedImageCache.getStrippedImage(sourcePath);

        BufferedImage sourceImage;
        try {
            sourceImage = ImageIO.read(new ByteArrayInputStream(strippedBytes));
        } catch (IOException exception) {
            return sneakyThrow(exception);
        }
        if (sourceImage == null) {
            return sneakyThrow(new IOException("Could not decode stripped image: " + sourcePath));
        }

        BufferedImage scaled;
        if (sourceImage.getWidth() == plan.scaledWidth() && sourceImage.getHeight() == plan.scaledHeight()) {
            scaled = sourceImage;
        } else {
            scaled = resize(sourceImage, plan.scaledWidth(), plan.scaledHeight());
        }

        BufferedImage finalImage;
        if (plan.isRequiresCrop()) {
            finalImage = crop(scaled, plan.cropX(), plan.cropY(), plan.outputWidth(), plan.outputHeight());
        } else {
            finalImage = scaled;
        }

        return encode(finalImage, plan);
    }

    private ImageTransformPlan buildPlan(
        int sourceWidth,
        int sourceHeight,
        ImageTransformRequest request,
        OutputEncoding encoding
    ) {
        boolean hasWidthConstraint = request.width() != null || request.maxWidth() != null;
        boolean hasHeightConstraint = request.height() != null || request.maxHeight() != null;

        int boxWidth = Math.min(sourceWidth, minConstraint(request.width(), request.maxWidth(), sourceWidth));
        int boxHeight = Math.min(sourceHeight, minConstraint(request.height(), request.maxHeight(), sourceHeight));

        if (!hasWidthConstraint && !hasHeightConstraint) {
            return new ImageTransformPlan(
                sourceWidth,
                sourceHeight,
                sourceWidth,
                sourceHeight,
                sourceWidth,
                sourceHeight,
                encoding.formatName(),
                encoding.extension(),
                encoding.mimeType(),
                request.quality(),
                request.fit()
            );
        }

        double scaleX = boxWidth / (double) sourceWidth;
        double scaleY = boxHeight / (double) sourceHeight;

        boolean useCover = request.fit() == ImageTransformFit.COVER && hasWidthConstraint && hasHeightConstraint;
        boolean useFill = request.fit() == ImageTransformFit.FILL && request.width() != null && request.height() != null;

        if (useFill) {
            return new ImageTransformPlan(
                sourceWidth,
                sourceHeight,
                boxWidth,
                boxHeight,
                boxWidth,
                boxHeight,
                encoding.formatName(),
                encoding.extension(),
                encoding.mimeType(),
                request.quality(),
                request.fit()
            );
        }

        if (useCover) {
            double scale = Math.max(scaleX, scaleY);
            int scaledWidth = scaleDimension(sourceWidth, scale);
            int scaledHeight = scaleDimension(sourceHeight, scale);
            return new ImageTransformPlan(
                sourceWidth,
                sourceHeight,
                scaledWidth,
                scaledHeight,
                boxWidth,
                boxHeight,
                Math.max((scaledWidth - boxWidth) / 2, 0),
                Math.max((scaledHeight - boxHeight) / 2, 0),
                encoding.formatName(),
                encoding.extension(),
                encoding.mimeType(),
                request.quality(),
                request.fit()
            );
        }

        double scale = Math.min(scaleX, scaleY);
        int scaledWidth = scaleDimension(sourceWidth, scale);
        int scaledHeight = scaleDimension(sourceHeight, scale);
        return new ImageTransformPlan(
            sourceWidth,
            sourceHeight,
            scaledWidth,
            scaledHeight,
            scaledWidth,
            scaledHeight,
            encoding.formatName(),
            encoding.extension(),
            encoding.mimeType(),
            request.quality(),
            request.fit()
        );
    }

    private BufferedImage resize(BufferedImage source, int width, int height) {
        int targetType = source.getType() == BufferedImage.TYPE_CUSTOM ? BufferedImage.TYPE_INT_ARGB : source.getType();
        BufferedImage output = new BufferedImage(width, height, targetType);
        var graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private BufferedImage crop(BufferedImage source, int x, int y, int width, int height) {
        BufferedImage output = new BufferedImage(width, height, source.getType());
        var graphics = output.createGraphics();
        try {
            graphics.drawImage(
                source,
                0,
                0,
                width,
                height,
                x,
                y,
                x + width,
                y + height,
                null
            );
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private byte[] encode(BufferedImage image, ImageTransformPlan plan) {
        var writers = ImageIO.getImageWritersByFormatName(plan.outputFormat());
        if (!writers.hasNext()) {
            throw new IllegalArgumentException("No writer available for format " + plan.outputFormat());
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        var writer = writers.next();
        try (var imageOutputStream = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutputStream);

            ImageWriteParam param = writer.getDefaultWriteParam();
            if (plan.quality() != null && param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(plan.quality() / 100f);
            }

            writer.write(null, new IIOImage(image, null, null), param);
            imageOutputStream.flush();
        } catch (IOException exception) {
            return sneakyThrow(exception);
        } finally {
            writer.dispose();
        }

        return output.toByteArray();
    }

    private static int scaleDimension(int value, double scale) {
        return Math.max(1, (int) Math.round(value * scale));
    }

    private int[] readImageDimensions(Path sourcePath) {
        try (var stream = ImageIO.createImageInputStream(sourcePath.toFile())) {
            if (stream == null) {
                return null;
            }

            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return null;
            }

            var reader = readers.next();
            try {
                reader.setInput(stream);
                return new int[] {reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private OutputEncoding resolveOutputEncoding(Path sourcePath, String sourceMimeType) {
        String extension = extension(sourcePath).toLowerCase(Locale.ROOT);
        List<OutputEncoding> candidates;
        if (JPEG_EXTENSIONS.contains(extension) || "image/jpeg".equals(sourceMimeType)) {
            candidates = List.of(new OutputEncoding("jpeg", "jpg", "image/jpeg"));
        } else if ("png".equals(extension) || "image/png".equals(sourceMimeType)) {
            candidates = List.of(new OutputEncoding("png", "png", "image/png"));
        } else if ("gif".equals(extension) || "image/gif".equals(sourceMimeType)) {
            candidates = List.of(
                new OutputEncoding("gif", "gif", "image/gif"),
                new OutputEncoding("png", "png", "image/png")
            );
        } else if ("bmp".equals(extension) || "image/bmp".equals(sourceMimeType)) {
            candidates = List.of(
                new OutputEncoding("bmp", "bmp", "image/bmp"),
                new OutputEncoding("png", "png", "image/png")
            );
        } else if (TIFF_EXTENSIONS.contains(extension) || "image/tiff".equals(sourceMimeType)) {
            candidates = List.of(
                new OutputEncoding("tiff", "tiff", "image/tiff"),
                new OutputEncoding("png", "png", "image/png")
            );
        } else if ("webp".equals(extension) || "image/webp".equals(sourceMimeType)) {
            candidates = List.of(
                new OutputEncoding("webp", "webp", "image/webp"),
                new OutputEncoding("png", "png", "image/png")
            );
        } else {
            candidates = List.of(
                new OutputEncoding("png", "png", "image/png"),
                new OutputEncoding("jpeg", "jpg", "image/jpeg")
            );
        }

        for (OutputEncoding candidate : candidates) {
            if (ImageIO.getImageWritersByFormatName(candidate.formatName()).hasNext()) {
                return candidate;
            }
        }
        throw new IllegalStateException("No writer available for image transform output: " + sourcePath);
    }

    private static int minConstraint(Integer first, Integer second, int fallback) {
        int result = fallback;
        if (first != null) {
            result = Math.min(result, first);
        }
        if (second != null) {
            result = Math.min(result, second);
        }
        return result;
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

    private record OutputEncoding(String formatName, String extension, String mimeType) {
    }
}
