import os
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


def test_listed_and_unlisted_public_domains_both_enter_project_mihomo() -> None:
    model_config = {
        "proxyPolicy": {
            "enabled": True,
            "projectProxyUrl": "http://mihomo:7890",
            "routingRules": [
                {
                    "id": "suno",
                    "patternType": "EXACT",
                    "pattern": "api.sunoapi.org",
                    "strategy": "PROXY",
                    "enabled": True,
                }
            ],
            "businessFallback": "DIRECT",
        }
    }

    with (
        patch.dict(
            os.environ,
            {
                "PROJECT_MIHOMO_PROXY_URL": "http://mihomo:7890",
                "NO_PROXY": "backend,.cn,api.deepseek.com",
                "HTTP_PROXY": "http://user:secret@host-proxy.example:3128",
                "HTTPS_PROXY": "http://user:secret@host-proxy.example:3128",
            },
            clear=True,
        ),
        patch("utils.outbound_http.requests.Session") as session_factory,
    ):
        session = session_factory.return_value
        session.request.return_value = FakeResponse()
        client = OutboundRequestsClient.from_model_config(model_config)

        client.get("https://api.sunoapi.org/api/v1/generate/record-info")
        client.get("https://unlisted-public.example/assets/result.mp4")
        client.get("https://api.deepseek.com/v1/chat/completions")
        client.get("https://example.cn/v1/status")

    expected = {"http": "http://mihomo:7890", "https": "http://mihomo:7890"}
    assert [call.kwargs["proxies"] for call in session.request.call_args_list] == [
        expected,
        expected,
        expected,
        expected,
    ]
    assert session.trust_env is False


def test_internal_hosts_and_private_addresses_bypass_project_mihomo() -> None:
    with (
        patch.dict(
            os.environ,
            {
                "PROJECT_MIHOMO_PROXY_URL": "http://mihomo:7890",
                "NO_PROXY": "backend,custom-service,10.20.30.40,api.public.example",
            },
            clear=True,
        ),
        patch("utils.outbound_http.requests.Session") as session_factory,
    ):
        session = session_factory.return_value
        session.request.return_value = FakeResponse()
        client = OutboundRequestsClient.from_model_config()

        client.get("http://backend:8080/internal/tasks")
        client.get("http://custom-service:9000/health")
        client.get("http://10.20.30.40:8080/file")
        client.get("http://[::1]:8080/health")
        client.get("https://api.public.example/v1")
        client.get("https://[2606:4700:4700::1111]/dns-query")

    assert [call.kwargs["proxies"] for call in session.request.call_args_list[:4]] == [{}, {}, {}, {}]
    assert session.request.call_args_list[4].kwargs["proxies"] == {
        "http": "http://mihomo:7890",
        "https": "http://mihomo:7890",
    }
    assert session.request.call_args_list[5].kwargs["proxies"] == {
        "http": "http://mihomo:7890",
        "https": "http://mihomo:7890",
    }


def test_model_and_request_proxy_urls_cannot_override_project_gateway() -> None:
    malicious_model_config = {
        "proxyPolicy": {
            "enabled": False,
            "proxyUrl": "http://attacker:password@evil.example:8080",
            "projectProxyUrl": "http://evil.example:7890",
            "routingEnabled": False,
            "routingRules": [],
        }
    }

    with (
        patch.dict(os.environ, {"PROJECT_MIHOMO_PROXY_URL": "http://mihomo:7890"}, clear=True),
        patch("utils.outbound_http.requests.Session") as session_factory,
    ):
        session = session_factory.return_value
        session.request.return_value = FakeResponse()
        client = OutboundRequestsClient.from_model_config(malicious_model_config)
        client.get(
            "https://api.example.com/v1",
            proxies={"https": "http://request-user:request-password@evil.example:3128"},
        )

    assert session.request.call_args.kwargs["proxies"] == {
        "http": "http://mihomo:7890",
        "https": "http://mihomo:7890",
    }


def test_non_project_gateway_environment_value_is_rejected() -> None:
    with patch.dict(
        os.environ,
        {"PROJECT_MIHOMO_PROXY_URL": "http://user:secret@external-proxy.example:7890"},
        clear=True,
    ):
        policy = resolve_outbound_proxy_policy()

    assert policy.enabled is False
    assert policy.proxy_url == ""
    assert policy.proxies == {}


