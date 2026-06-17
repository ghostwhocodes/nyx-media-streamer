package com.nyx.media;

import com.nyx.media.contracts.Library;
import com.nyx.media.contracts.LibraryItemType;
import com.nyx.media.contracts.LibraryType;
import com.nyx.media.contracts.MediaKind;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ShowLibraryItemBuilder implements LibraryItemBuilder {
    private static final Pattern SHOW_EPISODE_REGEX = Pattern.compile(
        "(?i)(.*?)(?:[ ._-]*)(?:s(\\d{1,2})e(\\d{1,2})|(\\d{1,2})x(\\d{1,2})).*"
    );
    private static final Pattern SEASON_DIRECTORY_REGEX = Pattern.compile(
        "(?i)(?:season[ ._-]?(\\d{1,2})|s(\\d{1,2}))"
    );

    @Override
    public String getBuilderId() {
        return "show-hierarchy";
    }

    @Override
    public boolean supports(Library library) {
        return library.type() == LibraryType.SHOW;
    }

    @Override
    public List<LibraryItemDescriptor> buildItems(LibraryItemBuildContext context) {
        ParsedShowEpisode parsed = parseShowEpisode(
            context.primaryPath(),
            context.sourceRootPath(),
            context.mediaObject().embeddedTitle()
        );
        if (parsed == null) {
            return List.of(unmatchedItem(context, "season or episode pattern missing"));
        }

        String showIdentityKey = "show:" + LibraryItemText.normalizedIdentity(parsed.showTitle());
        String seasonIdentityKey = showIdentityKey + ":season:" + parsed.seasonNumber();
        return List.of(
            new LibraryItemDescriptor(
                showIdentityKey,
                null,
                null,
                null,
                LibraryItemType.SHOW,
                parsed.showTitle(),
                MediaKind.VIDEO,
                parsed.showPath(),
                null,
                null,
                null,
                null
            ),
            new LibraryItemDescriptor(
                seasonIdentityKey,
                showIdentityKey,
                null,
                null,
                LibraryItemType.SEASON,
                "Season " + parsed.seasonNumber(),
                MediaKind.VIDEO,
                parsed.seasonPath(),
                null,
                parsed.seasonNumber(),
                null,
                null
            ),
            new LibraryItemDescriptor(
                context.identityKey(),
                seasonIdentityKey,
                context.libraryEntryId(),
                context.objectId(),
                LibraryItemType.EPISODE,
                parsed.episodeTitle(),
                context.mediaKind(),
                context.primaryPath(),
                null,
                parsed.seasonNumber(),
                parsed.episodeNumber(),
                null
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

    private static ParsedShowEpisode parseShowEpisode(
        String primaryPath,
        Path sourceRootPath,
        String embeddedTitle
    ) {
        Path path = Path.of(primaryPath);
        String fileStem = LibraryItemText.fileStem(path);
        if (fileStem == null) {
            return null;
        }

        Matcher seasonEpisodeMatch = SHOW_EPISODE_REGEX.matcher(fileStem);
        if (!seasonEpisodeMatch.matches()) {
            return null;
        }

        Integer seasonNumber = firstPresentInt(seasonEpisodeMatch, 2, 4);
        Integer episodeNumber = firstPresentInt(seasonEpisodeMatch, 3, 5);
        if (seasonNumber == null || episodeNumber == null) {
            return null;
        }

        Path parentDir = path.getParent();
        String parentName = LibraryItemText.fileName(parentDir);
        String grandparentName = parentDir == null ? null : LibraryItemText.fileName(parentDir.getParent());
        Integer folderSeasonNumber = parentName == null ? null : parseSeasonDirectoryNumber(parentName);
        Path normalizedSourceRoot = sourceRootPath == null ? null : sourceRootPath.normalize();
        Path normalizedParent = parentDir == null ? null : parentDir.normalize();
        Path normalizedGrandparent = parentDir == null || parentDir.getParent() == null
            ? null
            : parentDir.getParent().normalize();

        if (folderSeasonNumber != null && folderSeasonNumber.intValue() != seasonNumber.intValue()) {
            return null;
        }

        String showTitle;
        if (folderSeasonNumber != null && grandparentName != null && !grandparentName.isBlank()) {
            showTitle = LibraryItemText.cleanTitle(grandparentName);
        } else if (LibraryItemText.notBlank(seasonEpisodeMatch.group(1))) {
            showTitle = LibraryItemText.cleanTitle(seasonEpisodeMatch.group(1));
        } else if (parentDir != null && sourceRootPath != null && !parentDir.normalize().equals(sourceRootPath.normalize())) {
            showTitle = LibraryItemText.cleanTitle(parentName == null ? "" : parentName);
        } else {
            showTitle = "";
        }
        if (showTitle.isBlank()) {
            return null;
        }

        String derivedEpisodeTitle = LibraryItemText.notBlank(embeddedTitle)
            ? embeddedTitle.trim()
            : LibraryItemText.cleanTitle(removeMatchedRange(fileStem, seasonEpisodeMatch)).isBlank()
                ? "Episode " + episodeNumber
                : LibraryItemText.cleanTitle(removeMatchedRange(fileStem, seasonEpisodeMatch));

        String showPath;
        if (folderSeasonNumber != null && normalizedGrandparent != null) {
            showPath = normalizedGrandparent.toString();
        } else if (normalizedParent != null && !normalizedParent.equals(normalizedSourceRoot)) {
            showPath = normalizedParent.toString();
        } else {
            showPath = null;
        }

        String seasonPath;
        if (folderSeasonNumber != null && normalizedParent != null) {
            seasonPath = normalizedParent.toString();
        } else if (normalizedParent != null && !normalizedParent.equals(normalizedSourceRoot)) {
            seasonPath = normalizedParent.toString();
        } else {
            seasonPath = showPath;
        }

        return new ParsedShowEpisode(
            showTitle,
            showPath,
            seasonPath,
            seasonNumber,
            episodeNumber,
            derivedEpisodeTitle
        );
    }

    private static Integer parseSeasonDirectoryNumber(String raw) {
        Matcher match = SEASON_DIRECTORY_REGEX.matcher(raw.trim());
        if (!match.matches()) {
            return null;
        }
        for (int index = 1; index <= match.groupCount(); index++) {
            String group = match.group(index);
            if (LibraryItemText.notBlank(group)) {
                return parseInteger(group);
            }
        }
        return null;
    }

    private static Integer firstPresentInt(Matcher matcher, int... groups) {
        for (int group : groups) {
            Integer value = parseInteger(matcher.group(group));
            if (value != null) {
                return value;
            }
        }
        return null;
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

    private static String removeMatchedRange(String text, Matcher matcher) {
        return text.substring(0, matcher.start()) + text.substring(matcher.end());
    }

    private record ParsedShowEpisode(
        String showTitle,
        String showPath,
        String seasonPath,
        Integer seasonNumber,
        Integer episodeNumber,
        String episodeTitle
    ) {
    }
}
