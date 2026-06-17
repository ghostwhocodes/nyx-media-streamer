package com.nyx.media;

import com.nyx.media.contracts.Library;
import com.nyx.media.contracts.LibraryItemType;
import com.nyx.media.contracts.LibraryType;
import java.util.List;

final class TypedLeafLibraryItemBuilder implements LibraryItemBuilder {
    @Override
    public String getBuilderId() {
        return "typed-leaf";
    }

    @Override
    public boolean supports(Library library) {
        return library.type() == LibraryType.MOVIE
            || library.type() == LibraryType.GENERIC_VIDEO
            || library.type() == LibraryType.PHOTO;
    }

    @Override
    public List<LibraryItemDescriptor> buildItems(LibraryItemBuildContext context) {
        LibraryItemType type = switch (context.library().type()) {
            case MOVIE -> LibraryItemType.MOVIE;
            case GENERIC_VIDEO -> LibraryItemType.VIDEO;
            case PHOTO -> LibraryItemType.PHOTO;
            case SHOW, MUSIC -> throw new IllegalStateException("Unsupported library type: " + context.library().type());
        };
        String title = context.library().type() == LibraryType.PHOTO
            ? LibraryItemText.titleFromPath(context.primaryPath())
            : LibraryItemText.nonBlank(
                context.mediaObject().embeddedTitle(),
                LibraryItemText.titleFromPath(context.primaryPath())
            );
        return List.of(new LibraryItemDescriptor(
            context.identityKey(),
            null,
            context.libraryEntryId(),
            context.objectId(),
            type,
            title,
            context.mediaKind(),
            context.primaryPath(),
            null,
            null,
            null,
            null
        ));
    }
}
