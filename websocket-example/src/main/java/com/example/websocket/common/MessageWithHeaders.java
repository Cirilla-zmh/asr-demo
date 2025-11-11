package com.example.websocket.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashMap;
import java.util.Map;

/**
 * 带 Headers 的 WebSocket 消息包装类
 * 用于在消息内容中嵌入元数据（headers）
 */
public class MessageWithHeaders {
    @JsonProperty("headers")
    private Map<String, String> headers;
    
    @JsonProperty("body")
    private String body;
    
    public MessageWithHeaders() {
        this.headers = new HashMap<>();
    }
    
    public MessageWithHeaders(String body) {
        this();
        this.body = body;
    }
    
    public MessageWithHeaders(Map<String, String> headers, String body) {
        this.headers = headers != null ? headers : new HashMap<>();
        this.body = body;
    }
    
    public Map<String, String> getHeaders() {
        return headers;
    }
    
    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }
    
    public String getBody() {
        return body;
    }
    
    public void setBody(String body) {
        this.body = body;
    }
    
    /**
     * 添加 header
     */
    public MessageWithHeaders addHeader(String key, String value) {
        this.headers.put(key, value);
        return this;
    }
    
    /**
     * 获取 header
     */
    public String getHeader(String key) {
        return this.headers.get(key);
    }
}

