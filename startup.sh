#!/bin/bash
# ========================================
# 汽车销售 AI 客服 —— 本地启动脚本（模板）
# 复制此文件为 start.sh 并填入真实 API Key
# start.sh 已在 .gitignore 中，不会提交到 Git
# ========================================

# ---- API Keys（请替换为真实 Key） ----
export DEEPSEEK_API_KEY=your-deepseek-api-key
export SILICONFLOW_API_KEY=your-siliconflow-api-key
export ANTHROPIC_API_KEY=your-anthropic-api-key
# Langfuse 可观测（可选：Langfuse 未启动也不影响应用运行）
export LANGFUSE_PUBLIC_KEY=pk-lf-your-public-key
export LANGFUSE_SECRET_KEY=sk-lf-your-secret-key
export LANGFUSE_BASE_URL=http://127.0.0.1:3000

echo "✅ 环境变量已设置"

# ---- 启动 Spring Boot ----
./mvnw spring-boot:run
