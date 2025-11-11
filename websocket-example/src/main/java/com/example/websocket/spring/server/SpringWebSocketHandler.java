package com.example.websocket.spring.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spring WebSocket 服务器端处理器
 * 支持基本的消息收发和会话管理
 */
@Component
public class SpringWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(SpringWebSocketHandler.class);
    
    // 会话管理：存储所有连接的会话
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = session.getId();
        sessions.put(sessionId, session);
        log.info("客户端连接: sessionId={}, 当前连接数={}", sessionId, sessions.size());
        
        // 发送欢迎消息
        session.sendMessage(new TextMessage("欢迎连接！您的会话ID: " + sessionId));
    }
    
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String sessionId = session.getId();
        String payload = message.getPayload();
        log.info("收到消息 [{}]: {}", sessionId, payload);
        
        // 回显消息
        String response = "服务器收到: " + payload;
        session.sendMessage(new TextMessage(response));
        
        // 如果是广播消息（以 "broadcast:" 开头）
        if (payload.startsWith("broadcast:")) {
            String broadcastMsg = payload.substring("broadcast:".length());
            broadcastMessage(sessionId, "广播消息 [" + sessionId + "]: " + broadcastMsg);
        }
    }
    
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = session.getId();
        sessions.remove(sessionId);
        log.info("客户端断开: sessionId={}, 状态={}, 剩余连接数={}", sessionId, status, sessions.size());
    }
    
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        String sessionId = session.getId();
        log.error("会话错误 [{}]: {}", sessionId, exception.getMessage(), exception);
    }
    
    /**
     * 广播消息给所有会话（除了发送者）
     */
    private void broadcastMessage(String excludeSessionId, String message) {
        sessions.entrySet().stream()
            .filter(entry -> !entry.getKey().equals(excludeSessionId))
            .forEach(entry -> {
                try {
                    entry.getValue().sendMessage(new TextMessage(message));
                } catch (Exception e) {
                    log.error("广播消息失败 [{}]: {}", entry.getKey(), e.getMessage());
                }
            });
    }
    
    /**
     * 获取当前连接数
     */
    public int getConnectionCount() {
        return sessions.size();
    }
}

