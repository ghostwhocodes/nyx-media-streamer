package com.nyx.media;

import com.nyx.media.contracts.Library;
import com.nyx.media.contracts.LibraryItem;
import java.util.List;

public interface LibraryScanHook {
    String getHookId();

    void afterSuccessfulScan(Library library, LibraryScanRun scanRun, List<LibraryTrackedObject> trackedObjects, List<LibraryItem> items);
}
