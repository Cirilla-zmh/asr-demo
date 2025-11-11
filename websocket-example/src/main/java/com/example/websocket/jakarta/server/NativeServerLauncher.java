package com.example.websocket.jakarta.server;

import org.glassfish.tyrus.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Java WebSocket 原生 API 服务器启动类
 */
public class NativeServerLauncher {
    private static final Logger log = LoggerFactory.getLogger(NativeServerLauncher.class);
    
    public static void main(String[] args) {
        Server server = new Server("localhost", 18081, "/", null, NativeWebSocketServer.class);
        
        try {
            server.start();
            log.info("Java WebSocket 原生服务器已启动: ws://localhost:18081/native/ws");
            log.info("按 'q' 并回车来停止服务器");
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = reader.readLine()) != null && !line.equals("q")) {
                // 等待退出命令
            }
            
        } catch (Exception e) {
            log.error("服务器启动失败", e);
        } finally {
            server.stop();
            log.info("服务器已停止");
        }
    }
}

