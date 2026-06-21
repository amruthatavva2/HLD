package com.typeahead.service.batch;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchWriteBufferTest {

    @Test
    void aggregatesRepeatedQueriesIntoOneDelta() {
        BatchWriteBuffer buffer = new BatchWriteBuffer();
        for (int i = 0; i < 10; i++) buffer.add("iphone");
        buffer.add("ipad");
        buffer.add("ipad");

        assertEquals(2, buffer.distinctSize(), "two distinct queries buffered");

        Map<String, Long> drained = buffer.drain();
        assertEquals(10L, drained.get("iphone"), "10 searches aggregate to +10");
        assertEquals(2L, drained.get("ipad"));
    }

    @Test
    void drainResetsTheBuffer() {
        BatchWriteBuffer buffer = new BatchWriteBuffer();
        buffer.add("x");
        buffer.drain();
        assertEquals(0, buffer.distinctSize());
        assertTrue(buffer.drain().isEmpty());
    }
}
