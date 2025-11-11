#!/bin/bash

# ASR Demo 项目启动脚本

echo "======================================"
echo "  ASR Demo 项目启动"
echo "======================================"
echo ""

# 检查 Java 环境
if ! command -v java &> /dev/null; then
    echo "❌ 错误：未找到 Java。请安装 JDK 17 或更高版本。"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 17 ]; then
    echo "❌ 错误：Java 版本过低。当前版本：$JAVA_VERSION，需要版本 17 或更高。"
    exit 1
fi

echo "✓ Java 版本检查通过"

# 检查 Maven 环境
if ! command -v mvn &> /dev/null; then
    echo "❌ 错误：未找到 Maven。请安装 Maven 3.6+。"
    exit 1
fi

echo "✓ Maven 检查通过"

# 检查 Python 环境
if ! command -v python3 &> /dev/null; then
    echo "❌ 错误：未找到 Python3。请安装 Python 3.10+。"
    exit 1
fi

echo "✓ Python3 检查通过"
echo ""

# 进入项目根目录
cd "$(dirname "$0")"

echo "======================================"
echo "  步骤 1: 编译 Java 后端服务"
echo "======================================"
cd asr-service
mvn clean package -DskipTests
if [ $? -ne 0 ]; then
    echo "❌ Maven 编译失败"
    exit 1
fi

# 检查 JAR 文件是否存在
JAR_FILE="target/asr-service-0.1.0-SNAPSHOT.jar"
if [ ! -f "$JAR_FILE" ]; then
    echo "❌ 错误：未找到 JAR 文件: $JAR_FILE"
    exit 1
fi

echo "✓ 编译完成"
echo ""

cd ..

echo "======================================"
echo "  步骤 2: 安装 Python 依赖"
echo "======================================"
cd order-mcp
pip3 install -r requirements.txt -q
if [ $? -ne 0 ]; then
    echo "❌ Python 依赖安装失败"
    exit 1
fi
echo "✓ 依赖安装完成"
echo ""

cd ..

echo "======================================"
echo "  步骤 3: 启动后端服务"
echo "======================================"
echo ""
echo "正在启动 ASR 服务..."
echo "服务地址: http://localhost:8080"
echo "WebSocket: ws://localhost:8080/ws/asr"
echo ""
echo "按 Ctrl+C 停止服务"
echo ""

cd asr-service
java $JAVA_AGENT_OPTIONS -jar target/asr-service-0.1.0-SNAPSHOT.jar
