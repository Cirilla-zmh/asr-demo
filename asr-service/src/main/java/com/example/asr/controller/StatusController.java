package com.example.asr.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class StatusController {

    @GetMapping("/")
    public Map<String, Object> home() {
        Map<String, Object> response = new HashMap<>();
        response.put("service", "ASR Voice Ordering Service");
        response.put("status", "running");
        response.put("version", "0.1.0");
        response.put("websocket_endpoint", "ws://localhost:8080/ws/asr");
        response.put("description", "Connect to /ws/asr for real-time audio streaming");
        return response;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "UP");
        return response;
    }
}

