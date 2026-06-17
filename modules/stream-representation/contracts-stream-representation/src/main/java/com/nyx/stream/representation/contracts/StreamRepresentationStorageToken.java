package com.nyx.stream.representation.contracts;

public record StreamRepresentationStorageToken(String value) {
    public StreamRepresentationStorageToken {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("storage token value is required");
        }
    }
}
