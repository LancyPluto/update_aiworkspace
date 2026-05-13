import logging
from typing import Any

from client.backend_client import BackendClient, BackendClientError
from client.model_client import (
    ModelClient,
    ModelClientError,
    ModelOutputEmptyError,
    ModelTimeoutError,
)
from config import settings
from prompt.renderer import PromptRenderError, render_prompt
from tools import ToolResultBuildError, build_success_payload
from tools.output_policy import apply_output_discipline
from tools.customer_followup_script_generator import (
    build_prompt_payload as build_customer_followup_script_prompt_payload,
)
from tools.live_stream_script_generator import (
    build_prompt_payload as build_live_stream_script_prompt_payload,
)
from tools.moments_copywriting_generator import (
    build_prompt_payload as build_moments_prompt_payload,
)
from tools.objection_handling_script_generator import (
    build_prompt_payload as build_objection_handling_script_prompt_payload,
)
from tools.product_detail_page_copywriter import (
    build_prompt_payload as build_product_detail_copywriter_prompt_payload,
)
from tools.product_title_optimizer import (
    build_prompt_payload as build_product_title_prompt_payload,
)
from tools.ecommerce_campaign_planner import (
    build_prompt_payload as build_ecommerce_campaign_prompt_payload,
)
from tools.short_video_script_generator import (
    build_prompt_payload as build_short_video_script_prompt_payload,
)
from tools.short_video_topic_generator import (
    build_prompt_payload as build_short_video_topic_prompt_payload,
)
from tools.store_campaign_planner import (
    build_prompt_payload as build_store_campaign_prompt_payload,
)
from tools.wechat_longform_generator import (
    build_prompt_payload as build_wechat_longform_prompt_payload,
)
from tools.xiaohongshu_copywriting import build_prompt_payload as build_xiaohongshu_prompt_payload


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
            context = self._normalize_execution_context(
                self.backend_client.get_execution_context(task_id)
            )
            self.backend_client.mark_processing(task_id)

            system_prompt, user_prompt = self._build_model_prompts(context)
            system_prompt = apply_output_discipline(system_prompt)

            generated_text = self.model_client.generate(
                user_prompt,
                system_prompt=system_prompt,
                model_name=context.get("modelName"),
            )

            success_payload = build_success_payload(context, generated_text)
            self.backend_client.mark_success(task_id, success_payload)
            LOGGER.info("task %s completed successfully", task_id)
            return {"status": "SUCCESS", "taskId": task_id}
        except PromptRenderError as exc:
            return self._mark_failed(
                task_id,
                error_code="PROMPT_VARIABLE_MISSING",
                error_message=str(exc),
            )
        except ModelTimeoutError as exc:
            return self._mark_failed(
                task_id,
                error_code="MODEL_TIMEOUT",
                error_message=str(exc),
            )
        except ModelOutputEmptyError as exc:
            return self._mark_failed(
                task_id,
                error_code="MODEL_OUTPUT_EMPTY",
                error_message=str(exc),
            )
        except ToolResultBuildError as exc:
            return self._mark_failed(
                task_id,
                error_code="MODEL_OUTPUT_EMPTY",
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

    def _build_model_prompts(self, context: dict[str, Any]) -> tuple[str, str]:
        tool_code = context.get("toolCode")
        generation_mode = str(context.get("generationMode") or "").upper()
        has_rewrite_context = bool(context.get("rewriteContext"))

        if tool_code == "xiaohongshu_copywriting":
            prompt_payload = build_xiaohongshu_prompt_payload(context)
            return prompt_payload["system_prompt"], prompt_payload["user_prompt"]

        if tool_code == "wechat_longform_generator":
            prompt_payload = build_wechat_longform_prompt_payload(context)
            return prompt_payload["system_prompt"], prompt_payload["user_prompt"]

        if tool_code == "moments_copywriting_generator":
            prompt_payload = build_moments_prompt_payload(context)
            return prompt_payload["system_prompt"], prompt_payload["user_prompt"]

        if tool_code == "product_title_optimizer":
            prompt_payload = build_product_title_prompt_payload(context)
            return prompt_payload["system_prompt"], prompt_payload["user_prompt"]

        if tool_code == "product_detail_page_copywriter":
            prompt_payload = build_product_detail_copywriter_prompt_payload(context)
            return prompt_payload["system_prompt"], prompt_payload["user_prompt"]

        if tool_code == "ecommerce_campaign_planner":
            prompt_payload = build_ecommerce_campaign_prompt_payload(context)
            return prompt_payload["system_prompt"], prompt_payload["user_prompt"]

        if tool_code == "short_video_script_generator":
            prompt_payload = build_short_video_script_prompt_payload(context)
            return prompt_payload["system_prompt"], prompt_payload["user_prompt"]

        if tool_code == "short_video_topic_generator":
            prompt_payload = build_short_video_topic_prompt_payload(context)
            return prompt_payload["system_prompt"], prompt_payload["user_prompt"]

        if tool_code == "live_stream_script_generator":
            prompt_payload = build_live_stream_script_prompt_payload(context)
            return prompt_payload["system_prompt"], prompt_payload["user_prompt"]

        if tool_code == "customer_followup_script_generator":
            prompt_payload = build_customer_followup_script_prompt_payload(context)
            return prompt_payload["system_prompt"], prompt_payload["user_prompt"]

        if tool_code == "objection_handling_script_generator":
            prompt_payload = build_objection_handling_script_prompt_payload(context)
            return prompt_payload["system_prompt"], prompt_payload["user_prompt"]

        if tool_code == "store_campaign_planner":
            prompt_payload = build_store_campaign_prompt_payload(context)
            return prompt_payload["system_prompt"], prompt_payload["user_prompt"]

        params = context.get("params") or {}
        user_prompt_template = context.get("userPromptTemplate")
        if user_prompt_template:
            user_prompt = render_prompt(user_prompt_template, params)
        else:
            user_prompt = self._build_default_prompt(params)
        return context.get("systemPrompt", ""), user_prompt

    def _normalize_execution_context(self, context: dict[str, Any]) -> dict[str, Any]:
        normalized = dict(context)
        normalized.setdefault("outputFormat", "MARKDOWN")
        normalized.setdefault("modelProviderCode", settings.model_provider)
        normalized.setdefault("modelName", self.model_client.default_model_name)
        return normalized

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
