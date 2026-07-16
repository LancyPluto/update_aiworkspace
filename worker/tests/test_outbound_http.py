import sys
import threading
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


WORKER_ROOT = Path(__file__).resolve().parents[1]
if str(WORKER_ROOT) not in sys.path:
    sys.path.insert(0, str(WORKER_ROOT))

from utils.outbound_http import OutboundRequestsClient, resolve_outbound_proxy_policy


class FakeResponse:
    status_code = 200


def test_outbound_client_routes_each_request_by_target_hostname():
    client = OutboundRequestsClient.from_model_config(
        {
            "proxyPolicy": {
                "enabled": True,
                "proxyUrl": "http://mihomo:7890",
                "noProxyHosts": ["localhost", "backend"],
                "routingRules": [
                    {
                        "id": "ofox",
                        "patternType": "EXACT",
                        "pattern": "api.ofox.ai",
                        "strategy": "PROXY",
                        "priority": 100,
                        "enabled": True,
                    }
                ],
                "businessFallback": "DIRECT",
            }
        }
    )

    with patch("utils.outbound_http.requests.Session") as session_factory:
        session = session_factory.return_value
        session.proxies = {}
        session.request.return_value = FakeResponse()

        client.get("https://api.ofox.ai/v1/images/edits")
        assert session.trust_env is False
        assert session.request.call_args.kwargs["proxies"]["https"] == "http://mihomo:7890"
        client.get("https://storage.example.net/images/result.png")
        assert session.trust_env is False
        assert session.request.call_args.kwargs["proxies"] == {}
        assert session.proxies == {}


def test_outbound_client_bypasses_proxy_for_no_proxy_hosts():
    client = OutboundRequestsClient.from_model_config(
        {
            "proxyPolicy": {
                "enabled": True,
                "proxyUrl": "http://mihomo:7890",
                "noProxyHosts": ["backend"],
                "routingRules": [{"id": "all", "patternType": "SUFFIX", "pattern": "example.com", "strategy": "PROXY", "priority": 1, "enabled": True}],
            }
        }
    )

    with patch("utils.outbound_http.requests.Session") as session_factory:
        session = session_factory.return_value
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
                "proxyUrl": "http://mihomo:7890",
            }
        }
    )

    with patch("utils.outbound_http.requests.Session") as session_factory:
        session = session_factory.return_value
        session.proxies = {}
        session.request.return_value = FakeResponse()

        client.get("http://[::1]:8080/generated/audio/ref.mp3")

    assert session.trust_env is False
    assert session.proxies == {}


def test_outbound_client_rejects_legacy_upstream_socks_snapshot():
    client = OutboundRequestsClient.from_model_config(
        {
            "proxyPolicy": {
                "enabled": True,
                "proxyUrl": "socks5://proxy.example:1080",
                "noProxyHosts": ["localhost", "backend"],
            }
        }
    )

    with patch("utils.outbound_http.requests.Session") as session_factory:
        session = session_factory.return_value
        session.proxies = {}
        session.request.return_value = FakeResponse()

        client.get("https://api.ofox.ai/v1/images/edits")

    assert session.trust_env is False
    assert session.proxies == {}


def test_worker_does_not_install_upstream_socks_support():
    requirements = (WORKER_ROOT / "requirements.txt").read_text(encoding="utf-8").lower().splitlines()

    assert not any(line.strip().startswith("requests[socks]") for line in requirements)


