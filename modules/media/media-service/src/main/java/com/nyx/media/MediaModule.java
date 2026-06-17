package com.nyx.media;

import com.nyx.browse.BrowseService;
import com.nyx.common.DatabaseResources;
import com.nyx.common.HealthMonitor;
import com.nyx.common.MetricsCollector;
import com.nyx.common.PathSecurity;
import com.nyx.common.VirtualPathResolver;
import com.nyx.common.storage.StorageBackend;
import com.nyx.config.ServerConfig;
import com.nyx.ffmpeg.MediaProber;
import com.nyx.ffmpeg.VideoPreviewGenerator;
import com.nyx.ffmpeg.VideoTrickplayGenerator;
import com.nyx.playback.contracts.AudioNegotiationService;
import com.nyx.playback.contracts.MediaPlaystateProjector;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;

public final class MediaModule {
    private MediaModule() {}

    public static MediaBindings createMediaBindings(
        ServerConfig config,
        PathSecurity pathSecurity,
        VirtualPathResolver virtualPathResolver,
        StorageBackend storageBackend,
        ExecutorService backgroundExecutor,
        ScheduledExecutorService cleanupScheduler,
        Semaphore ffmpegSemaphore,
        MediaProber probeService,
        VideoPreviewGenerator videoPreviewGenerator,
        VideoTrickplayGenerator videoTrickplayGenerator,
        MetricsCollector metricsCollector,
        HealthMonitor healthMonitor
    ) {
        DatabaseResources fileProbeCacheResources = FileProbeCache.createDatabase(
            config.getDatabase().getDir(),
            config.getDatabase()
        );
        FileProbeCache fileProbeCache = new FileProbeCache(fileProbeCacheResources.getJdbi());
        AudioMetadataService audioMetadataService = new AudioMetadataService(probeService, fileProbeCache);

        DatabaseResources mediaObjectResources = MediaObjectService.createDatabase(
            config.getDatabase().getDir(),
            config.getDatabase()
        );
        MediaObjectService mediaObjectService = new MediaObjectService(mediaObjectResources.getJdbi());
        MediaThumbnailService mediaThumbnailService = new MediaThumbnailService(mediaObjectResources.getJdbi());

        DatabaseResources libraryResources = LibraryService.createDatabase(
            config.getDatabase().getDir(),
            config.getDatabase()
        );
        LibraryService libraryService = new LibraryService(libraryResources.getJdbi());
        LibraryInterpretationService libraryInterpretationService = new LibraryInterpretationService(
            libraryResources.getJdbi(),
            libraryService,
            mediaObjectService
        );
        LibraryCatalogService libraryCatalogService = new LibraryCatalogService(
            libraryResources.getJdbi(),
            libraryService
        );
        LibraryExtensionCoordinator libraryExtensionCoordinator = new LibraryExtensionCoordinator(
            libraryService,
            libraryCatalogService
        );

        MediaThumbnailLifecycle mediaThumbnailLifecycle = new BestEffortMediaThumbnailLifecycle(mediaThumbnailService);
        UserMediaStateService userMediaStateService = new UserMediaStateService(mediaObjectResources.getJdbi());
        LibraryUserStateService libraryUserStateService = new LibraryUserStateService(
            libraryCatalogService,
            userMediaStateService
        );
        MediaPlaystateProjector mediaPlaystateProjector = userMediaStateService;
        MediaObjectResolver mediaObjectResolver = new MediaObjectResolver(
            mediaObjectService,
            probeService,
            audioMetadataService,
            mediaThumbnailService,
            mediaThumbnailLifecycle
        );
        LibraryScanService libraryScanService = new LibraryScanService(
            libraryResources.getJdbi(),
            libraryService,
            mediaObjectService,
            mediaObjectResolver,
            backgroundExecutor,
            libraryInterpretationService,
            libraryCatalogService,
            libraryExtensionCoordinator
        );
        LibraryAdminService libraryAdminService = new LibraryAdminService(
            libraryService,
            libraryScanService,
            libraryInterpretationService,
            libraryCatalogService,
            libraryExtensionCoordinator
        );

        List<Path> mediaRootPaths = config.getMediaRoots().stream().map(root -> root.getPath()).toList();
        MediaFileService mediaFileService = new MediaFileService(
            config.getThumbnails().getSizes(),
            mediaRootPaths,
            probeService,
            fileProbeCache,
            virtualPathResolver,
            audioMetadataService,
            mediaObjectResolver,
            backgroundExecutor
        );

        long thumbnailCacheSizeBytes = config.getThumbnails().getMaxCacheSizeMB() * 1024 * 1024;
        ThumbnailService thumbnailService = new ThumbnailService(
            Set.copyOf(config.getThumbnails().getSizes()),
            config.getFfmpeg().getPath(),
            config.getFfmpeg().getFfprobePath(),
            config.getThumbnails().getVideoOffsetPercent(),
            thumbnailCacheSizeBytes,
            ffmpegSemaphore,
            cleanupScheduler,
            config.getThumbnails().getCleanupIntervalMinutes(),
            metricsCollector,
            healthMonitor,
            storageBackend
        );
        ExifExtractor exifExtractor = new ExifExtractor();
        StrippedImageCache strippedImageCache = new StrippedImageCache(
            exifExtractor,
            thumbnailCacheSizeBytes,
            cleanupScheduler,
            config.getThumbnails().getCleanupIntervalMinutes(),
            storageBackend
        );
        ImageTransformService imageTransformService = new ImageTransformService(
            strippedImageCache,
            thumbnailCacheSizeBytes,
            cleanupScheduler,
            config.getThumbnails().getCleanupIntervalMinutes(),
            storageBackend
        );
        VideoPreviewService videoPreviewService = new VideoPreviewService(
            videoPreviewGenerator,
            thumbnailCacheSizeBytes,
            cleanupScheduler,
            config.getThumbnails().getCleanupIntervalMinutes(),
            healthMonitor,
            storageBackend
        );
        VideoTrickplayService videoTrickplayService = new VideoTrickplayService(
            videoTrickplayGenerator,
            thumbnailCacheSizeBytes,
            cleanupScheduler,
            config.getThumbnails().getCleanupIntervalMinutes(),
            healthMonitor,
            storageBackend
        );

        AudioTranscoder audioTranscoder = new AudioTranscoder(
            config.getFfmpeg().getPath(),
            ffmpegSemaphore,
            healthMonitor,
            config.getAudio()
        );
        AudioNegotiationService audioNegotiationService = new LocalAudioNegotiationService(audioTranscoder);
        BrowseService browseService = new BrowseService(
            virtualPathResolver,
            pathSecurity,
            config.getThumbnails().getSizes(),
            probeService,
            audioMetadataService,
            mediaObjectResolver,
            backgroundExecutor
        );

        DatabaseResources playlistResources = PlaylistService.createDatabase(
            config.getDatabase().getDir(),
            config.getDatabase()
        );
        PlaylistService playlistService = new PlaylistService(playlistResources.getJdbi(), pathSecurity);

        DatabaseResources chapterResources = ChapterService.createDatabase(
            config.getDatabase().getDir(),
            config.getDatabase()
        );
        ChapterService chapterService = new ChapterService(chapterResources.getJdbi(), pathSecurity);

        return new MediaBindings(
            ffmpegSemaphore,
            fileProbeCacheResources,
            fileProbeCache,
            audioMetadataService,
            mediaObjectResources,
            mediaObjectService,
            mediaThumbnailService,
            libraryResources,
            libraryService,
            libraryInterpretationService,
            libraryCatalogService,
            libraryExtensionCoordinator,
            libraryScanService,
            libraryAdminService,
            mediaThumbnailLifecycle,
            userMediaStateService,
            libraryUserStateService,
            mediaPlaystateProjector,
            mediaObjectResolver,
            mediaFileService,
            thumbnailService,
            exifExtractor,
            strippedImageCache,
            imageTransformService,
            videoPreviewService,
            videoTrickplayService,
            audioTranscoder,
            audioNegotiationService,
            browseService,
            playlistResources,
            playlistService,
            chapterResources,
            chapterService
        );
    }
}
