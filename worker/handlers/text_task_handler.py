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

LONG_OUTPUT_MODEL_TOKENS = {
    "wechat_longform_generator": 4096,
    "product_detail_page_copywriter": 4096,
    "ecommerce_campaign_planner": 4096,
    "store_campaign_planner": 4096,
    "live_stream_script_generator": 4096,
    "customer_followup_script_generator": 3072,
    "objection_handling_script_generator": 3072,
    "short_video_script_generator": 3072,
    "short_video_topic_generator": 2048,
    "xiaohongshu_copywriting": 2048,
    "moments_copywriting_generator": 2048,
    "product_title_optimizer": 2048,
}

LONG_OUTPUT_TIMEOUT_SECONDS = {
    "wechat_longform_generator": 120,
    "product_detail_page_copywriter": 120,
    "ecommerce_campaign_planner": 120,
    "store_campaign_planner": 120,
    "live_stream_script_generator": 120,
}


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
        trace_id = message.get("traceId")
        LOGGER.info("start processing task %s traceId=%s", task_id, trace_id or "-")

        try:
            context = self._normalize_execution_context(
                message.get("__executionContext") or self.backend_client.get_execution_context(task_id, trace_id=trace_id)
            )
            trace_id = trace_id or context.get("traceId")
            context["traceId"] = trace_id
            self._report_progress(context, task_id, 12, "任务已开始，正在整理输入参数", trace_id=trace_id)

            system_prompt, user_prompt = self._build_model_prompts(context)
            self._report_progress(context, task_id, 28, "已生成企业诊断提示词，正在准备调用管理端大模型", trace_id=trace_id)
            system_prompt = apply_output_discipline(system_prompt)

            self._report_progress(context, task_id, 55, "正在调用管理端配置的大模型联网检索企业公开信息并分析经营情况", trace_id=trace_id)
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

            self._report_progress(context, task_id, 86, "企业诊断报告已生成，正在整理报告结构", trace_id=trace_id)
            success_payload = build_success_payload(context, model_result.content)
            self._attach_token_usage(success_payload, model_result)
            self._report_progress(context, task_id, 94, "正在保存企业诊断报告，准备生成结果页", trace_id=trace_id)
            self.backend_client.mark_success(task_id, success_payload, trace_id=trace_id)
            LOGGER.info("task %s completed successfully traceId=%s", task_id, trace_id or "-")
            return {"status": "SUCCESS", "taskId": task_id, "traceId": trace_id}
        except PromptRenderError as exc:
            return self._mark_failed(
                task_id,
                error_code="PROMPT_VARIABLE_MISSING",
                error_message=str(exc),
                trace_id=trace_id,
            )
        except ModelTimeoutError as exc:
            return self._mark_failed(
                task_id,
                error_code="MODEL_TIMEOUT",
                error_message=str(exc),
                trace_id=trace_id,
            )
        except ModelOutputEmptyError as exc:
            return self._mark_failed(
                task_id,
                error_code="MODEL_OUTPUT_EMPTY",
                error_message=str(exc),
                trace_id=trace_id,
            )
        except ToolResultBuildError as exc:
            return self._mark_failed(
                task_id,
                error_code="MODEL_OUTPUT_EMPTY",
                error_message=str(exc),
                trace_id=trace_id,
            )
        except ModelClientError as exc:
            return self._mark_failed(
                task_id,
                error_code="MODEL_CALL_FAILED",
                error_message=str(exc),
                trace_id=trace_id,
            )
        except BackendClientError:
            raise
        except Exception as exc:
            return self._mark_failed(
                task_id,
                error_code="WORKER_INTERNAL_ERROR",
                error_message=str(exc),
                trace_id=trace_id,
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
        tool_code = str(normalized.get("toolCode") or "")
        normalized["modelTimeoutSeconds"] = self._positive_int(
            model_config.get("timeoutSeconds") or model_config.get("timeout_seconds"),
            default=LONG_OUTPUT_TIMEOUT_SECONDS.get(tool_code),
        )
        normalized["modelMaxTokens"] = self._positive_int(
            model_config.get("maxTokens") or model_config.get("max_tokens"),
            default=LONG_OUTPUT_MODEL_TOKENS.get(tool_code, 2048),
        )
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

    def _mark_failed(self, task_id: int, *, error_code: str, error_message: str, trace_id: str | None = None) -> dict[str, Any]:
        LOGGER.exception("task %s failed traceId=%s errorCode=%s: %s", task_id, trace_id or "-", error_code, error_message)
        self.backend_client.mark_failed(
            task_id,
            {
                "errorCode": error_code,
                "errorMessage": error_message,
            },
            trace_id=trace_id,
        )
        return {"status": "FAILED", "taskId": task_id, "errorCode": error_code, "traceId": trace_id}

    def _report_progress(
        self,
        context: dict[str, Any],
        task_id: int,
        progress: int,
        message: str,
        trace_id: str | None = None,
    ) -> None:
        if context.get("toolCode") == "enterprise_diagnosis_agent":
            self.backend_client.mark_processing(
                task_id,
                progress=progress,
                progress_message=message,
                trace_id=trace_id,
            )
            return
        if progress == 12:
            self.backend_client.mark_processing(task_id, trace_id=trace_id)

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

    @staticmethod
    def _positive_int(value: Any, *, default: int | None = None) -> int | None:
        try:
            parsed = int(value)
        except (TypeError, ValueError):
            return default
        return parsed if parsed > 0 else default
