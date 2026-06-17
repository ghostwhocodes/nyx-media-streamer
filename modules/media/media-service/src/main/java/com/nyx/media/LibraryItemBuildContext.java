package com.nyx.media;

import com.nyx.media.contracts.Library;
import com.nyx.media.contracts.LibrarySourceRoot;
import com.nyx.media.contracts.MediaKind;
import com.nyx.media.contracts.MediaObject;
import java.nio.file.Path;

public record LibraryItemBuildContext(
    Library library,
    LibrarySourceRoot sourceRoot,
    String libraryEntryId,
    String objectId,
    MediaKind mediaKind,
    String primaryPath,
    MediaObject mediaObject
) {
    public String identityKey() {
        return "entry:" + libraryEntryId;
    }

    public Path sourceRootPath() {
        return sourceRoot == null ? null : Path.of(sourceRoot.path());
    }
}
