from __future__ import annotations

import json
import os
from dataclasses import dataclass
from typing import Any
from urllib.parse import urlparse

import requests
from requests import PreparedRequest, Request


DEFAULT_NO_PROXY_HOSTS = {"localhost", "127.0.0.1", "::1", "0.0.0.0", "backend", "host.docker.internal"}
PLATFORM_MEDIA_HOSTS = {"cdn.wlcloudai.com"}


@dataclass(frozen=True)
class OutboundProxyPolicy:
    enabled: bool = False
    proxy_url: str = ""
    trust_env: bool = False
    no_proxy_hosts: frozenset[str] = frozenset(DEFAULT_NO_PROXY_HOSTS)

    @property
    def proxies(self) -> dict[str, str]:
        if not self.enabled or not self.proxy_url:
            return {}
        return {"http": self.proxy_url, "https": self.proxy_url}


class OutboundRequestsClient:
    def __init__(self, policy: OutboundProxyPolicy | None = None) -> None:
        self.policy = policy or OutboundProxyPolicy()
        self.headers: dict[str, str] = {}
        self.trust_env = self.policy.trust_env
        self.proxies = self.policy.proxies

    @classmethod
    def from_model_config(cls, model_config: dict[str, Any] | None = None, *, extra_auth_json: str | None = None) -> "OutboundRequestsClient":
        return cls(resolve_outbound_proxy_policy(model_config, extra_auth_json=extra_auth_json))

    @property
    def proxy_url(self) -> str:
        return self.policy.proxy_url if self.policy.enabled else ""

    def request(self, method: str, url: str, **kwargs: Any) -> requests.Response:
        with requests.Session() as session:
            session.trust_env = self.policy.trust_env and not self._should_bypass_proxy(url)
            session.headers.update(self.headers)
            if self.policy.enabled and self.policy.proxy_url and not self._should_bypass_proxy(url):
                session.proxies.update(self.policy.proxies)
            return session.request(method, url, **kwargs)

    def get(self, url: str, **kwargs: Any) -> requests.Response:
        return self.request("GET", url, **kwargs)

    def post(self, url: str, **kwargs: Any) -> requests.Response:
        return self.request("POST", url, **kwargs)

    def prepare_request(self, request: Request) -> PreparedRequest:
        with requests.Session() as session:
            session.headers.update(self.headers)
            return session.prepare_request(request)

    def _should_bypass_proxy(self, url: str) -> bool:
        hostname = (urlparse(url).hostname or "").lower()
        return not hostname or hostname in self.policy.no_proxy_hosts or hostname in PLATFORM_MEDIA_HOSTS


def resolve_outbound_proxy_policy(model_config: dict[str, Any] | None = None, *, extra_auth_json: str | None = None) -> OutboundProxyPolicy:
    config = model_config or {}
    policy = config.get("proxyPolicy") if isinstance(config.get("proxyPolicy"), dict) else None
    if policy is not None:
        enabled = _as_bool(policy.get("enabled"), False)
        proxy_url = str(policy.get("proxyUrl") or "").strip()
        hosts = _normalize_hosts(policy.get("noProxyHosts"))
        return OutboundProxyPolicy(enabled=enabled and bool(proxy_url), proxy_url=proxy_url, trust_env=False, no_proxy_hosts=hosts)

    extra_auth = _parse_json_object(extra_auth_json if extra_auth_json is not None else config.get("extraAuthJson"))
    proxy_url = str(extra_auth.get("proxyUrl") or "").strip()
    trust_env = _as_bool(extra_auth.get("trustEnv"), False)
    if proxy_url:
        return OutboundProxyPolicy(enabled=True, proxy_url=proxy_url, trust_env=False)
    if "trustEnv" in extra_auth:
        return OutboundProxyPolicy(enabled=False, trust_env=trust_env)

    env_http_proxy = (os.environ.get("HTTP_PROXY") or os.environ.get("http_proxy") or "").strip()
    env_https_proxy = (os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy") or "").strip()
    env_proxy = env_http_proxy or env_https_proxy
    if env_proxy:
        return OutboundProxyPolicy(enabled=True, proxy_url=env_proxy, trust_env=False)
    return OutboundProxyPolicy()


def _parse_json_object(raw: Any) -> dict[str, Any]:
    if raw is None or not str(raw).strip():
        return {}
    try:
        data = json.loads(str(raw))
    except json.JSONDecodeError:
        return {}
    return data if isinstance(data, dict) else {}


def _as_bool(value: Any, default: bool = False) -> bool:
    if isinstance(value, bool):
        return value
    if value is None:
        return default
    text = str(value).strip().lower()
    if not text:
        return default
    return text in {"1", "true", "yes", "on"}


def _normalize_hosts(value: Any) -> frozenset[str]:
    if isinstance(value, list):
        hosts = [str(item).strip().lower() for item in value]
    else:
        hosts = [item.strip().lower() for item in str(value or "").split(",")]
    filtered = {item for item in hosts if item}
    return frozenset(filtered or DEFAULT_NO_PROXY_HOSTS)
