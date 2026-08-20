#!/usr/bin/env bash
# ============================================================
# WavePilot 完整模式一键启动（真实 API + 本地 MATLAB + Milvus）
# 前置：Docker + Maven/JDK17；复制 .env.example 为 .env 并填写
# 用法：chmod +x start-full.sh && ./start-full.sh
# ============================================================
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f .env ]; then
  cp .env.example .env
  echo "[提示] 已创建 .env 模板，请填写 DASHSCOPE_API_KEY 与 MATLAB_EXECUTABLE 后重新运行。"
  exit 1
fi

echo "[1/2] 启动 Milvus（Docker）..."
docker compose -f vector-database.yml up -d

echo "[2/2] 启动 WavePilot 应用（读取 .env 配置）..."
set -a
# shellcheck disable=SC1091
. ./.env
set +a

echo "应用启动后请打开 http://localhost:${SERVER_PORT:-9900}"
exec mvn spring-boot:run
