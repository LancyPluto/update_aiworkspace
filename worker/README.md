# AI 任务 Worker

技术栈：**Python 3.10+**（推荐 3.11）、**redis-py**（队列 `ai:task:queue`）、**httpx**（调用 Spring 内部 API）、**pydantic-settings**（配置）。

## 本地运行

```bash
cd worker
python3 -m venv .venv
source .venv/bin/activate
pip install -e .
cp .env.example .env
# 编辑 .env：BACKEND_BASE_URL、INTERNAL_API_TOKEN、REDIS_*、MODEL_*
ai-task-worker
```

等价：`python -m ai_task_worker`

## 目录说明

| 路径 | 职责 |
|------|------|
| `ai_task_worker/config.py` | 环境变量与默认值 |
| `ai_task_worker/redis_consumer.py` | 从 Redis 阻塞读取任务消息 |
| `ai_task_worker/schemas.py` | 队列消息、执行上下文等数据结构 |
| `ai_task_worker/internal_api.py` | `execution-context` / `processing` / `success` / `failed` |
| `ai_task_worker/prompt.py` | `{{var}}` 替换与缺失检测 |
| `ai_task_worker/model_client.py` | OpenAI 兼容 Chat Completions 调用 |
| `ai_task_worker/worker.py` | 消费循环与编排（后续按天迭代） |
| `ai_task_worker/__main__.py` | 入口 |

与后端契约见仓库根目录 [README.md](../README.md) 中「AI 任务 Worker」一节。
