from __future__ import annotations

import unittest
from unittest.mock import MagicMock, patch

from client.backend_client import BackendClientError
from handlers.subject_sync_handler import SubjectSyncHandler


class SubjectSyncHandlerTest(unittest.TestCase):
    def test_reports_failed_when_sync_context_unauthorized(self) -> None:
        backend = MagicMock()
        backend.get_subject_sync_context.side_effect = BackendClientError(
            "backend request failed: status=401, body=UNAUTHORIZED"
        )
        handler = SubjectSyncHandler(backend_client=backend)

        with self.assertRaises(BackendClientError):
            handler.handle({"subjectCode": "subj001", "messageType": "subject_sync"})

        backend.report_subject_sync_result.assert_called_once_with(
            "subj001",
            {
                "syncStatus": "FAILED",
                "syncError": "backend request failed: status=401, body=UNAUTHORIZED",
            },
            trace_id=None,
        )

    @patch("handlers.subject_sync_handler.KlingVideoClient")
    @patch("handlers.subject_sync_handler.require_subject_adapter")
    def test_success_reports_ready(
        self,
        require_subject_adapter: MagicMock,
        kling_video_client: MagicMock,
    ) -> None:
        backend = MagicMock()
        backend.get_subject_sync_context.return_value = {
            "providerCode": "kling_video",
            "displayName": "Hero",
            "description": "Main",
            "referenceType": "image_refer",
            "referenceJson": {"frontalImage": "https://example.com/front.jpg"},
            "baseUrl": "https://api-beijing.klingai.com",
            "apiKey": "jwt-token",
            "extraAuthJson": None,
        }
        adapter = MagicMock()
        adapter.build_create_payload.return_value = {
            "element_name": "Hero",
            "element_description": "Main",
            "reference_type": "image_refer",
            "element_image_list": {
                "frontal_image": "encoded",
                "refer_images": [{"image_url": "encoded-side"}],
            },
        }
        adapter.extract_element_id.return_value = "elem_123"
        require_subject_adapter.return_value = adapter

        client = MagicMock()
        client.create_element.return_value = {"task_id": "task_1"}
        kling_video_client._extract_task_id.return_value = "task_1"
        kling_video_client.return_value = client

        handler = SubjectSyncHandler(backend_client=backend)
        result = handler.handle({"subjectCode": "subj002", "messageType": "subject_sync"})

        self.assertEqual(result["status"], "SUCCESS")
        self.assertEqual(result["upstreamElementId"], "elem_123")
        backend.report_subject_sync_result.assert_called_once_with(
            "subj002",
            {
                "syncStatus": "READY",
                "syncTaskId": "task_1",
                "upstreamElementId": "elem_123",
            },
            trace_id=None,
        )


if __name__ == "__main__":
    unittest.main()
