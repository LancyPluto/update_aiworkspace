import json
import logging
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

import redis


sys.path.insert(0, str(Path(__file__).resolve().parent.parent))


BACKEND_PORT = 18180
MODEL_PORT = 18181
QUEUE_NAME = "ai:task:queue"
TASK_ID = 99001
STATE: dict[str, object] = {"events": []}


def configure_env() -> None:
    os.environ["BACKEND_INTERNAL_BASE_URL"] = f"http://127.0.0.1:{BACKEND_PORT}"
    os.environ["MODEL_API_BASE_URL"] = f"http://127.0.0.1:{MODEL_PORT}"
    os.environ["MODEL_API_KEY"] = "fake-key"
    os.environ["MODEL_NAME"] = "fake-model"
    os.environ["INTERNAL_API_TOKEN"] = "local-internal-token"
    os.environ["AI_TASK_QUEUE"] = QUEUE_NAME


def build_execution_context() -> dict:
    return {
        "taskId": TASK_ID,
        "taskNo": "T202605090001",
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
        "modelProviderCode": "deepseek",
        "modelName": "deepseek-chat",
    }


class BackendHandler(BaseHTTPRequestHandler):
    def _send_json(self, payload: dict, status_code: int = 200) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self) -> bool:
        token = self.headers.get("X-Internal-Token", "")
        auth = self.headers.get("Authorization", "")
        return token == "local-internal-token" or auth == "Bearer local-internal-token"

    def _read_body(self) -> dict:
        size = int(self.headers.get("Content-Length", "0"))
        if size <= 0:
            return {}
        return json.loads(self.rfile.read(size).decode("utf-8"))

    def do_GET(self) -> None:
        if not self._authorized():
            self._send_json({"code": "UNAUTHORIZED", "message": "invalid token", "data": None}, 401)
            return
        if self.path == f"/api/internal/v1/tasks/{TASK_ID}/execution-context":
            STATE["events"].append("execution-context")
            self._send_json({"code": "SUCCESS", "message": "ok", "data": build_execution_context()})
            return
        self._send_json({"code": "NOT_FOUND", "message": "not found", "data": None}, 404)

    def do_POST(self) -> None:
        if not self._authorized():
            self._send_json({"code": "UNAUTHORIZED", "message": "invalid token", "data": None}, 401)
            return
        payload = self._read_body()
        if self.path == f"/api/internal/v1/tasks/{TASK_ID}/processing":
            STATE["events"].append("processing")
            self._send_json({"code": "SUCCESS", "message": "ok", "data": {}})
            return
        if self.path == f"/api/internal/v1/tasks/{TASK_ID}/success":
            STATE["events"].append("success")
            STATE["success_payload"] = payload
            self._send_json({"code": "SUCCESS", "message": "ok", "data": {}})
            return
        if self.path == f"/api/internal/v1/tasks/{TASK_ID}/failed":
            STATE["events"].append("failed")
            STATE["failed_payload"] = payload
            self._send_json({"code": "SUCCESS", "message": "ok", "data": {}})
            return
        self._send_json({"code": "NOT_FOUND", "message": "not found", "data": None}, 404)

    def log_message(self, format: str, *args) -> None:
        return


class ModelHandler(BaseHTTPRequestHandler):
    def do_POST(self) -> None:
        if self.path != "/chat/completions":
            self.send_response(404)
            self.end_headers()
            return
        size = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(size).decode("utf-8")
        request_payload = json.loads(raw)
        STATE["model_request"] = request_payload

        response = {
            "id": "fake-chatcmpl",
            "object": "chat.completion",
            "choices": [
                {
                    "index": 0,
                    "message": {
                        "role": "assistant",
                        "content": """## 标题建议
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
如果你最近也想给自己放松一下，可以先收藏起来找时间去试试。""",
                    },
                    "finish_reason": "stop",
                }
            ],
        }
        body = json.dumps(response, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args) -> None:
        return


def serve_forever(server: HTTPServer) -> None:
    server.serve_forever()


def main() -> None:
    configure_env()

    from task_queue.redis_consumer import RedisConsumer

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s [%(name)s] %(message)s")

    backend_server = HTTPServer(("127.0.0.1", BACKEND_PORT), BackendHandler)
    model_server = HTTPServer(("127.0.0.1", MODEL_PORT), ModelHandler)

    threading.Thread(target=serve_forever, args=(backend_server,), daemon=True).start()
    threading.Thread(target=serve_forever, args=(model_server,), daemon=True).start()

    consumer = RedisConsumer()
    threading.Thread(target=consumer.start, daemon=True).start()

    redis_client = redis.Redis(host="127.0.0.1", port=6379, db=0, decode_responses=True)
    redis_client.delete(QUEUE_NAME)
    redis_client.lpush(
        QUEUE_NAME,
        json.dumps(
            {
                "taskId": TASK_ID,
                "taskNo": "T202605090001",
                "toolCode": "xiaohongshu_copywriting",
                "traceId": "fake-integration-test",
                "createdAt": "2026-05-09T12:00:00",
            },
            ensure_ascii=False,
        ),
    )

    deadline = time.time() + 15
    while time.time() < deadline:
        if STATE.get("success_payload"):
            break
        if STATE.get("failed_payload"):
            break
        time.sleep(0.2)

    backend_server.shutdown()
    model_server.shutdown()

    if STATE.get("failed_payload"):
        raise SystemExit(f"FAILED: {json.dumps(STATE['failed_payload'], ensure_ascii=False)}")

    success_payload = STATE.get("success_payload")
    if not success_payload:
        raise SystemExit("FAILED: timeout waiting for success payload")

    model_request = STATE.get("model_request") or {}
    messages = model_request.get("messages") or []
    model_user_prompt = messages[-1]["content"] if messages else ""
    model_system_prompt = messages[0]["content"] if messages else ""

    assert success_payload["resourceType"] == "MARKDOWN", success_payload
    assert "## 标题建议" in success_payload["contentText"], success_payload
    assert "## 正文" in success_payload["contentText"], success_payload
    assert "## 标签建议" in success_payload["contentText"], success_payload
    assert "## 行动引导" in success_payload["contentText"], success_payload
    assert "如果本次任务为二次优化" in model_system_prompt, model_system_prompt
    assert "上一版结果" in model_user_prompt, model_user_prompt
    assert "本轮反馈要求" in model_user_prompt, model_user_prompt

    print("FAKE_INTEGRATION_TEST_PASSED")
    print(json.dumps({"events": STATE["events"], "success_payload": success_payload}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
