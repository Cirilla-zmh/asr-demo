package com.example.websocket.jakarta.server;

import com.example.websocket.common.MessageWithHeaders;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import jakarta.websocket.*;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.websocket.server.ServerEndpointConfig;
import java.util.Deque;
import org.apache.logging.log4j.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java WebSocket 原生 API 服务器端示例
 * 支持基本的消息收发、会话管理和全链路可观测性
 * 
 * 可观测性实现：
 * - 从握手时的 HTTP headers 提取 Client 传递的 TraceContext
 * - 基于 TraceContext 创建子 Span，与 Client 的 Trace 串联
 */
@ServerEndpoint(value = "/native/ws", configurator = NativeWebSocketServer.TraceContextConfigurator.class)
public class NativeWebSocketServer {
    private static final Logger log = LoggerFactory.getLogger(NativeWebSocketServer.class);
    
    // OpenTelemetry Tracer
    private static final Tracer tracer;
    private static final OpenTelemetry openTelemetry;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    static {
        openTelemetry = GlobalOpenTelemetry.get();
        
        tracer = openTelemetry.getTracer("websocket-example", "1.0.0");
        log.info("OpenTelemetry Tracer 初始化完成");
    }
    
    // 会话管理：存储所有连接的会话
    private static final Map<String, Session> sessions = new ConcurrentHashMap<>();
    
    // Trace 管理：存储每个会话对应的连接级别 Context
    private static final Map<String, Context> connectionTraceContexts = new ConcurrentHashMap<>();

    /**
     * Configurator 用于在握手时提取 TraceContext
     */
    public static class TraceContextConfigurator extends ServerEndpointConfig.Configurator {
        private static final TextMapGetter<Map<String, List<String>>> headerGetter = new TextMapGetter<Map<String, List<String>>>() {
            @Override
            public Iterable<String> keys(Map<String, List<String>> carrier) {
                return carrier.keySet();
            }
            
            @Override
            public String get(Map<String, List<String>> carrier, String key) {
                List<String> values = carrier.get(key);
                return values != null && !values.isEmpty() ? values.get(0) : null;
            }
        };
        
