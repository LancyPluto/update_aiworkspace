from unittest.mock import MagicMock, patch

import requests

import pytest

from client.kling_video_client import KlingVideoClient, KlingVideoError
from client.provider_error import structured_failure_payload
from utils.outbound_http import OutboundRequestsClient


def test_kling_request_does_not_retry_ambiguous_ssl_eof(monkeypatch) -> None:
    monkeypatch.setenv("PROJECT_MIHOMO_PROXY_URL", "http://mihomo:7890")
    client = KlingVideoClient(api_key="test.jwt.token")
    assert isinstance(client.session, OutboundRequestsClient)
    assert client.session.trust_env is False
    assert client.session.proxies == {
        "http": "http://mihomo:7890",
        "https": "http://mihomo:7890",
    }
    response = MagicMock()
    response.raise_for_status.return_value = None
    response.json.return_value = {"code": 0, "data": {"task_id": "kling-task-1"}}
    client.session.request = MagicMock(
        side_effect=[requests.exceptions.SSLError("unexpected eof"), response]
    )

    with patch("client.kling_video_client.time.sleep") as sleep:
        with pytest.raises(KlingVideoError) as raised:
            client._request("POST", "/v1/videos/multi-image2video", {"image_list": ["large"]})

    assert structured_failure_payload(raised.value)["deliveryState"] == "UNKNOWN"
    assert client.session.request.call_count == 1
    sleep.assert_not_called()
