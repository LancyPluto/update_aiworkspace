import json
import os
from datetime import datetime
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path


HOST = os.getenv("FAKE_BACKEND_HOST", "127.0.0.1")
PORT = int(os.getenv("FAKE_BACKEND_PORT", "18080"))
INTERNAL_TOKEN = os.getenv("INTERNAL_API_TOKEN", "local-internal-token")
GENERATION_MODE = os.getenv("FAKE_GENERATION_MODE", "INITIAL").upper()
STATE_FILE = Path(os.getenv("FAKE_BACKEND_STATE_FILE", "logs/fake_backend_state.json"))


def build_execution_context(task_id: int) -> dict:
    context = {
        "taskId": task_id,
        "taskNo": f"T{datetime.now().strftime('%Y%m%d%H%M%S')}",
        "toolCode": "xiaohongshu_copywriting",
        "status": "QUEUED",
        "params": {
            "productName": "五一肩颈护理套餐",
            "targetCustomer": "久坐上班族女性",
            "style": "种草",
            "sellingPoints": "价格划算、放松明显、适合节前放松",
            "extraInfo": "团购价99元，限五一假期，门店在杭州拱墅区",
        },
        "systemPrompt": "你是一个专业的小红书营销文案助手。",
        "userPromptTemplate": (
            "请根据以下信息生成文案：{{productName}}，目标用户：{{targetCustomer}}，"
            "风格：{{style}}，卖点：{{sellingPoints}}，补充：{{extraInfo}}"
        ),
        "outputFormat": "MARKDOWN",
        "modelProviderCode": "deepseek",
        "modelName": "deepseek-chat",
    }

    if GENERATION_MODE == "REWRITE":
        context["generationMode"] = "REWRITE"
        context["rewriteContext"] = {
            "lastFeedback": ["moreColloquial", "lessAdvertising"],
            "customFeedback": "不要太像活动宣传，更像朋友真实分享",
            "previousResult": {
                "titles": ["标题A", "标题B", "标题C"],
                "content": "上一版正文",
                "hashtags": ["#肩颈护理", "#门店种草"],
                "cta": "上一版行动引导",
            },
        }
    return context


def write_state(payload: dict) -> None:
    STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    STATE_FILE.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


class Handler(BaseHTTPRequestHandler):
    def _send_json(self, data: dict, status_code: int = 200) -> None:
        response = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(response)))
        self.end_headers()
        self.wfile.write(response)

    def _unauthorized(self) -> None:
        self._send_json({"code": "UNAUTHORIZED", "message": "invalid token", "data": None}, 401)

    def _check_auth(self) -> bool:
        token = self.headers.get("X-Internal-Token") or ""
        auth = self.headers.get("Authorization") or ""
        return token == INTERNAL_TOKEN or auth == f"Bearer {INTERNAL_TOKEN}"

    def _read_body(self) -> dict:
        content_length = int(self.headers.get("Content-Length", "0"))
        if content_length <= 0:
            return {}
        raw = self.rfile.read(content_length)
        if not raw:
            return {}
        return json.loads(raw.decode("utf-8"))

    def do_GET(self) -> None:
        if not self._check_auth():
            self._unauthorized()
            return

        if self.path.startswith("/api/internal/v1/tasks/") and self.path.endswith("/execution-context"):
            task_id = int(self.path.split("/")[5])
            write_state({"last_event": "execution-context", "taskId": task_id})
            self._send_json({"code": "SUCCESS", "message": "ok", "data": build_execution_context(task_id)})
            return

        self._send_json({"code": "NOT_FOUND", "message": "not found", "data": None}, 404)

    def do_POST(self) -> None:
        if not self._check_auth():
            self._unauthorized()
            return

        payload = self._read_body()
        if self.path.startswith("/api/internal/v1/tasks/") and self.path.endswith("/processing"):
            write_state({"last_event": "processing", "payload": payload})
            self._send_json({"code": "SUCCESS", "message": "ok", "data": {}})
            return

        if self.path.startswith("/api/internal/v1/tasks/") and self.path.endswith("/success"):
            write_state({"last_event": "success", "payload": payload})
            self._send_json({"code": "SUCCESS", "message": "ok", "data": {}})
            return

        if self.path.startswith("/api/internal/v1/tasks/") and self.path.endswith("/failed"):
            write_state({"last_event": "failed", "payload": payload})
            self._send_json({"code": "SUCCESS", "message": "ok", "data": {}})
            return

        self._send_json({"code": "NOT_FOUND", "message": "not found", "data": None}, 404)

    def log_message(self, format: str, *args) -> None:
        return


def main() -> None:
    server = HTTPServer((HOST, PORT), Handler)
    print(f"fake backend listening on http://{HOST}:{PORT}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
