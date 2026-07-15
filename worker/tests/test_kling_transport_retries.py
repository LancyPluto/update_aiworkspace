from unittest.mock import MagicMock, patch

import requests

from client.kling_video_client import KlingVideoClient


def test_kling_request_retries_transient_ssl_eof() -> None:
    client = KlingVideoClient(api_key="test.jwt.token")
    assert client.session.trust_env is False
    response = MagicMock()
    response.raise_for_status.return_value = None
    response.json.return_value = {"code": 0, "data": {"task_id": "kling-task-1"}}
    client.session.request = MagicMock(
        side_effect=[requests.exceptions.SSLError("unexpected eof"), response]
    )

    with patch("client.kling_video_client.time.sleep") as sleep:
        result = client._request("POST", "/v1/videos/multi-image2video", {"image_list": ["large"]})

    assert result["data"]["task_id"] == "kling-task-1"
    assert client.session.request.call_count == 2
    sleep.assert_called_once()
