package com.nyx.media.contracts;

import java.util.List;

public record UserMediaStateListing(
    List<UserMediaStateEntry> items,
    int total,
    int page,
    int limit
) {
    public UserMediaStateListing(int total, int page, int limit) {
        this(List.of(), total, page, limit);
    }

    public UserMediaStateListing {
        items = ContractCollections.immutableList(items);
    }
}
