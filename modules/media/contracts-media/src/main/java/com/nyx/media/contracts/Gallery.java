package com.nyx.media.contracts;

import java.util.List;

public record Gallery(
    List<MediaItem.Image> images,
    int total,
    int page,
    int limit
) {
    public Gallery {
        images = ContractCollections.immutableList(images);
    }
}
