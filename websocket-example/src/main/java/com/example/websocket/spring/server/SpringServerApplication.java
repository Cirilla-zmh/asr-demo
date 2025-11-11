package com.example.websocket.spring.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring WebSocket 服务器启动类
 */
@SpringBootApplication
public class SpringServerApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(SpringServerApplication.class, args);
        System.out.println("Spring WebSocket 服务器已启动: ws://localhost:8082/spring/ws");
    }
}

