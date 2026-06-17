package com.nyx.media;

import com.nyx.media.contracts.Library;
import com.nyx.media.contracts.LibraryItem;
import java.util.List;

public interface LibraryCollectionBuilder {
    String getBuilderId();

    void rebuildCollections(Library library, List<LibraryItem> items, LibraryCatalogService catalogService);
}
