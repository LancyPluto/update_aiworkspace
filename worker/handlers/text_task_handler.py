import logging
from typing import Any

from client.backend_client import BackendClient, BackendClientError
from client.model_client import ModelClient, ModelClientError
from prompt.renderer import PromptRenderError, render_prompt


LOGGER = logging.getLogger(__name__)


class TextTaskHandler:
    def __init__(
        self,
        backend_client: BackendClient | None = None,
        model_client: ModelClient | None = None,
    ) -> None:
        self.backend_client = backend_client or BackendClient()
        self.model_client = model_client or ModelClient()

    def handle(self, message: dict[str, Any]) -> dict[str, Any]:
        task_id = int(message["taskId"])
        LOGGER.info("start processing task %s", task_id)

        try:
            context = self.backend_client.get_execution_context(task_id)
            self.backend_client.mark_processing(task_id)

            params = context.get("params") or {}
            user_prompt_template = context.get("userPromptTemplate")
            if user_prompt_template:
                user_prompt = render_prompt(user_prompt_template, params)
            else:
                user_prompt = self._build_default_prompt(params)

            generated_text = self.model_client.generate(
                user_prompt,
                system_prompt=context.get("systemPrompt", ""),
                model_name=context.get("modelName"),
            )

            success_payload = {
                "resourceType": context.get("outputFormat", "MARKDOWN"),
                "contentText": generated_text,
                "contentJson": None,
                "modelProviderCode": context.get("modelProviderCode"),
                "modelName": context.get("modelName"),
            }
            self.backend_client.mark_success(task_id, success_payload)
            LOGGER.info("task %s completed successfully", task_id)
            return {"status": "SUCCESS", "taskId": task_id}
        except PromptRenderError as exc:
            return self._mark_failed(
                task_id,
                error_code="PROMPT_VARIABLE_MISSING",
                error_message=str(exc),
            )
        except ModelClientError as exc:
            return self._mark_failed(
                task_id,
                error_code="MODEL_CALL_FAILED",
                error_message=str(exc),
            )
        except BackendClientError:
            raise
        except Exception as exc:
            return self._mark_failed(
                task_id,
                error_code="WORKER_INTERNAL_ERROR",
                error_message=str(exc),
            )

    def _mark_failed(self, task_id: int, *, error_code: str, error_message: str) -> dict[str, Any]:
        LOGGER.exception("task %s failed: %s", task_id, error_message)
        self.backend_client.mark_failed(
            task_id,
            {
                "errorCode": error_code,
                "errorMessage": error_message,
            },
        )
        return {"status": "FAILED", "taskId": task_id, "errorCode": error_code}

    @staticmethod
    def _build_default_prompt(params: dict[str, Any]) -> str:
        lines = ["Generate a result from the following user input:"]
        for key, value in params.items():
            lines.append(f"- {key}: {value}")
        return "\n".join(lines)
