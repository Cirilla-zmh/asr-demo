package com.example.asr.model;

public class StreamChunk {
    private byte[] data;
    private long timestampMs;

    public StreamChunk(byte[] data, long timestampMs) {
        this.data = data;
        this.timestampMs = timestampMs;
    }

    public byte[] getData() {
        return data;
    }

    public long getTimestampMs() {
        return timestampMs;
    }
}


