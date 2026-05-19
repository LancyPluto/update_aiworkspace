import json
import logging
import sys
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from client.model_client import ModelClient
from handlers.text_task_handler import TextTaskHandler


TASK_ID = 99201


class FakeBackendClient:
    def __init__(self) -> None:
        self.success_payload = None
        self.failed_payload = None
        self.processing_called = False

    def get_execution_context(self, task_id: int) -> dict:
        return {
            "taskId": task_id,
            "taskNo": "T202605090003",
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

    def mark_processing(
        self,
        task_id: int,
        *,
        progress: int | None = None,
        progress_message: str | None = None,
    ) -> dict:
        self.processing_called = True
        return {}

    def mark_success(self, task_id: int, payload: dict) -> dict:
        self.success_payload = payload
        return {}

    def mark_failed(self, task_id: int, payload: dict) -> dict:
        self.failed_payload = payload
        return {}


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s [%(name)s] %(message)s")

    backend = FakeBackendClient()
    handler = TextTaskHandler(backend_client=backend, model_client=ModelClient())
    result = handler.handle({"taskId": TASK_ID})

    if backend.failed_payload:
        print("REAL_MODEL_TEST_FAILED")
        print(json.dumps({"result": result, "failed_payload": backend.failed_payload}, ensure_ascii=False, indent=2))
        raise SystemExit(1)

    if not backend.success_payload:
        print("REAL_MODEL_TEST_FAILED")
        print(json.dumps({"result": result, "message": "missing success payload"}, ensure_ascii=False, indent=2))
        raise SystemExit(1)

    content_json = backend.success_payload["contentJson"]
    print("REAL_MODEL_TEST_PASSED")
    print(
        json.dumps(
            {
                "result": result,
                "title_count": len(content_json["titles"]),
                "hashtag_count": len(content_json["hashtags"]),
                "first_title": content_json["titles"][0],
                "cta": content_json["cta"],
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
