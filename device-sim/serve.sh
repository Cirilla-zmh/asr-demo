#!/bin/bash

echo "======================================"
echo "  启动前端设备模拟页面"
echo "======================================"
echo ""
echo "服务地址: http://localhost:8000"
echo "请确保后端服务已启动: http://localhost:8080"
echo ""
echo "按 Ctrl+C 停止服务"
echo ""

cd "$(dirname "$0")"
python3 -m http.server 8000


