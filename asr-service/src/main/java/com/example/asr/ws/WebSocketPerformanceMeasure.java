package com.example.asr.ws;

import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebSocket 性能指标测量工具类
 * 用于统一管理 time_to_first_chunk 和 time_per_output_chunk 等性能指标
 */
public class WebSocketPerformanceMeasure {
    private static final Logger log = LoggerFactory.getLogger(WebSocketPerformanceMeasure.class);
    
    private static final long UNINITIALIZED = -1L;
    
    private Long startTime;
    private Long firstChunkTime;
    private AtomicInteger chunkCounts;
    private AtomicLong totalInterval;
    private Long lastChunkTime;

    /**
     * 创建新的性能测量实例
     */
    public static WebSocketPerformanceMeasure create() {
        WebSocketPerformanceMeasure measure = new WebSocketPerformanceMeasure();
        measure.startTime = System.currentTimeMillis();
        measure.firstChunkTime = UNINITIALIZED;
        measure.chunkCounts = new AtomicInteger(0);
        measure.totalInterval = new AtomicLong(0);
        measure.lastChunkTime = UNINITIALIZED;
        return measure;
    }

    /**
     * 开始测量（如果尚未开始）
     */
    public void start() {
        if (startTime == null) {
            startTime = System.currentTimeMillis();
            firstChunkTime = UNINITIALIZED;
            chunkCounts = new AtomicInteger(0);
            totalInterval = new AtomicLong(0);
            lastChunkTime = UNINITIALIZED;
        }
    }

    /**
     * 记录一个 chunk 的到达
     * 自动计算 time_to_first_chunk 和更新间隔统计
     * 
     * @return 如果是第一个 chunk，返回 time_to_first_chunk（毫秒），否则返回 null
     */
    public Long recordChunk() {
        if (startTime == null) {
            log.warn("Performance measure not started, calling start() automatically");
            start();
        }
        
        long currentTime = System.currentTimeMillis();
        chunkCounts.incrementAndGet();
        
        // 记录第一个 chunk 的时间
        Long timeToFirstChunk = null;
        if (firstChunkTime == UNINITIALIZED) {
            timeToFirstChunk = currentTime - startTime;
            firstChunkTime = currentTime;
            log.debug("First chunk recorded, time_to_first_chunk: {}ms", timeToFirstChunk);
        }
        
        // 计算 chunk 间隔（从第二个 chunk 开始）
        if (lastChunkTime != UNINITIALIZED) {
            long interval = currentTime - lastChunkTime;
            totalInterval.addAndGet(interval);
        }
        lastChunkTime = currentTime;
        
        return timeToFirstChunk;
    }

    /**
     * 获取 time_to_first_chunk（毫秒）
     * 如果第一个 chunk 尚未到达，返回 null
     */
    public Long getTimeToFirstChunk() {
        if (firstChunkTime == UNINITIALIZED || startTime == null) {
            return null;
        }
        return firstChunkTime - startTime;
    }

    /**
     * 获取 time_to_last_chunk（毫秒）
     * 需要保证在 chunk 完全到达后调用
     * 如果第一个 chunk 尚未到达，返回 null
     */
    public Long getTimeToLastChunk() {
        if (lastChunkTime == UNINITIALIZED || startTime == null) {
            return null;
        }
        return lastChunkTime - startTime;
    }

    /**
     * 获取平均 chunk 间隔（毫秒）
     * 如果 chunk 数量少于 2，返回 null
     */
    public Long getAverageInterval() {
        int count = chunkCounts.get();
        if (count < 2 || totalInterval == null) {
            return null;
        }
        return totalInterval.get() / (count - 1);
    }

    /**
     * 获取 chunk 总数
     */
    public int getChunkCount() {
        return chunkCounts != null ? chunkCounts.get() : 0;
    }

    /**
     * 将性能指标应用到 OpenTelemetry Span
     * 
     * @param span 要应用指标的 Span
     * @param prefix 指标名称前缀（如 "asr", "tts", "websocket.write"）
     */
    public void applyToSpan(Span span, String prefix) {
        if (span == null) {
            return;
        }
        
        Long timeToFirstChunk = getTimeToFirstChunk();
        if (timeToFirstChunk != null) {
            String attributeName = prefix + ".time_to_first_chunk_ms";
            span.setAttribute(attributeName, timeToFirstChunk);
            log.debug("Applied {} to span: {}ms", attributeName, timeToFirstChunk);
        }
        
        Long avgInterval = getAverageInterval();
        if (avgInterval != null) {
            String attributeName = prefix + ".time_per_output_chunk_ms";
            span.setAttribute(attributeName, avgInterval);
            log.debug("Applied {} to span: {}ms", attributeName, avgInterval);
        }
        
        int chunkCount = getChunkCount();
        if (chunkCount > 0) {
            String attributeName = prefix + ".chunk_count";
            span.setAttribute(attributeName, chunkCount);
        }
    }
}
