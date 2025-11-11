package com.example.asr.service;

import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

@Slf4j
@Service
public class TtsSynthesisService {
    @Value("${dashscope.api-key}")
    private String apiKey;

    @Value("${dashscope.tts.model}")
    private String model;

    @Value("${dashscope.tts.voice}")
    private String voice;

    @Value("${dashscope.tts.format}")
    private String format;

    public void synthesizeStream(String sessionId, String text, Consumer<byte[]> onAudioChunk) {
        if (text == null || text.trim().isEmpty()) {
            log.warn("Empty text for TTS synthesis, session: {}", sessionId);
            return;
        }
        
        try {
            log.info("Starting TTS synthesis for session: {}, text length: {}", sessionId, text.length());
            
            SpeechSynthesisParam param = SpeechSynthesisParam.builder()
                    .model(model)
                    .voice(voice)
                    .apiKey(apiKey)
                    .format(SpeechSynthesisAudioFormat.MP3_22050HZ_MONO_256KBPS)
                    .build();
            
            SpeechSynthesizer synthesizer = new SpeechSynthesizer(param, null);
            
            // 使用Flowable方式调用，直接订阅Flowable来获取结果
            synthesizer.callAsFlowable(text)
                .blockingSubscribe(
                    result -> {
                        if (result != null && result.getAudioFrame() != null) {
                            ByteBuffer audioFrame = result.getAudioFrame();
                            // 确保读取所有数据
                            int remaining = audioFrame.remaining();
                            if (remaining > 0) {
                                byte[] audioBytes = new byte[remaining];
                                audioFrame.get(audioBytes);
                                
                                log.info("TTS audio chunk generated: {} bytes for session: {}", audioBytes.length, sessionId);
                                
                                if (onAudioChunk != null) {
                                    // 立即发送，不延迟
                                    onAudioChunk.accept(audioBytes);
                                    log.debug("TTS audio chunk sent to callback for session: {} bytes", audioBytes.length);
                                } else {
                                    log.warn("TTS onAudioChunk callback is null for session: {}", sessionId);
                                }
                            } else {
                                log.debug("TTS audio frame has no remaining data for session: {}", sessionId);
                            }
                        } else {
                            log.debug("TTS result has no audio frame for session: {}", sessionId);
                        }
                    },
                    error -> {
                        String errorMsg = error != null ? error.getMessage() : "Unknown error";
                        if (errorMsg != null && errorMsg.contains("AccessDenied")) {
                            log.error("TTS AccessDenied for session: {} - Check API key permissions and model: {}", sessionId, model);
                        } else if (errorMsg != null && errorMsg.contains("RateQuota")) {
                            log.error("TTS rate limit exceeded for session: {} - Please wait before retrying", sessionId);
                        } else {
                            log.error("TTS synthesis error for session: {}", sessionId, error);
                        }
                    },
                    () -> {
                        log.info("TTS synthesis completed for session: {}", sessionId);
                    }
                );
            
        } catch (ApiException | NoApiKeyException e) {
            log.error("Failed to synthesize speech for session: {}, text: {}", sessionId, 
                text.substring(0, Math.min(50, text.length())), e);
        } catch (Exception e) {
            log.error("Unexpected error during TTS synthesis for session: {}", sessionId, e);
        }
    }
}


