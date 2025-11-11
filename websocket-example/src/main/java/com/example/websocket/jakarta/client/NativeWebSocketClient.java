package com.example.websocket.jakarta.client;

import com.example.websocket.common.MessageWithHeaders;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapSetter;
import jakarta.websocket.*;
import java.util.ArrayDeque;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Java WebSocket 原生 API 客户端示例
 * 支持全链路可观测性
 * 
 * 可观测性实现：
 * - Client 创建连接级别的 Trace
 * - 通过握手时的 HTTP headers 传递 TraceContext 给 Server
 * - Server 提取 TraceContext 并创建子 Span
 */
public class NativeWebSocketClient {
    private static final Logger log = LoggerFactory.getLogger(NativeWebSocketClient.class);
    
    // OpenTelemetry Tracer
    private static final Tracer tracer;
    private static final OpenTelemetry openTelemetry;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    static {
        openTelemetry = GlobalOpenTelemetry.get();
        
        tracer = openTelemetry.getTracer("websocket-example", "1.0.0");
        log.info("OpenTelemetry Tracer 初始化完成");
    }
    
    private Session session;
    private String sessionId;

    public void onOpen(Session session) {
        this.session = session;
        this.sessionId = session.getId();
        log.info("已连接到服务器: sessionId={}", sessionId);
    }

    public void onMessage(String message) {
        try {
            // 尝试解析为带 headers 的消息
            MessageWithHeaders msgWithHeaders = objectMapper.readValue(message, MessageWithHeaders.class);
            Map<String, String> headers = msgWithHeaders.getHeaders();
            String body = msgWithHeaders.getBody();
            
            log.info("收到服务器消息 [headers={}]: {}", headers, body);
            
            // 处理消息体
            handleMessage(body, headers);
        } catch (Exception e) {
            // 如果不是 JSON 格式，按普通消息处理（向后兼容）
            log.info("收到服务器消息（普通格式）: {}", message);
            handleMessage(message, null);
        }
    }
    
    /**
     * 处理消息（带 headers）
     */
    private void handleMessage(String body, Map<String, String> headers) {
        // 业务逻辑处理
        if (headers != null) {
            log.debug("消息 headers: {}", headers);
        }
        // 这里可以基于 headers 做不同的处理
    }

    public void onClose() {
        log.info("连接已关闭: sessionId={}", sessionId);
    }

    public void onError(Throwable error) {
        log.error("连接错误: {}", error.getMessage(), error);
    }
    
    /**
     * 发送消息（普通文本，无 headers）
     */
    public void sendMessage(String message) {
        HashMap<String, String> headers = new HashMap<>();
        Span span = tracer.spanBuilder("Client send message").startSpan();
        // 向 span 中写入 session id
        span.setAttribute("websocket.session.id", session.getId());
        try (Scope scope = span.makeCurrent()) {
            openTelemetry.getPropagators().getTextMapPropagator().inject(Context.current(), headers,
                (headersMap, key, value) -> headersMap.put(key, value));
            sendMessage(message, headers);
        } finally {
            span.end();
        }
    }
    
    /**
     * 发送消息（带 headers）
     * 
     * @param message 消息内容
     * @param headers 消息 headers（可以为 null）
     */
    public void sendMessage(String message, Map<String, String> headers) {
        try {
            if (session != null && session.isOpen()) {
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
    public static void main(String[] args) throws Exception {
        // 1. 创建连接级别的 Trace（在连接前创建，以便在握手时传递 TraceContext）
        Span connectionSpan = tracer.spanBuilder("websocket.connection")
            .setAttribute("websocket.endpoint", "/native/ws")
            .setAttribute("websocket.destination", "ws://localhost:18081")
            .setAttribute("websocket.connection.type", "client")
            .startSpan();
        // 2. 将当前 Span 激活在线程内的上下文中
        try (Scope scope = connectionSpan.makeCurrent()) {
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            // 创建 WebSocket Client
            NativeWebSocketClient client = new NativeWebSocketClient();

            // 使用 Endpoint 方式连接
            Session session = container.connectToServer(
                new jakarta.websocket.Endpoint() {
                    @Override
                    public void onOpen(Session session, EndpointConfig config) {
                        client.onOpen(session);

                        // 注册消息处理器（使用匿名内部类而不是 lambda，避免泛型类型推断问题）
                        session.addMessageHandler(new MessageHandler.Whole<String>() {
                            @Override
                            public void onMessage(String message) {
                                client.onMessage(message);
                            }
                        });
                    }

                    @Override
                    public void onClose(Session session, CloseReason closeReason) {
                        client.onClose();
                    }

                    @Override
                    public void onError(Session session, Throwable thr) {
                        // 记录错误到当前 Span
                        connectionSpan.recordException(thr);

                        client.onError(thr);
                    }
                },
                // 3. 发起握手时，在请求头中携带当前的上下文
                createHeaderWithUserProperties(),
                URI.create("ws://localhost:18081/native/ws"));

            client.session = session;
            client.sessionId = session.getId();

            log.info("客户端已启动，输入消息发送给服务器（输入 'exit' 退出）:");

            // 从控制台读取输入
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = reader.readLine()) != null && !line.equals("exit")) {
                if (!line.trim().isEmpty()) {
                    // 4. 向 Server 发送消息
                    client.sendMessage(line);
                }
            }

            // 关闭连接
            client.close();
            log.info("客户端已退出");
        } catch (Exception e) {
            // 如果出现错误，记录到 span 中
            connectionSpan.recordException(e);
            log.error("客户端启动失败", e);
        } finally {
            // 5. 结束 span
            connectionSpan.end();
        }
        // 等待 span 异步上报，实际业务中无需保留
        Thread.sleep(5000L);
    }

    private static ClientEndpointConfig createHeaderWithUserProperties() {
        // 创建 ClientEndpointConfig，用于在握手时注入 TraceContext
        ClientEndpointConfig.Builder configBuilder = ClientEndpointConfig.Builder.create();

        // 创建 Configurator 来注入 TraceContext 到 HTTP headers
        final Map<String, List<String>> headersMap = new HashMap<>();
        Context currentContext = Context.current();

        // 通过 ContextPropagators 注入 TraceContext 到 headers
        openTelemetry.getPropagators().getTextMapPropagator()
            .inject(currentContext, headersMap, (carrier, key, value) -> carrier.put(key, List.of(value)));

        // 设置 Configurator 来在握手时添加 headers
        configBuilder.configurator(new ClientEndpointConfig.Configurator() {
            @Override
            public void beforeRequest(Map<String, List<String>> headers) {
                headers.putAll(headersMap);
            }
        });
        return configBuilder.build();
    }
}

