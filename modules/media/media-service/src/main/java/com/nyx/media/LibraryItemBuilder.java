package com.nyx.media;

import com.nyx.media.contracts.Library;
import java.util.List;

public interface LibraryItemBuilder {
    String getBuilderId();

    boolean supports(Library library);

    List<LibraryItemDescriptor> buildItems(LibraryItemBuildContext context);
}
