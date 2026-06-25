import sys
from pathlib import Path
from unittest.mock import patch


WORKER_ROOT = Path(__file__).resolve().parents[1]
if str(WORKER_ROOT) not in sys.path:
    sys.path.insert(0, str(WORKER_ROOT))

from utils.outbound_http import OutboundRequestsClient


class FakeResponse:
    status_code = 200


def test_outbound_client_applies_proxy_policy_for_external_host():
    client = OutboundRequestsClient.from_model_config(
        {
            "proxyPolicy": {
                "enabled": True,
                "proxyUrl": "http://127.0.0.1:7890",
                "noProxyHosts": ["localhost", "backend"],
            }
        }
    )

    with patch("utils.outbound_http.requests.Session") as session_factory:
        session = session_factory.return_value.__enter__.return_value
        session.proxies = {}
        session.request.return_value = FakeResponse()

        client.get("https://api.sunoapi.org/api/v1/generate")

    assert session.trust_env is False
    assert session.proxies["https"] == "http://127.0.0.1:7890"


def test_outbound_client_bypasses_proxy_for_no_proxy_hosts():
    client = OutboundRequestsClient.from_model_config(
        {
            "proxyPolicy": {
                "enabled": True,
                "proxyUrl": "http://127.0.0.1:7890",
                "noProxyHosts": ["backend"],
            }
        }
    )

    with patch("utils.outbound_http.requests.Session") as session_factory:
        session = session_factory.return_value.__enter__.return_value
        session.proxies = {}
        session.request.return_value = FakeResponse()

        client.get("http://backend:8080/generated/audio/ref.mp3")

    assert session.trust_env is False
    assert session.proxies == {}


def test_outbound_client_bypasses_proxy_for_ipv6_loopback_by_default():
    client = OutboundRequestsClient.from_model_config(
        {
            "proxyPolicy": {
                "enabled": True,
                "proxyUrl": "http://127.0.0.1:7890",
            }
        }
    )

    with patch("utils.outbound_http.requests.Session") as session_factory:
        session = session_factory.return_value.__enter__.return_value
        session.proxies = {}
        session.request.return_value = FakeResponse()

        client.get("http://[::1]:8080/generated/audio/ref.mp3")

    assert session.trust_env is False
    assert session.proxies == {}
