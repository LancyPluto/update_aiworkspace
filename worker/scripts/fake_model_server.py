import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer


HOST = os.getenv("FAKE_MODEL_HOST", "127.0.0.1")
PORT = int(os.getenv("FAKE_MODEL_PORT", "18081"))

MARKDOWN_RESULT = """## 标题建议
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
如果你最近也想给自己放松一下，可以先收藏起来找时间去试试。
"""


class Handler(BaseHTTPRequestHandler):
    def do_POST(self) -> None:
        if self.path != "/chat/completions":
            self.send_response(404)
            self.end_headers()
            return

        response = {
            "id": "fake-chatcmpl",
            "object": "chat.completion",
            "choices": [
                {
                    "index": 0,
                    "message": {
                        "role": "assistant",
                        "content": MARKDOWN_RESULT,
                    },
                    "finish_reason": "stop",
                }
            ],
        }
        payload = json.dumps(response, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, format: str, *args) -> None:
        return


def main() -> None:
    server = HTTPServer((HOST, PORT), Handler)
    print(f"fake model listening on http://{HOST}:{PORT}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
