import json
import logging
import sys
import threading
import time
from pathlib import Path

import redis


sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from handlers.text_task_handler import TextTaskHandler
from task_queue.redis_consumer import RedisConsumer


QUEUE_NAME = "ai:task:queue"
TASK_ID = 99101


class FakeBackendClient:
    def __init__(self) -> None:
        self.success_payload = None
        self.failed_payload = None
        self.processing_called = False

    def get_execution_context(self, task_id: int) -> dict:
        return {
            "taskId": task_id,
            "taskNo": "T202605090002",
            "toolCode": "xiaohongshu_copywriting",
            "status": "QUEUED",
            "generationMode": "REWRITE",
            "params": {
                "productName": "五一肩颈护理套餐",
                "targetCustomer": "久坐上班族女性",
                "style": "种草",
                "sellingPoints": "价格划算、放松明显、适合节前放松",
                "extraInfo": "团购价99元，限五一假期，门店在杭州拱墅区",
            },
            "rewriteContext": {
                "lastFeedback": ["moreColloquial", "lessAdvertising"],
                "customFeedback": "不要太像活动宣传，更像朋友真实分享",
                "previousResult": {
                    "titles": ["标题A", "标题B", "标题C"],
                    "content": "上一版正文",
                    "hashtags": ["#肩颈护理", "#门店种草"],
                    "cta": "上一版行动引导",
                },
            },
            "systemPrompt": "你是一个专业的小红书营销文案助手。",
            "userPromptTemplate": "rewrite 模式下不会使用这段模板：{{productName}}",
            "outputFormat": "MARKDOWN",
            "modelProviderCode": "fake",
            "modelName": "fake-model",
        }

    def mark_processing(self, task_id: int) -> dict:
        self.processing_called = True
        return {}

    def mark_success(self, task_id: int, payload: dict) -> dict:
        self.success_payload = payload
        return {}

    def mark_failed(self, task_id: int, payload: dict) -> dict:
        self.failed_payload = payload
        return {}


class FakeModelClient:
    def __init__(self) -> None:
        self.calls = []

    def generate(self, prompt: str, *, system_prompt: str = "", model_name: str | None = None) -> str:
        self.calls.append(
            {
                "prompt": prompt,
                "system_prompt": system_prompt,
                "model_name": model_name,
            }
        )
        return """## 标题建议
1. 久坐党真的要试试这个肩颈放松项目
2. 五一前我先去做了个肩颈护理，整个人轻了不少
3. 上班坐太久的人，真的会爱上这种放松感

## 正文
最近坐办公室久了，肩颈紧到晚上回家都不太想动。
这次去体验了五一肩颈护理套餐，整体感受比我想的更舒服，按完整个人明显放松很多，而且团购价也比较友好。
如果你平时也是电脑前一坐一整天，假期前去做一次，状态真的会好很多。

## 标签建议
#肩颈护理
#上班族放松
#五一安排
#杭州探店
#门店种草

## 行动引导
如果你最近也想给自己放松一下，可以先收藏起来找时间去试试。"""


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s [%(name)s] %(message)s")

    backend = FakeBackendClient()
    model = FakeModelClient()
    handler = TextTaskHandler(backend_client=backend, model_client=model)
    consumer = RedisConsumer(handler=handler)

    thread = threading.Thread(target=consumer.start, daemon=True)
    thread.start()

    redis_client = redis.Redis(host="127.0.0.1", port=6379, db=0, decode_responses=True)
    redis_client.delete(QUEUE_NAME)
    redis_client.lpush(
        QUEUE_NAME,
        json.dumps(
            {
                "taskId": TASK_ID,
                "taskNo": "T202605090002",
                "toolCode": "xiaohongshu_copywriting",
                "traceId": "fake-worker-redis-test",
                "createdAt": "2026-05-09T12:10:00",
            },
            ensure_ascii=False,
        ),
    )

    deadline = time.time() + 15
    while time.time() < deadline:
        if backend.success_payload or backend.failed_payload:
            break
        time.sleep(0.2)

    if backend.failed_payload:
        raise SystemExit(f"FAILED: {json.dumps(backend.failed_payload, ensure_ascii=False)}")
    if not backend.success_payload:
        raise SystemExit("FAILED: timeout waiting for worker success")

    assert backend.processing_called is True
    assert model.calls, "model was not called"
    assert "如果本次任务为二次优化" in model.calls[0]["system_prompt"]
    assert "上一版结果" in model.calls[0]["prompt"]
    assert "本轮反馈要求" in model.calls[0]["prompt"]

    content_json = backend.success_payload["contentJson"]
    assert content_json["titles"], backend.success_payload
    assert content_json["content"], backend.success_payload
    assert content_json["hashtags"], backend.success_payload
    assert content_json["cta"], backend.success_payload

    print("FAKE_WORKER_REDIS_TEST_PASSED")
    print(json.dumps({"success_payload": backend.success_payload, "model_call": model.calls[0]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
