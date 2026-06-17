package com.nyx.transcode;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SeekRequestTest {
    @Test
    void seekRequestRecordHoldsTimestamp() {
        SeekRequest request = new SeekRequest(45.5);
        assertEquals(45.5, request.timestamp_secs());
    }
}
