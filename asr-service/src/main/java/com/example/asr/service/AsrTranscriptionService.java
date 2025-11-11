package com.example.asr.service;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult;
import com.alibaba.dashscope.common.ResultCallback;
import com.example.asr.ws.WebSocketPerformanceMeasure;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

@Slf4j
@Service
public class AsrTranscriptionService {
    @Value("${dashscope.api-key}")
    private String apiKey;

    @Value("${dashscope.asr.model}")
    private String model;

    @Value("${dashscope.asr.sample-rate}")
    private int sampleRate;

    private final Map<String, Recognition> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> transcripts = new ConcurrentHashMap<>(); // 只保存最后一次的完整结果
    private final Map<String, CountDownLatch> completionLatches = new ConcurrentHashMap<>();
    private final Map<String, WebSocketPerformanceMeasure> performanceMeasureMap = new ConcurrentHashMap<>();

    public void startStream(String sessionId) {
        try {
            log.info("Starting ASR stream for session: {}", sessionId);
            Recognition recognition = new Recognition();
            CountDownLatch latch = new CountDownLatch(1);
            
            sessions.put(sessionId, recognition);
            transcripts.put(sessionId, ""); // 初始化为空字符串
            completionLatches.put(sessionId, latch);
            
            // 创建识别参数
            RecognitionParam param = RecognitionParam.builder()
                    .model(model)
                    .apiKey(apiKey)
                    .sampleRate(sampleRate)
                    .format("pcm")
                    .build();
            
            // 启动异步识别回调
            ResultCallback<RecognitionResult> callback = new ResultCallback<RecognitionResult>() {
                @Override
                public void onEvent(RecognitionResult result) {
                    if (result != null && result.getSentence() != null) {
                        String text = result.getSentence().getText();
                        if (text != null && !text.isEmpty()) {
                            // 直接替换为最新的完整结果，而不是累积追加
                            // 因为ASR返回的可能是累积的完整文本
                            transcripts.put(sessionId, text);
                            log.debug("ASR result for {} (replaced): {}", sessionId, text);
                        }
                        WebSocketPerformanceMeasure measure = performanceMeasureMap.getOrDefault(sessionId, null);
                        if (measure != null) {
                            measure.recordChunk();
                        }
                    }
                }

                @Override
                public void onComplete() {
                    log.info("ASR completed for session: {}", sessionId);
                    latch.countDown();
                }

                @Override
                public void onError(Exception e) {
                    log.error("ASR error for session: {}", sessionId, e);
                    latch.countDown();
                }
            };
            
            recognition.call(param, callback);
            log.info("ASR stream initialized for session: {}", sessionId);
            
        } catch (Exception e) {
            log.error("Failed to start ASR stream for session: {}", sessionId, e);
            throw new RuntimeException("ASR initialization failed", e);
        }
    }

    public void registerMeasure(String sessionId, WebSocketPerformanceMeasure measure) {
        performanceMeasureMap.put(sessionId, measure);
    }

    public void appendAudio(String sessionId, byte[] audioBytes) {
        Recognition recognition = sessions.get(sessionId);
        if (recognition == null) {
            log.warn("No ASR session found for: {}, creating new session", sessionId);
            try {
                startStream(sessionId);
                recognition = sessions.get(sessionId);
                if (recognition == null) {
                    log.error("Failed to create ASR session for: {}", sessionId);
                    return;
                }
            } catch (Exception e) {
                log.error("Failed to create ASR session for: {}", sessionId, e);
                return;
            }
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(audioBytes);
            recognition.sendAudioFrame(buffer);
            log.debug("Appended {} bytes to ASR for session: {}", audioBytes.length, sessionId);
        } catch (Exception e) {
            log.error("Failed to append audio for session: {}", sessionId, e);
        }
    }

    public String endStream(String sessionId) {
        Recognition recognition = sessions.get(sessionId);
        CountDownLatch latch = completionLatches.get(sessionId);
        
        if (recognition == null || latch == null) {
            log.warn("No ASR session found for: {}", sessionId);
            return "";
        }

        try {
            log.info("Ending ASR stream for session: {}", sessionId);
            
            // 结束音频流
            recognition.stop();
            
            // 等待识别完成（最多等待 30 秒）
            latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
            
            // 获取最后一次的完整识别结果
            String finalText = transcripts.get(sessionId);
            if (finalText == null) {
                finalText = "";
            }
            log.info("ASR final result for session {}: {}", sessionId, finalText);
            
            // 清理
            sessions.remove(sessionId);
            transcripts.remove(sessionId);
            completionLatches.remove(sessionId);
            performanceMeasureMap.remove(sessionId);
            
            return finalText;
            
        } catch (Exception e) {
            log.error("Failed to end ASR stream for session: {}", sessionId, e);
            // 返回最后一次保存的结果
            String lastResult = transcripts.get(sessionId);
            return lastResult != null ? lastResult : "";
        }
    }
}