def test_gateway_is_disabled_without_environment_even_if_model_snapshot_requests_it() -> None:
    with patch.dict(os.environ, {}, clear=True):
        policy = resolve_outbound_proxy_policy(
            {
                "proxyPolicy": {
                    "enabled": True,
                    "projectProxyUrl": "http://mihomo:7890",
                    "routingRules": [
                        {
                            "patternType": "EXACT",
                            "pattern": "api.sunoapi.org",
                            "strategy": "PROXY",
                            "enabled": True,
                        }
                    ],
                }
            }
        )

    assert policy.enabled is False
    assert policy.proxy_url == ""
    assert policy.proxies == {}


def test_local_host_proxy_requires_explicit_local_guard() -> None:
    with patch.dict(
        os.environ,
        {"PROJECT_MIHOMO_PROXY_URL": "http://host.docker.internal:3128"},
        clear=True,
    ):
        assert resolve_outbound_proxy_policy().enabled is False

    with patch.dict(
        os.environ,
        {
            "PROJECT_MIHOMO_PROXY_URL": "http://host.docker.internal:3128",
            "ALLOW_LOCAL_HOST_PROXY": "true",
        },
        clear=True,
    ):
        policy = resolve_outbound_proxy_policy()

    assert policy.enabled is True
    assert policy.proxy_url == "http://host.docker.internal:3128"


def test_local_host_proxy_rejects_credentials_and_external_hosts() -> None:
    for value in (
        "http://user:secret@host.docker.internal:3128",
        "http://proxy.example.com:3128",
        "https://host.docker.internal:3128",
    ):
        with patch.dict(
            os.environ,
            {"PROJECT_MIHOMO_PROXY_URL": value, "ALLOW_LOCAL_HOST_PROXY": "true"},
            clear=True,
        ):
            assert resolve_outbound_proxy_policy().enabled is False


def test_redirect_re_evaluates_public_and_internal_targets() -> None:
    with (
        patch.dict(os.environ, {"PROJECT_MIHOMO_PROXY_URL": "http://mihomo:7890"}, clear=True),
        patch("utils.outbound_http.requests.Session") as session_factory,
    ):
        session = session_factory.return_value
        session.request.return_value = FakeResponse()
        client = OutboundRequestsClient.from_model_config()
        client.get("https://public.example/start")

        internal_request = SimpleNamespace(
            url="http://backend:8080/generated/result.mp4",
            headers={"Proxy-Authorization": "Basic must-not-leak"},
        )
        public_request = SimpleNamespace(url="https://cdn.public.example/result.mp4", headers={})

        assert session.rebuild_proxies(internal_request, client.proxies) == {}
        assert "Proxy-Authorization" not in internal_request.headers
        assert session.rebuild_proxies(public_request, {}) == {
            "http": "http://mihomo:7890",
            "https": "http://mihomo:7890",
        }


def test_streaming_response_remains_open_until_client_is_closed() -> None:
    with (
        patch.dict(os.environ, {"PROJECT_MIHOMO_PROXY_URL": "http://mihomo:7890"}, clear=True),
        patch("utils.outbound_http.requests.Session") as session_factory,
    ):
        session = session_factory.return_value
        response = FakeResponse()
        session.request.return_value = response
        client = OutboundRequestsClient.from_model_config()

        returned = client.get("https://media.example.com/large.bin", stream=True)

        assert returned is response
        session.close.assert_not_called()
        client.close()
        session.close.assert_called_once()


def test_concurrent_public_requests_use_thread_local_sessions() -> None:
    barrier = threading.Barrier(2)
    sessions = []

    class FakeSession:
        def __init__(self) -> None:
            self.trust_env = True
            self.headers = {}
            self.calls = []
            sessions.append(self)

        def request(self, method, url, **kwargs):
            barrier.wait(timeout=2)
            self.calls.append((method, url, kwargs))
            return FakeResponse()

        def close(self) -> None:
            return None

    with (
        patch.dict(os.environ, {"PROJECT_MIHOMO_PROXY_URL": "http://mihomo:7890"}, clear=True),
        patch("utils.outbound_http.requests.Session", side_effect=FakeSession),
    ):
        client = OutboundRequestsClient.from_model_config()
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
    assert all(
        session.calls[0][2]["proxies"]
        == {"http": "http://mihomo:7890", "https": "http://mihomo:7890"}
        for session in sessions
    )
    assert all(session.trust_env is False for session in sessions)


def test_worker_does_not_install_upstream_socks_support() -> None:
    requirements = (WORKER_ROOT / "requirements.txt").read_text(encoding="utf-8").lower().splitlines()

    assert not any(line.strip().startswith("requests[socks]") for line in requirements)