def test_wildcard_excludes_root_and_longer_more_specific_rule_wins():
    policy = resolve_outbound_proxy_policy(
        {
            "proxyPolicy": {
                "projectProxyUrl": "http://mihomo:7890",
                "routingRules": [
                    {"id": "root", "patternType": "SUFFIX", "pattern": "example.com", "strategy": "PROXY", "priority": 900, "enabled": True},
                    {"id": "long", "patternType": "SUFFIX", "pattern": "api.example.com", "strategy": "DIRECT", "priority": 1, "enabled": True},
                    {"id": "broad-wild", "patternType": "WILDCARD", "pattern": "*.example.org", "strategy": "PROXY", "priority": 900, "enabled": True},
                    {"id": "specific-suffix", "patternType": "SUFFIX", "pattern": "api.example.org", "strategy": "DIRECT", "priority": 1, "enabled": True},
                    {"id": "wild", "patternType": "WILDCARD", "pattern": "*.media.example.net", "strategy": "PROXY", "priority": 500, "enabled": True},
                ],
            }
        }
    )
    client = OutboundRequestsClient(policy)

    assert client._should_proxy("https://api.example.com/v1") is False
    assert client._should_proxy("https://sub.api.example.org/v1") is False
    assert client._should_proxy("https://example.com/") is True
    assert client._should_proxy("https://media.example.net/") is False
    assert client._should_proxy("https://cdn.media.example.net/file") is True


def test_streaming_response_remains_open_until_client_is_closed():
    client = OutboundRequestsClient.from_model_config(
        {
            "proxyPolicy": {
                "projectProxyUrl": "http://mihomo:7890",
                "routingRules": [
                    {"id": "media", "patternType": "SUFFIX", "pattern": "media.example.com", "strategy": "PROXY", "priority": 1, "enabled": True}
                ],
            }
        }
    )

    with patch("utils.outbound_http.requests.Session") as session_factory:
        session = session_factory.return_value
        response = FakeResponse()
        session.request.return_value = response
        fresh_client = OutboundRequestsClient(client.policy)

        returned = fresh_client.get("https://media.example.com/large.bin", stream=True)

        assert returned is response
        session.close.assert_not_called()
        fresh_client.close()
        session.close.assert_called_once()


def test_redirect_reselects_proxy_for_each_target_hostname():
    client = OutboundRequestsClient.from_model_config(
        {
            "proxyPolicy": {
                "projectProxyUrl": "http://mihomo:7890",
                "routingRules": [
                    {"id": "api", "patternType": "EXACT", "pattern": "api.example.com", "strategy": "PROXY", "priority": 1, "enabled": True}
                ],
            }
        }
    )

    with patch("utils.outbound_http.requests.Session") as session_factory:
        session = session_factory.return_value
        session.proxies = {}
        session.request.return_value = FakeResponse()

        client.get("https://api.example.com/start")

        assert session.rebuild_proxies(SimpleNamespace(url="https://cdn.example.net/result", headers={}), {}) == {}
        assert session.rebuild_proxies(SimpleNamespace(url="https://api.example.com/next", headers={}), {}) == {
            "http": "http://mihomo:7890",
            "https": "http://mihomo:7890",
        }


def test_concurrent_requests_use_thread_local_sessions_and_request_local_proxies():
    client = OutboundRequestsClient.from_model_config(
        {
            "proxyPolicy": {
                "projectProxyUrl": "http://mihomo:7890",
                "routingRules": [
                    {"id": "api", "patternType": "EXACT", "pattern": "api.example.com", "strategy": "PROXY", "priority": 1, "enabled": True}
                ],
            }
        }
    )
    barrier = threading.Barrier(2)
    sessions = []

    class FakeSession:
        def __init__(self):
            self.trust_env = True
            self.proxies = {}
            self.headers = {}
            self.calls = []
            self.closed = False
            sessions.append(self)

        def request(self, method, url, **kwargs):
            barrier.wait(timeout=2)
            self.calls.append((method, url, kwargs))
            return FakeResponse()

        def close(self):
            self.closed = True

    with patch("utils.outbound_http.requests.Session", side_effect=FakeSession):
        threads = [
            threading.Thread(target=client.get, args=("https://api.example.com/start",)),
            threading.Thread(target=client.get, args=("https://cdn.example.net/result",)),
        ]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=3)

    assert all(not thread.is_alive() for thread in threads)
    assert len(sessions) == 2
    calls = {session.calls[0][1]: session.calls[0][2]["proxies"] for session in sessions}
    assert calls["https://api.example.com/start"]["https"] == "http://mihomo:7890"
    assert calls["https://cdn.example.net/result"] == {}
    assert all(session.proxies == {} for session in sessions)
