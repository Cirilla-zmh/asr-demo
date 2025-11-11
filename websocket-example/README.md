# WebSocket 示例模块

本模块提供了两种 WebSocket 实现方式的简单示例（基于 JDK 17+）：
1. **Jakarta WebSocket API** (原生 API，使用 jakarta.websocket)
2. **Spring WebSocket**

## 项目结构

```
websocket-example/
├── src/main/java/com/example/websocket/
│   ├── jakarta/         # Jakarta WebSocket API 示例 (jakarta.websocket，JDK 17+)
│   │   ├── server/      # 服务器端
│   │   │   ├── NativeWebSocketServer.java    # WebSocket 端点
│   │   │   └── NativeServerLauncher.java     # 服务器启动类
│   │   └── client/      # 客户端
│   │       └── NativeWebSocketClient.java    # 客户端实现
│   └── spring/          # Spring WebSocket 示例
│       ├── server/      # 服务器端
│       │   ├── SpringWebSocketHandler.java   # WebSocket 处理器
│       │   ├── WebSocketConfig.java          # WebSocket 配置
│       │   └── SpringServerApplication.java  # Spring Boot 启动类
│       └── client/       # 客户端
│           └── SpringWebSocketClient.java    # 客户端实现
├── pom.xml
└── README.md
```

## 功能特性

- ✅ 基本的消息收发
- ✅ 会话管理（连接、断开、错误处理）
- ✅ 广播消息功能
- ✅ 最少的代码实现

## 使用方法

### 1. Jakarta WebSocket API (原生 API)

#### 启动服务器
```bash
cd websocket-example
mvn exec:java -Dexec.mainClass="com.example.websocket.jakarta.server.NativeServerLauncher"
```
服务器将在 `ws://localhost:8081/native/ws` 启动

#### 启动客户端
```bash
mvn exec:java -Dexec.mainClass="com.example.websocket.jakarta.client.NativeWebSocketClient"
```
客户端将连接到服务器，可以输入消息进行交互

### 2. Spring WebSocket

#### 启动服务器
```bash
mvn spring-boot:run -Dspring-boot.run.main-class=com.example.websocket.spring.server.SpringServerApplication
```
服务器将在 `ws://localhost:8082/spring/ws` 启动

#### 启动客户端
```bash
mvn exec:java -Dexec.mainClass="com.example.websocket.spring.client.SpringWebSocketClient"
```
客户端将连接到服务器，可以输入消息进行交互

## 测试示例

### 基本消息收发
- 客户端发送：`Hello Server`
- 服务器回复：`服务器收到: Hello Server`

### 广播消息
- 客户端发送：`broadcast: 这是一条广播消息`
- 服务器会将消息广播给所有其他连接的客户端

### 退出
- 输入 `exit` 退出客户端

## 会话管理

两种实现都包含基本的会话管理：
- 连接建立时记录会话
- 连接断开时清理会话
- 错误处理
- 连接数统计

## 注意事项

1. 确保端口未被占用（原生 API: 8081, Spring: 8082）
2. 先启动服务器，再启动客户端
3. 可以启动多个客户端进行测试

