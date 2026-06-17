package com.nyx.media;

import com.nyx.media.contracts.Library;
import com.nyx.media.contracts.LibraryItemType;
import com.nyx.media.contracts.LibraryType;
import com.nyx.media.contracts.MediaKind;
import com.nyx.media.contracts.MediaObject;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MusicLibraryItemBuilder implements LibraryItemBuilder {
    private static final Pattern TRACK_NUMBER_REGEX = Pattern.compile("^(\\d{1,2})\\b");
    private static final Pattern TRACK_NUMBER_PREFIX_REGEX = Pattern.compile("^\\d{1,2}\\s*[-._ ]+\\s*");

    @Override
    public String getBuilderId() {
        return "music-album-track";
    }

    @Override
    public boolean supports(Library library) {
        return library.type() == LibraryType.MUSIC;
    }

    @Override
    public List<LibraryItemDescriptor> buildItems(LibraryItemBuildContext context) {
        ParsedMusicTrack parsed = parseMusicTrack(
            context.primaryPath(),
            context.sourceRootPath(),
            context.mediaObject()
        );
        if (parsed == null) {
            return List.of(unmatchedItem(context, "album inference unavailable"));
        }

        String albumIdentityKey = "album:" + LibraryItemText.normalizedIdentity(parsed.albumIdentityKey());
        return List.of(
            new LibraryItemDescriptor(
                albumIdentityKey,
                null,
                null,
                null,
                LibraryItemType.ALBUM,
                parsed.albumTitle(),
                MediaKind.AUDIO,
                parsed.albumPath(),
                null,
                null,
                null,
                null
            ),
            new LibraryItemDescriptor(
                context.identityKey(),
                albumIdentityKey,
                context.libraryEntryId(),
                context.objectId(),
                LibraryItemType.TRACK,
                parsed.trackTitle(),
                context.mediaKind(),
                context.primaryPath(),
                null,
                null,
                null,
                parsed.trackNumber()
            )
        );
    }

    private static LibraryItemDescriptor unmatchedItem(LibraryItemBuildContext context, String reason) {
        return new LibraryItemDescriptor(
            context.identityKey(),
            null,
            context.libraryEntryId(),
            context.objectId(),
            LibraryItemType.UNMATCHED,
            LibraryItemText.nonBlank(
                context.mediaObject().embeddedTitle(),
                LibraryItemText.titleFromPath(context.primaryPath())
            ),
            context.mediaKind(),
            context.primaryPath(),
            reason,
            null,
            null,
            null
        );
    }

    private static ParsedMusicTrack parseMusicTrack(
        String primaryPath,
        Path sourceRootPath,
        MediaObject mediaObject
    ) {
        Path path = Path.of(primaryPath);
        String fileStem = LibraryItemText.fileStem(path);
        if (fileStem == null) {
            return null;
        }

        Path parentDir = path.getParent();
        String albumTitle = normalizedAlbumTitle(mediaObject, parentDir, sourceRootPath);
        if (albumTitle == null) {
            return null;
        }

        Matcher trackNumberMatch = TRACK_NUMBER_REGEX.matcher(fileStem);
        Integer trackNumber = trackNumberMatch.find() ? parseInteger(trackNumberMatch.group(1)) : null;

        String trackTitle;
        if (LibraryItemText.notBlank(mediaObject.embeddedTitle())) {
            trackTitle = mediaObject.embeddedTitle().trim();
        } else {
            String cleanedStem = TRACK_NUMBER_PREFIX_REGEX.matcher(fileStem).replaceFirst("");
            String cleanedTitle = LibraryItemText.cleanTitle(cleanedStem);
            trackTitle = cleanedTitle.isBlank() ? LibraryItemText.titleFromPath(primaryPath) : cleanedTitle;
        }

        String albumIdentityKey = albumTitle + ":" + (parentDir == null ? "" : parentDir.normalize());
        return new ParsedMusicTrack(
            albumIdentityKey,
            albumTitle,
            parentDir == null ? null : parentDir.normalize().toString(),
            trackTitle,
            trackNumber
        );
    }

    private static String normalizedAlbumTitle(MediaObject mediaObject, Path parentDir, Path sourceRootPath) {
        if (LibraryItemText.notBlank(mediaObject.embeddedAlbum())) {
            return mediaObject.embeddedAlbum().trim();
        }
        if (parentDir == null || sourceRootPath == null) {
            return null;
        }
        String fallback = parentDir.normalize().equals(sourceRootPath.normalize())
            ? LibraryItemText.cleanTitle(LibraryItemText.fileName(sourceRootPath) == null ? "" : LibraryItemText.fileName(sourceRootPath))
            : LibraryItemText.cleanTitle(LibraryItemText.fileName(parentDir) == null ? "" : LibraryItemText.fileName(parentDir));
        return fallback.isBlank() ? null : fallback;
    }

    private static Integer parseInteger(String value) {
        if (!LibraryItemText.notBlank(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record ParsedMusicTrack(
        String albumIdentityKey,
        String albumTitle,
        String albumPath,
        String trackTitle,
        Integer trackNumber
    ) {
    }
}
