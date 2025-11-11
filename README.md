# ASR Demo 项目

这是一个基于语音识别（ASR）、大语言模型（LLM）和语音合成（TTS）的智能对话系统演示项目。

## 项目结构

```
asr-demo/
├── asr-service/           # Java 后端服务（ASR/LLM/TTS/WebSocket）
├── order-mcp/             # Python MCP 下单服务
├── device-sim/            # 前端设备模拟页面
└── README.md
```

## 功能特性

- 🎙️ **实时语音识别**：使用 FunASR 流式识别语音输入
- 🤖 **智能意图识别**：基于 LLM 区分闲聊和下单场景
- 🛒 **工具调用**：通过 MCP 协议调用下单服务
- 🔊 **语音合成**：使用 CosyVoice 流式合成回复语音
- 💬 **多轮对话**：支持会话上下文管理

## 技术栈

### 后端（asr-service）
- Java 17+
- Spring Boot 3.3.3
- DashScope SDK（阿里云灵积模型服务）
- WebSocket

### 下单服务（order-mcp）
- Python 3.10+
- JSON-RPC over stdio

### 前端（device-sim）
- HTML5 + JavaScript
- WebSocket API
- MediaRecorder API

## 前置要求

- JDK 17 或更高版本
- Maven 3.6+
- Python 3.10+
- 现代浏览器（支持 MediaRecorder API）

## 快速开始

### 1. 启动 Python MCP 下单服务

```bash
cd order-mcp
pip install -r requirements.txt
python server.py
```

注意：MCP 服务通过 stdio 通信，会在后端调用时自动启动。

### 2. 启动 Java 后端服务

```bash
JAVA_AGENT_OPTIONS=/path/to/loongsuite-java-agent.jar ./start.sh
```

后端服务将在 `http://localhost:8080` 启动。

### 3. 打开前端页面

在浏览器中打开：
```
file:///path/to/asr-demo/device-sim/index.html
```

或使用简单的 HTTP 服务器：
```bash
cd device-sim
./serve.sh
# 然后访问 http://localhost:8000
```

## 使用说明

1. **开始对话**：点击"开始对话"按钮，允许浏览器访问麦克风
2. **语音输入**：对着麦克风说话（例如："你好"或"我要买两个苹果"）
3. **结束对话**：点击"结束对话"按钮
4. **查看结果**：
   - 日志区域会显示识别结果、意图分类等信息
   - 音频播放器会自动播放合成的回复语音

## 测试场景

### 闲聊场景
- "你好"
- "今天天气怎么样"
- "给我讲个笑话"

### 下单场景
- "我要买两个苹果"
- "帮我订购一台手机"
- "下单三个香蕉"

## 配置说明

### API Key 配置

API Key 在 `asr-service/src/main/resources/application.yaml` 中配置：

```yaml
openai:
  api-key: ${DASHSCOPE_API_KEY}
```

### 模型配置

```yaml
dashscope:
  asr:
    model: fun-asr-realtime  # 语音识别模型
  tts:
    model: cosyvoice-v2       # 语音合成模型
    voice: longxiaochun       # 音色
  llm:
    model: qwen-max           # 大语言模型
```

### MCP 服务路径

```yaml
mcp:
  order-service:
    command: python3
    script-path: ${MCP_ORDER_PATH:../order-mcp/server.py}
```

可通过环境变量 `MCP_ORDER_PATH` 自定义 MCP 服务脚本路径。

## 架构说明

### 数据流

```
设备端 → WebSocket → ASR → LLM(意图识别) → 
  ├─ 闲聊 → LLM(生成) → TTS → 设备端
  └─ 下单 → MCP(下单) → LLM(生成) → TTS → 设备端
```

### 会话管理

- 每个 WebSocket 连接对应一个独立会话
- 会话 ID 由后端自动生成
- 支持多轮对话上下文保存
- TODO: 会话清理策略（暂未实现）

## 日志与调试

### 后端日志

日志级别在 `application.yaml` 中配置：
```yaml
logging:
  level:
    com.example.asr: DEBUG
```

### 前端日志

打开浏览器开发者工具（F12）查看控制台输出。

## 已知限制

1. **音频格式**：前端使用 `audio/webm`，ASR 需要 PCM 格式，当前实现可能需要格式转换
2. **会话清理**：未实现自动会话清理策略，长时间运行可能占用内存
3. **错误恢复**：部分异常场景的重试机制尚未完善
4. **并发限制**：流式 TTS 调用可能存在并发限制

## 故障排查

### WebSocket 连接失败
- 检查后端服务是否启动
- 检查防火墙设置
- 查看浏览器控制台错误信息

### 麦克风无法访问
- 确保浏览器有麦克风权限
- 使用 HTTPS 或 localhost（HTTP 仅在本地可用）

### ASR 识别失败
- 检查 API Key 是否正确
- 查看后端日志中的错误信息
- 确认网络可访问 DashScope 服务

### MCP 调用失败
- 检查 Python 环境是否正确
- 确认 `server.py` 路径配置正确
- 查看后端日志中的 MCP 调用详情

## 许可证

本项目仅供演示和学习使用。

## 联系方式

如有问题，请查看项目文档或提交 Issue。