        @Override
        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response) {
            // 从 HTTP headers 中提取 TraceContext
            Map<String, List<String>> headers = request.getHeaders();
            Context extractedContext = openTelemetry.getPropagators()
                .getTextMapPropagator()
                .extract(Context.current(), headers, headerGetter);
            
            // 将 TraceContext 存储到 userProperties，在 onOpen 时提取
            sec.getUserProperties().put("traceContext", extractedContext);
        }
    }
    
    @OnOpen
    public void onOpen(Session session, EndpointConfig config) {
        String sessionId = session.getId();
        sessions.put(sessionId, session);

        // 从 config 的 userProperties 中提取 TraceContext（在 Configurator 中设置）
        Context parentContext = Context.current();
        Object traceContextObj = config.getUserProperties().get("traceContext");
        if (traceContextObj instanceof Context) {
            parentContext = (Context) traceContextObj;
        }

        // 将 Client 链路上下文作为父级上下文
        connectionTraceContexts.put(sessionId, parentContext);

        log.info("客户端连接: sessionId={}, 当前连接数={}, 已从 Client TraceContext 创建子 Span", sessionId, sessions.size());
        
        // 发送欢迎消息
        sendMessage(session, "欢迎连接！您的会话ID: " + sessionId);
    }
    
    @OnMessage
    public void onMessage(String message, Session session) {
        String sessionId = session.getId();

        try {
            // 尝试解析为带 headers 的消息
            MessageWithHeaders msgWithHeaders = objectMapper.readValue(message, MessageWithHeaders.class);
            Map<String, String> headers = msgWithHeaders.getHeaders();
            Context remoteContext = openTelemetry.getPropagators().getTextMapPropagator()
                .extract(Context.current(),
                    headers, new TextMapGetter<Map<String, String>>() {
                        @Override
                        public Iterable<String> keys(Map<String, String> headersMap) {
                            return headersMap.keySet();
                        }

                        @Override
                        public String get(Map<String, String> headersMap, String key) {
                            return headersMap.getOrDefault(key, null);
                        }
                    });
            Span serverSpan = tracer.spanBuilder("Server handle message")
                .setParent(remoteContext).startSpan();
            try (Scope scope = serverSpan.makeCurrent()) {
                String body = msgWithHeaders.getBody();

                log.info("收到消息 [{}] [headers={}]: {}", sessionId, headers, body);

                // 处理消息（带 headers）
                handleMessage(session, body, headers);
            } catch (Exception e) {
                serverSpan.recordException(e);
            } finally {
                serverSpan.end();
            }
        } catch (Exception e) {
            log.error("消息接受失败 [{}]: {}", sessionId, message, e);
        }
    }
    
    /**
     * 处理消息（带 headers）
     */
    private void handleMessage(Session session, String body, Map<String, String> headers) {
        String sessionId = session.getId();
        
        // 回显消息（带 headers）
        Map<String, String> responseHeaders = new HashMap<>();
        responseHeaders.put("response-to", sessionId);
        responseHeaders.put("timestamp", String.valueOf(System.currentTimeMillis()));
        
        String responseBody = "服务器收到: " + body;
        sendMessage(session, responseBody, responseHeaders);
        
        // 如果是广播消息（以 "broadcast:" 开头）
        if (body.startsWith("broadcast:")) {
            String broadcastMsg = body.substring("broadcast:".length());
            Map<String, String> broadcastHeaders = new HashMap<>();
            broadcastHeaders.put("type", "broadcast");
            broadcastHeaders.put("from", sessionId);
            broadcastMessage(sessionId, "广播消息 [" + sessionId + "]: " + broadcastMsg, broadcastHeaders);
        }
    }
    
    @OnClose
    public void onClose(Session session) {
        String sessionId = session.getId();
        sessions.remove(sessionId);
        connectionTraceContexts.remove(sessionId);
        log.info("客户端断开: sessionId={}, 剩余连接数={}, Trace已结束", sessionId, sessions.size());
    }
    
    @OnError
    public void onError(Session session, Throwable error) {
        String sessionId = session.getId();
        log.error("会话错误 [{}]: {}", sessionId, error.getMessage(), error);
    }
    
    /**
     * 发送消息给指定会话（普通文本，无 headers）
     */
    private void sendMessage(Session session, String message) {
        sendMessage(session, message, null);
    }
    
    /**
     * 发送消息给指定会话（带 headers）
     * 
     * @param session 会话
     * @param message 消息内容
     * @param headers 消息 headers（可以为 null）
     */
    private void sendMessage(Session session, String message, Map<String, String> headers) {
        try {
            if (session.isOpen()) {
                String messageToSend;
                
                if (headers != null && !headers.isEmpty()) {
                    // 如果有 headers，包装成 JSON 格式
                    MessageWithHeaders msgWithHeaders = new MessageWithHeaders(headers, message);
                    messageToSend = objectMapper.writeValueAsString(msgWithHeaders);
                    log.debug("发送消息（带 headers）: headers={}, body={}", headers, message);
                } else {
                    // 如果没有 headers，直接发送原始消息（向后兼容）
                    messageToSend = message;
                    log.debug("发送消息（普通格式）: {}", message);
                }
                
                session.getBasicRemote().sendText(messageToSend);
            }
        } catch (IOException e) {
            log.error("发送消息失败: {}", e.getMessage(), e);
        } catch (Exception e) {
            log.error("序列化消息失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 广播消息给所有会话（除了发送者，带 headers）
     */
    private void broadcastMessage(String excludeSessionId, String message, Map<String, String> headers) {
        sessions.entrySet().stream()
            .filter(entry -> !entry.getKey().equals(excludeSessionId))
            .forEach(entry -> sendMessage(entry.getValue(), message, headers));
    }
    
    /**
     * 获取当前连接数
     */
    public static int getConnectionCount() {
        return sessions.size();
    }
}

