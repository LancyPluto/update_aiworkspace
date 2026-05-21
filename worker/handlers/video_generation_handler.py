import logging
from typing import Any

from client.backend_client import BackendClient, BackendClientError
from providers import registry as provider_registry
from providers.registry import ProviderRegistryError


LOGGER = logging.getLogger(__name__)
TERMINAL_TASK_STATUSES = {"SUCCESS", "FAILED", "CANCELLED"}


class VideoGenerationHandler:
  def __init__(self, backend_client: BackendClient | None = None) -> None:
    self.backend_client = backend_client or BackendClient()

  def handle(self, message: dict[str, Any]) -> dict[str, Any]:
    task_id = int(message["taskId"])
    trace_id = message.get("traceId")
    try:
      context = message.get("__executionContext") or self.backend_client.get_execution_context(task_id, trace_id=trace_id)
      trace_id = trace_id or context.get("traceId")
      status = str(context.get("status") or "").upper()
      if status in TERMINAL_TASK_STATUSES:
        LOGGER.info("skip terminal video task taskId=%s status=%s", task_id, status)
        return {"status": "SKIPPED", "taskId": task_id, "taskStatus": status, "traceId": trace_id}
      model_config = context.get("modelConfig") or {}
      provider = str(model_config.get("provider") or context.get("modelProviderCode") or "").lower()
      provider_registry.require_capability(provider, "VIDEO_GENERATION")
      provider_registry.require_worker_ready(provider)
      self.backend_client.mark_failed(
        task_id,
        error_code="VIDEO_GENERATION_NOT_CONFIGURED",
        error_message="视频生成 Handler 已接入路由，但供应商调用尚未在本环境完成配置。请在管理端绑定视频模型后重试。",
        trace_id=trace_id,
      )
      return {"status": "FAILED", "taskId": task_id, "traceId": trace_id}
    except BackendClientError:
      LOGGER.exception("video generation handler backend error taskId=%s", task_id)
      raise
    except ProviderRegistryError as exc:
      self.backend_client.mark_failed(
        task_id,
        error_code="MODEL_CALL_FAILED",
        error_message=str(exc),
        trace_id=trace_id,
      )
      return {"status": "FAILED", "taskId": task_id, "traceId": trace_id, "errorCode": "MODEL_CALL_FAILED"}
