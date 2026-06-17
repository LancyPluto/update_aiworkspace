from __future__ import annotations

import logging
from typing import Any

from client.backend_client import BackendClient, BackendClientError
from client.kling_video_client import KlingVideoClient
from config import resolve_kling_api_key, resolve_kling_credentials
from subject.registry import require_subject_adapter

LOGGER = logging.getLogger(__name__)


class SubjectSyncHandler:
    def __init__(self, backend_client: BackendClient | None = None) -> None:
        self.backend_client = backend_client or BackendClient()

    def handle(self, message: dict[str, Any]) -> dict[str, Any]:
        subject_code = str(message.get("subjectCode") or "").strip()
        trace_id = message.get("traceId")
        if not subject_code:
            raise ValueError("subject sync message missing subjectCode")

        try:
            context = self.backend_client.get_subject_sync_context(subject_code, trace_id=trace_id)
            provider_code = str(context.get("providerCode") or "").strip()
            adapter = require_subject_adapter(provider_code)
            client = self._client(context)
            create_payload = adapter.build_create_payload(context, client)
            create_response = client.create_element(
                element_name=str(create_payload.get("element_name") or ""),
                element_description=str(create_payload.get("element_description") or ""),
                reference_type=str(create_payload.get("reference_type") or ""),
                element_image_list=create_payload.get("element_image_list"),
                element_video_list=create_payload.get("element_video_list"),
            )
            task_id = KlingVideoClient._extract_task_id(create_response)
            element_id = adapter.extract_element_id(create_response)
            if not element_id:
                element_id = client.wait_for_element(task_id)
            self.backend_client.report_subject_sync_result(
                subject_code,
                {
                    "syncStatus": "READY",
                    "syncTaskId": task_id,
                    "upstreamElementId": element_id,
                },
                trace_id=trace_id,
            )
            return {"status": "SUCCESS", "subjectCode": subject_code, "upstreamElementId": element_id}
        except Exception as exc:
            LOGGER.error(
                "subject sync failed subjectCode=%s error=%s",
                subject_code,
                exc,
                exc_info=True,
            )
            self._report_failure(subject_code, exc, trace_id)
            raise

    def _report_failure(
        self,
        subject_code: str,
        exc: Exception,
        trace_id: str | None,
    ) -> None:
        try:
            self.backend_client.report_subject_sync_result(
                subject_code,
                {
                    "syncStatus": "FAILED",
                    "syncError": str(exc)[:500],
                },
                trace_id=trace_id,
            )
        except BackendClientError:
            LOGGER.warning(
                "failed to report subject sync failure subjectCode=%s error=%s",
                subject_code,
                exc,
                exc_info=True,
            )

    @staticmethod
    def _client(context: dict[str, Any]) -> KlingVideoClient:
        model_config = {
            "baseUrl": context.get("baseUrl"),
            "apiKey": context.get("apiKey"),
            "extraAuthJson": context.get("extraAuthJson"),
        }
        access_key, secret_key = resolve_kling_credentials(model_config)
        return KlingVideoClient(
            base_url=model_config.get("baseUrl"),
            api_key=resolve_kling_api_key(model_config),
            access_key=access_key,
            secret_key=secret_key,
        )
