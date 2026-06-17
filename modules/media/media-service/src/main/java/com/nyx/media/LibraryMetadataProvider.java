package com.nyx.media;

import com.nyx.media.contracts.Library;
import com.nyx.media.contracts.LibraryItem;
import java.util.List;

public interface LibraryMetadataProvider {
    String getProviderId();

    void refresh(Library library, List<LibraryItem> items, LibraryCatalogService catalogService);
}
