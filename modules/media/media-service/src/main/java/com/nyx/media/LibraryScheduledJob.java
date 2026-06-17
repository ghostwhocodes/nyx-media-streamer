package com.nyx.media;

import com.nyx.media.contracts.Library;
import com.nyx.media.contracts.LibraryItem;
import java.util.List;

public interface LibraryScheduledJob {
    String getJobId();

    void run(Library library, List<LibraryItem> items, LibraryJobTrigger trigger);
}
