package com.nyx.media;

import com.nyx.browse.BrowseService;
import com.nyx.common.DatabaseResources;
import com.nyx.playback.contracts.AudioNegotiationService;
import com.nyx.playback.contracts.MediaPlaystateProjector;
import java.util.concurrent.Semaphore;

public record MediaBindings(
    Semaphore ffmpegSemaphore,
    DatabaseResources fileProbeCacheResources,
    FileProbeCache fileProbeCache,
    AudioMetadataService audioMetadataService,
    DatabaseResources mediaObjectResources,
    MediaObjectService mediaObjectService,
    MediaThumbnailService mediaThumbnailService,
    DatabaseResources libraryResources,
    LibraryService libraryService,
    LibraryInterpretationService libraryInterpretationService,
    LibraryCatalogService libraryCatalogService,
    LibraryExtensionCoordinator libraryExtensionCoordinator,
    LibraryScanService libraryScanService,
    LibraryAdminService libraryAdminService,
    MediaThumbnailLifecycle mediaThumbnailLifecycle,
    UserMediaStateService userMediaStateService,
    LibraryUserStateService libraryUserStateService,
    MediaPlaystateProjector mediaPlaystateProjector,
    MediaObjectResolver mediaObjectResolver,
    MediaFileService mediaFileService,
    ThumbnailService thumbnailService,
    ExifExtractor exifExtractor,
    StrippedImageCache strippedImageCache,
    ImageTransformService imageTransformService,
    VideoPreviewService videoPreviewService,
    VideoTrickplayService videoTrickplayService,
    AudioTranscoder audioTranscoder,
    AudioNegotiationService audioNegotiationService,
    BrowseService browseService,
    DatabaseResources playlistResources,
    PlaylistService playlistService,
    DatabaseResources chapterResources,
    ChapterService chapterService
) {
    public Semaphore getFfmpegSemaphore() { return ffmpegSemaphore; }

    public DatabaseResources getFileProbeCacheResources() { return fileProbeCacheResources; }

    public FileProbeCache getFileProbeCache() { return fileProbeCache; }

    public AudioMetadataService getAudioMetadataService() { return audioMetadataService; }

    public DatabaseResources getMediaObjectResources() { return mediaObjectResources; }

    public MediaObjectService getMediaObjectService() { return mediaObjectService; }

    public MediaThumbnailService getMediaThumbnailService() { return mediaThumbnailService; }

    public DatabaseResources getLibraryResources() { return libraryResources; }

    public LibraryService getLibraryService() { return libraryService; }

    public LibraryInterpretationService getLibraryInterpretationService() { return libraryInterpretationService; }

    public LibraryCatalogService getLibraryCatalogService() { return libraryCatalogService; }

    public LibraryExtensionCoordinator getLibraryExtensionCoordinator() { return libraryExtensionCoordinator; }

    public LibraryScanService getLibraryScanService() { return libraryScanService; }

    public LibraryAdminService getLibraryAdminService() { return libraryAdminService; }

    public MediaThumbnailLifecycle getMediaThumbnailLifecycle() { return mediaThumbnailLifecycle; }

    public UserMediaStateService getUserMediaStateService() { return userMediaStateService; }

    public LibraryUserStateService getLibraryUserStateService() { return libraryUserStateService; }

    public MediaPlaystateProjector getMediaPlaystateProjector() { return mediaPlaystateProjector; }

    public MediaObjectResolver getMediaObjectResolver() { return mediaObjectResolver; }

    public MediaFileService getMediaFileService() { return mediaFileService; }

    public ThumbnailService getThumbnailService() { return thumbnailService; }

    public ExifExtractor getExifExtractor() { return exifExtractor; }

    public StrippedImageCache getStrippedImageCache() { return strippedImageCache; }

    public ImageTransformService getImageTransformService() { return imageTransformService; }

    public VideoPreviewService getVideoPreviewService() { return videoPreviewService; }

    public VideoTrickplayService getVideoTrickplayService() { return videoTrickplayService; }

    public AudioTranscoder getAudioTranscoder() { return audioTranscoder; }

    public AudioNegotiationService getAudioNegotiationService() { return audioNegotiationService; }

    public BrowseService getBrowseService() { return browseService; }

    public DatabaseResources getPlaylistResources() { return playlistResources; }

    public PlaylistService getPlaylistService() { return playlistService; }

    public DatabaseResources getChapterResources() { return chapterResources; }

    public ChapterService getChapterService() { return chapterService; }
}
