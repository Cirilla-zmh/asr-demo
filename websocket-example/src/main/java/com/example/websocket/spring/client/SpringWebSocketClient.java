package com.example.websocket.spring.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.concurrent.CompletableFuture;

/**
 * Spring WebSocket 客户端示例
 */
public class SpringWebSocketClient extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(SpringWebSocketClient.class);
    
    private WebSocketSession session;
    private String sessionId;
    
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        this.session = session;
        this.sessionId = session.getId();
        log.info("已连接到服务器: sessionId={}", sessionId);
    }
    
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        log.info("收到服务器消息: {}", message.getPayload());
    }
    
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("连接已关闭: sessionId={}, 状态={}", sessionId, status);
    }
    
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("连接错误: {}", exception.getMessage(), exception);
    }
    
    /**
     * 发送消息
     */
    public void sendMessage(String message) {
        try {
            if (session != null && session.isOpen()) {
                session.sendMessage(new TextMessage(message));
                log.info("发送消息: {}", message);
            } else {
                log.warn("会话未打开，无法发送消息");
            }
        } catch (Exception e) {
            log.error("发送消息失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 关闭连接
     */
    public void close() {
        try {
            if (session != null && session.isOpen()) {
                session.close();
            }
        } catch (Exception e) {
            log.error("关闭连接失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 主方法：启动客户端并交互
     */
    public static void main(String[] args) {
        try {
            // 创建 WebSocket 客户端
            WebSocketClient client = new StandardWebSocketClient();
            SpringWebSocketClient handler = new SpringWebSocketClient();
            
            // 连接到服务器
            CompletableFuture<WebSocketSession> future = client.execute(handler, 
                "ws://localhost:8082/spring/ws");
            
            // 等待连接建立
            WebSocketSession session = future.get();
            handler.session = session;
            handler.sessionId = session.getId();
            
            log.info("客户端已启动，输入消息发送给服务器（输入 'exit' 退出）:");
            
            // 从控制台读取输入
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = reader.readLine()) != null && !line.equals("exit")) {
                if (!line.trim().isEmpty()) {
                    handler.sendMessage(line);
                }
            }
            
            // 关闭连接
            handler.close();
            log.info("客户端已退出");
            
        } catch (Exception e) {
            log.error("客户端启动失败", e);
        }
    }
}

