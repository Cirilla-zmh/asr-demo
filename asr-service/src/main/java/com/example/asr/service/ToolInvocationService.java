package com.example.asr.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ToolInvocationService {
    @Value("${mcp.order-service.command}")
    private String mcpCommand;

    @Value("${mcp.order-service.script-path}")
    private String mcpScriptPath;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String placeOrder(String item, int quantity) {
        try {
            log.info("Placing order via MCP: item={}, quantity={}", item, quantity);
            
            // 构建 JSON-RPC 请求
            Map<String, Object> request = new HashMap<>();
            request.put("jsonrpc", "2.0");
            request.put("id", System.currentTimeMillis());
            request.put("method", "order.place");
            
            Map<String, Object> params = new HashMap<>();
            params.put("item", item);
            params.put("quantity", quantity);
            request.put("params", params);
            
            String requestJson = objectMapper.writeValueAsString(request);
            
            // 启动 MCP 进程
            ProcessBuilder pb = new ProcessBuilder(mcpCommand, mcpScriptPath);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // 发送请求
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()))) {
                writer.write(requestJson);
                writer.newLine();
                writer.flush();
            }
            
            // 读取响应
            String response;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                response = reader.readLine();
            }
            
            process.waitFor(5, TimeUnit.SECONDS);
            process.destroy();
            
            if (response != null) {
                JsonNode responseNode = objectMapper.readTree(response);
                JsonNode result = responseNode.get("result");
                if (result != null) {
                    String orderId = result.get("orderId").asText();
                    log.info("Order placed successfully: {}", orderId);
                    return orderId;
                }
            }
            
            log.warn("Failed to place order, no valid response");
            return "ORDER-FAILED";
            
        } catch (Exception e) {
            log.error("Failed to invoke MCP order service", e);
            return "ORDER-ERROR";
        }
    }
}


