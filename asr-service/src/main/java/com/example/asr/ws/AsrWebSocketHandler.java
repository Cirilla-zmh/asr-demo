package com.example.asr.ws;

import com.example.asr.service.AsrTranscriptionService;
import com.example.asr.service.LlmService;
import com.example.asr.service.TtsSynthesisService;
import com.example.asr.service.ToolInvocationService;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class AsrWebSocketHandler extends BinaryWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(AsrWebSocketHandler.class);

    // OpenTelemetry Tracer
    private static final Tracer tracer;
    private static final OpenTelemetry openTelemetry;
    
    static {
        openTelemetry = GlobalOpenTelemetry.get();
        tracer = openTelemetry.getTracer("asr-service", "1.0.0");
        log.info("OpenTelemetry Tracer 初始化完成");
    }

    @Autowired
    private AsrTranscriptionService asrService;

    @Autowired
    private LlmService llmService;

    @Autowired
    private TtsSynthesisService ttsService;

    @Autowired
    private ToolInvocationService toolService;

    // 会话状态管理
    private final Map<String, ByteArrayOutputStream> audioBuffers = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAudioTimestamp = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> processingFlags = new ConcurrentHashMap<>();

    // *************** Tracing ***************
    // Tracing 相关：存储每个会话的 Span
    private final Map<String, Span> connectionSpans = new ConcurrentHashMap<>();
    
    // ASR Span 相关
    private final Map<String, Span> asrSpans = new ConcurrentHashMap<>();
    private final Map<String, WebSocketPerformanceMeasure> asrMeasures = new ConcurrentHashMap<>();

    // TTS Span 相关
    private final Map<String, Span> ttsSpans = new ConcurrentHashMap<>();
    private final Map<String, WebSocketPerformanceMeasure> ttsMeasures = new ConcurrentHashMap<>();
    
    // 前端写入 Span 相关
    private final Map<String, Span> writeSpans = new ConcurrentHashMap<>();
    private final Map<String, WebSocketPerformanceMeasure> writeMeasures = new ConcurrentHashMap<>();
    // *************** Tracing ***************

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = session.getId();
        log.info("WS connected: {}", sessionId);

        // *************** Tracing ***************
        // 1. 创建 WebSocket 连接 Span（root span）
        Span connectionSpan = tracer.spanBuilder("Server WebSocket Connection")
            .setAttribute("gen_ai.span.kind", "WEBSOCKET")
            .setAttribute("websocket.session.id", sessionId)
            .setAttribute("websocket.endpoint", "/ws/asr")
            .setAttribute("websocket.connection.type", "server")
            .startSpan();

        try (Scope scope = connectionSpan.makeCurrent()) {
            connectionSpans.put(sessionId, connectionSpan);
        // *************** Tracing ***************

            log.info("Created connection span for session: {}", sessionId);
        
            // 初始化会话状态
            audioBuffers.put(sessionId, new ByteArrayOutputStream());
            lastAudioTimestamp.put(sessionId, System.currentTimeMillis());
            processingFlags.put(sessionId, new AtomicBoolean(false));
            
            // 初始化 ASR 流
            log.info("Initializing ASR stream for session: {}", sessionId);

            asrService.startStream(sessionId);
            log.info("ASR stream initialized successfully for session: {}", sessionId);
            
            session.sendMessage(new TextMessage("{\"type\":\"connected\",\"sessionId\":\"" + sessionId + "\"}"));
        } catch (Exception e) {
            log.error("Failed to initialize session {}: {}", sessionId, e.getMessage(), e);
            session.sendMessage(new TextMessage("{\"type\":\"error\",\"message\":\"初始化失败: " + e.getMessage() + "\"}"));

            // *************** Tracing ***************
            connectionSpan.recordException(e);
            connectionSpan.end();
            // *************** Tracing ***************
            throw e;
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        String sessionId = session.getId();
        ByteBuffer payload = message.getPayload();
        byte[] audioData = new byte[payload.remaining()];
        payload.get(audioData);
        
        log.debug("Received {} audio bytes for session: {}", audioData.length, sessionId);

        // *************** Tracing ***************
        // 确保在 Connection Span 的上下文中执行
        Span connectionSpan = connectionSpans.get(sessionId);
        if (connectionSpan == null) {
            log.warn("No connection span found for session: {}", sessionId);
            return;
        }
        
        try (Scope scope = connectionSpan.makeCurrent()) {
            // 2. 如果是第一个音频消息，创建 ASR Span
            if (!asrSpans.containsKey(sessionId)) {
                Span asrSpan = tracer.spanBuilder("asr.transcription")
                    .setParent(Context.current())
                    .setAttribute("gen_ai.span.kind", "WEBSOCKET")
                    .setAttribute("websocket.session.id", sessionId)
                    .setAttribute("asr.format", "pcm")
                    .startSpan();
                
                asrSpans.put(sessionId, asrSpan);
                // 创建指标并注册到 asr 服务中
                WebSocketPerformanceMeasure measure = WebSocketPerformanceMeasure.create();
                measure.start();
                asrService.registerMeasure(sessionId, measure);
                
                log.info("Created ASR span for session: {}", sessionId);
            }
        // *************** Tracing ***************
        
            // 更新最后接收时间
            lastAudioTimestamp.put(sessionId, System.currentTimeMillis());
        
            // 累积音频数据
            ByteArrayOutputStream buffer = audioBuffers.get(sessionId);
            if (buffer != null) {
                buffer.write(audioData);
            }

            // 追加到 ASR 流
            asrService.appendAudio(sessionId, audioData);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String sessionId = session.getId();
        String payload = message.getPayload();
        
        log.info("Received text message for session {}: {}", sessionId, payload);
        
        // 处理控制消息
        if ("END".equals(payload)) {
            processAudioComplete(session);
        }
    }

    private void processAudioComplete(WebSocketSession session) {
        String sessionId = session.getId();
        AtomicBoolean processing = processingFlags.get(sessionId);
        
        if (processing != null && processing.compareAndSet(false, true)) {
            new Thread(() -> {
                // *************** Tracing ***************
                Span connectionSpan = connectionSpans.get(sessionId);
                if (connectionSpan == null) {
                    log.warn("No connection span found for session: {}", sessionId);
                    return;
                }
                
                try (Scope scope = connectionSpan.makeCurrent()) {
                // *************** Tracing ***************
                    log.info("Processing audio completion for session: {}", sessionId);
                    
                    // 结束 ASR 流并获取转录文本
                    String transcript = asrService.endStream(sessionId);

                    // *************** Tracing ***************
                    // 结束 ASR Span
                    Span asrSpan = asrSpans.remove(sessionId);
                    WebSocketPerformanceMeasure asrMeasure = asrMeasures.remove(sessionId);
                    if (asrSpan != null) {
                        if (asrMeasure != null) {
                            asrMeasure.applyToSpan(asrSpan, "asr");
                        }
                        asrSpan.setAttribute("asr.transcript.length", transcript != null ? transcript.length() : 0);
                        asrSpan.end();
                        log.info("Ended ASR span for session: {}", sessionId);
                    }
                    // *************** Tracing ***************
                    
                    if (transcript == null || transcript.trim().isEmpty()) {
                        log.warn("No transcript received for session: {}", sessionId);
                        session.sendMessage(new TextMessage("{\"type\":\"error\",\"message\":\"未能识别语音内容\"}"));
                        return;
                    }
                    
                    log.info("Transcript for session {}: {}", sessionId, transcript);
                    session.sendMessage(new TextMessage("{\"type\":\"transcript\",\"text\":\"" + transcript + "\"}"));
                    
                    // 意图识别
                    String intent = llmService.classifyIntent(transcript);
                    log.info("Intent for session {}: {}", sessionId, intent);
                    session.sendMessage(new TextMessage("{\"type\":\"intent\",\"value\":\"" + intent + "\"}"));
                    
                    // 根据意图处理
                    if ("order".equals(intent)) {
                        handleOrderIntent(session, transcript);
                    } else {
                        handleChitchatIntent(session, transcript);
                    }
                    
                } catch (Exception e) {
                    log.error("Error processing audio for session: {}", sessionId, e);
                    try {
                        session.sendMessage(new TextMessage("{\"type\":\"error\",\"message\":\"处理出错: " + e.getMessage() + "\"}"));
                    } catch (Exception ex) {
                        log.error("Failed to send error message", ex);
                    }
                } finally {
                    processing.set(false);
                }
            }).start();
        }
    }

    // 文本缓冲区，用于按句子聚合TTS请求
    private final Map<String, StringBuilder> textBuffers = new ConcurrentHashMap<>();
    
    // TTS请求队列，按sessionId分组，每2秒处理一个句子
    private final Map<String, Queue<String>> ttsQueues = new ConcurrentHashMap<>();
    private final ScheduledExecutorService ttsScheduler = Executors.newScheduledThreadPool(2);
    private final Map<String, ScheduledFuture<?>> ttsScheduledTasks = new ConcurrentHashMap<>();
    private static final long TTS_INTERVAL_MS = 2000; // 2秒间隔

    private void handleOrderIntent(WebSocketSession session, String transcript) throws Exception {
        String sessionId = session.getId();
        log.info("Handling order intent for session: {}", sessionId);
        
        // 简单提取商品和数量（真实场景应使用 LLM 工具调用）
        // 这里模拟提取
        String item = extractItem(transcript);
        int quantity = extractQuantity(transcript);
        
        // 调用 MCP 下单工具
        String orderId = toolService.placeOrder(item, quantity);
        log.info("Order placed: {}", orderId);
        
        // 初始化文本缓冲区
        textBuffers.put(sessionId, new StringBuilder());
        
        // 通过 LLM 生成更自然的回复并 TTS
        llmService.streamGenerate(sessionId, 
            "用户说：" + transcript + "\n系统已下单成功，订单号：" + orderId + "。请生成一个友好的确认回复。",
            textChunk -> {
                accumulateAndSynthesize(sessionId, textChunk, session);
            });
        
        // 处理剩余的文本
        StringBuilder remainingBuffer = textBuffers.remove(sessionId);
        if (remainingBuffer != null && remainingBuffer.length() > 0) {
            String remainingText = remainingBuffer.toString().trim();
            if (!remainingText.isEmpty()) {
                synthesizeSentence(sessionId, remainingText, session);
            }
        }
        
        session.sendMessage(new TextMessage("{\"type\":\"complete\"}"));
    }

    private void handleChitchatIntent(WebSocketSession session, String transcript) throws Exception {
        String sessionId = session.getId();
        log.info("Handling chitchat intent for session: {}", sessionId);
        
        // 初始化文本缓冲区
        textBuffers.put(sessionId, new StringBuilder());
        
        // LLM 流式生成闲聊内容
        llmService.streamGenerate(sessionId, transcript, textChunk -> {
            accumulateAndSynthesize(sessionId, textChunk, session);
        });
        
        // 处理剩余的文本
        StringBuilder remainingBuffer = textBuffers.remove(sessionId);
        if (remainingBuffer != null && remainingBuffer.length() > 0) {
            String remainingText = remainingBuffer.toString().trim();
            if (!remainingText.isEmpty()) {
                synthesizeSentence(sessionId, remainingText, session);
            }
        }
        
        session.sendMessage(new TextMessage("{\"type\":\"complete\"}"));
    }
    
    // 累积文本并按句子分割进行TTS
    private void accumulateAndSynthesize(String sessionId, String textChunk, WebSocketSession session) {
        StringBuilder buffer = textBuffers.get(sessionId);
        if (buffer == null) {
            buffer = new StringBuilder();
            textBuffers.put(sessionId, buffer);
        }
        
        // 发送文本块到前端用于流式显示
        try {
            if (textChunk != null && !textChunk.isEmpty() && session.isOpen()) {
                // 使用简单的 JSON 转义
                String escapedText = textChunk
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
                session.sendMessage(new TextMessage("{\"type\":\"text_chunk\",\"text\":\"" + escapedText + "\"}"));
                log.debug("Sent text chunk to frontend for session {}: {}", sessionId, textChunk);
            }
        } catch (Exception e) {
            log.error("Failed to send text chunk to frontend for session: {}", sessionId, e);
        }
        
        buffer.append(textChunk);
        String accumulated = buffer.toString();
        
        // 按句子分割（句号、问号、感叹号、换行符）
        String[] sentences = accumulated.split("(?<=[。！？\n])");
        
        // 如果最后一个字符不是句子结束符，说明句子未完成，保留在缓冲区
        if (sentences.length > 0) {
            // 处理完整的句子
            for (int i = 0; i < sentences.length - 1; i++) {
                String sentence = sentences[i].trim();
                if (!sentence.isEmpty()) {
                    synthesizeSentence(sessionId, sentence, session);
                }
            }
            
            // 保留最后一个可能未完成的句子
            String lastSentence = sentences[sentences.length - 1].trim();
            if (lastSentence.isEmpty() || accumulated.endsWith("。") || 
                accumulated.endsWith("！") || accumulated.endsWith("？") || 
                accumulated.endsWith("\n")) {
                // 如果最后一个句子也完整，处理它
                if (!lastSentence.isEmpty()) {
                    synthesizeSentence(sessionId, lastSentence, session);
                }
                buffer.setLength(0);
            } else {
                // 保留未完成的句子
                buffer.setLength(0);
                buffer.append(lastSentence);
            }
        }
    }
    
    // 将句子加入TTS队列
    private void synthesizeSentence(String sessionId, String sentence, WebSocketSession session) {
        if (sentence == null || sentence.trim().isEmpty()) {
            return;
        }
        
        log.debug("Queueing sentence for TTS, session {}: {}", sessionId, sentence);
        
        // 获取或创建该会话的队列
        Queue<String> queue = ttsQueues.computeIfAbsent(sessionId, k -> new ConcurrentLinkedQueue<>());
        queue.offer(sentence);
        
        // 如果还没有调度任务，启动一个
        if (!ttsScheduledTasks.containsKey(sessionId) || ttsScheduledTasks.get(sessionId).isDone()) {
            startTtsProcessor(sessionId, session);
        }
    }
    
    // 启动TTS处理器，每2秒处理一个句子
    private void startTtsProcessor(String sessionId, WebSocketSession session) {
        // 取消之前的任务（如果存在）
        ScheduledFuture<?> existingTask = ttsScheduledTasks.get(sessionId);
        if (existingTask != null && !existingTask.isDone()) {
            existingTask.cancel(false);
        }
        
        // 立即处理第一个句子
        processNextTtsSentence(sessionId, session);
        
        // 调度后续处理：延迟2秒后开始，然后每2秒执行一次
        ScheduledFuture<?> task = ttsScheduler.scheduleAtFixedRate(
            () -> processNextTtsSentence(sessionId, session),
            TTS_INTERVAL_MS,
            TTS_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
        
        ttsScheduledTasks.put(sessionId, task);
        log.debug("Started TTS processor for session: {}, will process one sentence every {}ms", sessionId, TTS_INTERVAL_MS);
    }
    
    // 处理下一个TTS句子
    private void processNextTtsSentence(String sessionId, WebSocketSession session) {
        // *************** Tracing ***************
        Span connectionSpan = connectionSpans.get(sessionId);
        if (connectionSpan == null) {
            log.warn("No connection span found for session: {}", sessionId);
            return;
        }
        
        try (Scope scope = connectionSpan.makeCurrent()) {
        // *************** Tracing ***************
            // 检查session是否仍然打开
            if (session == null || !session.isOpen()) {
                log.debug("Session {} is closed, stopping TTS processor", sessionId);
                Queue<String> queue = ttsQueues.remove(sessionId);
                if (queue != null) {
                    queue.clear();
                }
                ScheduledFuture<?> task = ttsScheduledTasks.remove(sessionId);
                if (task != null && !task.isDone()) {
                    task.cancel(false);
                }
                
                // 结束 TTS Span
                endTtsSpan(sessionId);
                return;
            }
            
            Queue<String> queue = ttsQueues.get(sessionId);
            if (queue == null || queue.isEmpty()) {
                // 队列为空，停止调度
                ScheduledFuture<?> task = ttsScheduledTasks.remove(sessionId);
                if (task != null && !task.isDone()) {
                    task.cancel(false);
                }
                log.debug("TTS queue empty for session {}, stopping processor", sessionId);
                
                // 结束 TTS Span
                endTtsSpan(sessionId);
                return;
            }
            
            String sentence = queue.poll();
            if (sentence != null) {
                log.info("Processing TTS for session {} (queue size: {}): {}", sessionId, queue.size(), sentence);

                // *************** Tracing ***************
                // 3. 如果是第一个 TTS 请求，创建 TTS Span
                if (!ttsSpans.containsKey(sessionId)) {
                    Span ttsSpan = tracer.spanBuilder("tts.synthesis")
                        .setParent(Context.current())
                        .setAttribute("gen_ai.span.kind", "WEBSOCKET")
                        .setAttribute("tts.session.id", sessionId)
                        .setAttribute("tts.format", "mp3")
                        .startSpan();
                    
                    ttsSpans.put(sessionId, ttsSpan);
                    WebSocketPerformanceMeasure measure = WebSocketPerformanceMeasure.create();
                    measure.start();
                // *************** Tracing ***************
                    ttsMeasures.put(sessionId, measure);
                    
                    log.info("Created TTS span for session: {}", sessionId);
                }
                
                try {
                    log.debug("Calling TTS synthesizeStream for session: {}, sentence: {}", sessionId, sentence);
                    ttsService.synthesizeStream(sessionId, sentence, audioChunk -> {
                        try {
                            // 记录 TTS chunk 时间
                            WebSocketPerformanceMeasure ttsMeasure = ttsMeasures.get(sessionId);
                            if (ttsMeasure != null) {
                                ttsMeasure.recordChunk();
                            }
                            
                            // 4. 开始向前端写入数据 Span
                            if (!writeSpans.containsKey(sessionId)) {
                                Span writeSpan = tracer.spanBuilder("websocket.write")
                                    .setParent(Context.current())
                                    .setAttribute("gen_ai.span.kind", "WEBSOCKET")
                                    .setAttribute("websocket.write.session.id", sessionId)
                                    .setAttribute("websocket.write.type", "binary")
                                    .startSpan();
                                
                                writeSpans.put(sessionId, writeSpan);
                                WebSocketPerformanceMeasure writeMeasure = WebSocketPerformanceMeasure.create();
                                writeMeasure.start();
                                writeMeasures.put(sessionId, writeMeasure);
                                
                                log.info("Created write span for session: {}", sessionId);
                            }
                            
                            // 记录写入 chunk 时间
                            Span writeSpan = writeSpans.get(sessionId);
                            WebSocketPerformanceMeasure writeMeasure = writeMeasures.get(sessionId);
                            if (writeSpan != null && writeMeasure != null) {
                                Long timeToFirstChunk = writeMeasure.recordChunk();
                                if (timeToFirstChunk != null) {
                                    writeSpan.setAttribute("websocket.write.time_to_first_chunk_ms", timeToFirstChunk);
                                    log.debug("Write first chunk sent for session {}: {}ms", sessionId, timeToFirstChunk);
                                }
                            }
                            
                            log.debug("TTS callback received audio chunk: {} bytes, session open: {}", 
                                audioChunk != null ? audioChunk.length : 0, session.isOpen());
                            if (audioChunk != null && audioChunk.length > 0 && session.isOpen()) {
                        session.sendMessage(new BinaryMessage(audioChunk));
                                log.info("Sent audio chunk: {} bytes for session: {}", audioChunk.length, sessionId);
                            } else {
                                if (audioChunk == null || audioChunk.length == 0) {
                                    log.warn("Audio chunk is null or empty for session: {}", sessionId);
                                }
                                if (!session.isOpen()) {
                                    log.warn("Session is closed for session: {}", sessionId);
                                }
                            }
                        } catch (Exception e) {
                            log.error("Failed to send audio chunk for session: {}", sessionId, e);
                        }
                    });
                    log.debug("TTS synthesizeStream call completed for session: {}", sessionId);
                } catch (Exception e) {
                    log.error("Failed to synthesize sentence for session {}: {}", sessionId, sentence, e);
                }
            }
        }
    }
    
    // 结束 TTS Span
    private void endTtsSpan(String sessionId) {
        Span ttsSpan = ttsSpans.remove(sessionId);
        WebSocketPerformanceMeasure measure = ttsMeasures.remove(sessionId);
        if (ttsSpan != null) {
            if (measure != null) {
                measure.applyToSpan(ttsSpan, "tts");
            }
            ttsSpan.end();
            log.info("Ended TTS span for session: {}", sessionId);
        }
        
        // 结束写入 Span
        endWriteSpan(sessionId);
    }
    
    // 结束写入 Span
    private void endWriteSpan(String sessionId) {
        Span writeSpan = writeSpans.remove(sessionId);
        WebSocketPerformanceMeasure measure = writeMeasures.remove(sessionId);
        if (writeSpan != null) {
            if (measure != null) {
                measure.applyToSpan(writeSpan, "websocket.write");
            }
            writeSpan.end();
            log.info("Ended write span for session: {}", sessionId);
        }
    }

    private String extractItem(String text) {
        // 简单提取逻辑（真实场景应使用 NLP 或 LLM）
        if (text.contains("苹果")) return "苹果";
        if (text.contains("香蕉")) return "香蕉";
        if (text.contains("橙子")) return "橙子";
        if (text.contains("手机")) return "手机";
        if (text.contains("电脑")) return "电脑";
        return "商品";
    }

    private int extractQuantity(String text) {
        // 简单提取数量
        if (text.contains("一") || text.contains("1")) return 1;
        if (text.contains("两") || text.contains("2")) return 2;
        if (text.contains("三") || text.contains("3")) return 3;
        return 1;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = session.getId();
        log.info("WS closed: {} status={}", sessionId, status);
        
        // 结束所有未完成的 Span
        endTtsSpan(sessionId);
        endWriteSpan(sessionId);
        
        Span asrSpan = asrSpans.remove(sessionId);
        WebSocketPerformanceMeasure asrMeasure = asrMeasures.remove(sessionId);
        if (asrSpan != null) {
            if (asrMeasure != null) {
                asrMeasure.applyToSpan(asrSpan, "asr");
            }
            asrSpan.end();
        }
        
        // 结束连接 Span
        Span connectionSpan = connectionSpans.remove(sessionId);
        if (connectionSpan != null) {
            connectionSpan.setAttribute("websocket.close.status", status.getCode());
            connectionSpan.setAttribute("websocket.close.reason", status.getReason());
            connectionSpan.end();
            log.info("Ended connection span for session: {}", sessionId);
        }
        
        // 清理会话状态
        audioBuffers.remove(sessionId);
        lastAudioTimestamp.remove(sessionId);
        processingFlags.remove(sessionId);
        textBuffers.remove(sessionId);
        
        // 清理TTS队列和调度任务
        Queue<String> queue = ttsQueues.remove(sessionId);
        if (queue != null) {
            queue.clear();
        }
        ScheduledFuture<?> task = ttsScheduledTasks.remove(sessionId);
        if (task != null && !task.isDone()) {
            task.cancel(false);
        }
        
        llmService.clearContext(sessionId);
    }
}


