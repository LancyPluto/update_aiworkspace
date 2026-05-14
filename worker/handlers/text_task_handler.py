import logging
from typing import Any

from client.backend_client import BackendClient, BackendClientError
from client.model_client import (
    ModelClient,
    ModelClientError,
    ModelGenerationResult,
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

            model_result = self._generate_model_result(
                user_prompt,
                system_prompt=system_prompt,
                provider=context.get("modelProviderCode"),
                model_name=context.get("modelName"),
                base_url=context.get("modelApiBaseUrl"),
                api_key=context.get("modelApiKey"),
                timeout_seconds=context.get("modelTimeoutSeconds"),
                max_tokens=context.get("modelMaxTokens"),
            )

            success_payload = build_success_payload(context, model_result.content)
            self._attach_token_usage(success_payload, model_result)
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
        model_config = normalized.get("modelConfig") or {}
        normalized.setdefault("outputFormat", "MARKDOWN")
        normalized["modelProviderCode"] = (
            normalized.get("modelProviderCode") or model_config.get("provider") or settings.model_provider
        )
        normalized["modelName"] = (
            normalized.get("modelName") or model_config.get("modelName") or self.model_client.default_model_name
        )
        normalized["modelApiBaseUrl"] = model_config.get("baseUrl") or settings.model_api_base_url
        normalized["modelApiKey"] = model_config.get("apiKey") or settings.model_api_key
        normalized["modelTimeoutSeconds"] = model_config.get("timeoutSeconds")
        normalized["modelMaxTokens"] = 1024
        return normalized

    def _generate_model_result(self, prompt: str, **kwargs: Any) -> ModelGenerationResult:
        generate_with_usage = getattr(self.model_client, "generate_with_usage", None)
        if callable(generate_with_usage):
            result = generate_with_usage(prompt, **kwargs)
            if isinstance(result, ModelGenerationResult):
                return result
            if isinstance(result, dict):
                return ModelGenerationResult(
                    content=str(result.get("content") or result.get("text") or ""),
                    prompt_tokens=self._non_negative_int(result.get("promptTokens") or result.get("prompt_tokens")),
                    completion_tokens=self._non_negative_int(
                        result.get("completionTokens") or result.get("completion_tokens")
                    ),
                )
            return ModelGenerationResult(content=str(result or ""))

        content = self.model_client.generate(prompt, **kwargs)
        return ModelGenerationResult(content=content)

    def _attach_token_usage(self, payload: dict[str, Any], result: ModelGenerationResult) -> None:
        if result.prompt_tokens > 0:
            payload["promptTokens"] = result.prompt_tokens
        if result.completion_tokens > 0:
            payload["completionTokens"] = result.completion_tokens

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

    @staticmethod
    def _non_negative_int(value: Any) -> int:
        try:
            return max(0, int(value or 0))
        except (TypeError, ValueError):
            return 0
