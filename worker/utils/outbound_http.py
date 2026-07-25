from __future__ import annotations

import ipaddress
import os
import threading
from dataclasses import dataclass
from typing import Any
from urllib.parse import urlparse

import requests
from requests import PreparedRequest, Request


DEFAULT_NO_PROXY_HOSTS = frozenset(
    {
        "localhost",
        "127.0.0.1",
        "::1",
        "0.0.0.0",
        "mysql",
        "redis",
        "rabbitmq",
        "backend",
        "agent-service",
        "admin-frontend",
        "user-web",
        "nginx",
        "mihomo",
        "host.docker.internal",
    }
)
PROJECT_MIHOMO_PROXY_URL = "http://mihomo:7890"


@dataclass(frozen=True)
class OutboundProxyPolicy:
    enabled: bool = False
    proxy_url: str = ""
    trust_env: bool = False
    no_proxy_hosts: frozenset[str] = DEFAULT_NO_PROXY_HOSTS

    @property
    def proxies(self) -> dict[str, str]:
        if not self.enabled or not self.proxy_url:
            return {}
        return {"http": self.proxy_url, "https": self.proxy_url}


class OutboundRequestsClient:
    def __init__(self, policy: OutboundProxyPolicy | None = None) -> None:
        self.policy = policy if policy is not None else resolve_outbound_proxy_policy()
        self.headers: dict[str, str] = {}
        self.trust_env = self.policy.trust_env
        self.proxies = self.policy.proxies
        self._local = threading.local()
        self._sessions: list[requests.Session] = []
        self._sessions_lock = threading.Lock()
        self._generation = 0

    @classmethod
    def from_model_config(cls, model_config: dict[str, Any] | None = None, *, extra_auth_json: str | None = None) -> "OutboundRequestsClient":
        return cls(resolve_outbound_proxy_policy(model_config, extra_auth_json=extra_auth_json))

    @property
    def proxy_url(self) -> str:
        return self.policy.proxy_url if self.policy.enabled else ""

    def request(self, method: str, url: str, **kwargs: Any) -> requests.Response:
        session = self._get_session()
        session.trust_env = False
        session.headers.update(self.headers)
        kwargs["proxies"] = self._proxies_for_url(url)
        return session.request(method, url, **kwargs)

    def get(self, url: str, **kwargs: Any) -> requests.Response:
        return self.request("GET", url, **kwargs)

    def post(self, url: str, **kwargs: Any) -> requests.Response:
        return self.request("POST", url, **kwargs)

    def prepare_request(self, request: Request) -> PreparedRequest:
        session = self._get_session()
        session.headers.update(self.headers)
        return session.prepare_request(request)

    def close(self) -> None:
        with self._sessions_lock:
            sessions = self._sessions
            self._sessions = []
            self._generation += 1
        for session in sessions:
            session.close()

    def _get_session(self) -> requests.Session:
        generation = self._generation
        session = getattr(self._local, "session", None)
        if session is not None and getattr(self._local, "generation", -1) == generation:
            return session

        session = requests.Session()
        session.trust_env = False

        # requests calls rebuild_proxies for each redirect. Recompute from the
        # redirected URL so a proxied API cannot drag unrelated CDN traffic
        # through the same route (or vice versa).
        def rebuild_proxies(prepared_request: PreparedRequest, _proxies: dict[str, str]) -> dict[str, str]:
            prepared_request.headers.pop("Proxy-Authorization", None)
            return self._proxies_for_url(prepared_request.url)

        session.rebuild_proxies = rebuild_proxies  # type: ignore[method-assign]
        with self._sessions_lock:
            self._sessions.append(session)
            generation = self._generation
        self._local.session = session
        self._local.generation = generation
        return session

    def _proxies_for_url(self, url: str) -> dict[str, str]:
        return dict(self.policy.proxies) if self._should_proxy(url) else {}

    def _should_bypass_proxy(self, url: str) -> bool:
        hostname = (urlparse(url).hostname or "").lower()
        return (
            not hostname
            or _is_internal_hostname(hostname)
            or any(_host_matches(hostname, host) for host in self.policy.no_proxy_hosts)
        )

    def _should_proxy(self, url: str) -> bool:
        parsed = urlparse(url)
        return (
            self.policy.enabled
            and parsed.scheme.lower() in {"http", "https"}
            and bool(parsed.hostname)
            and not self._should_bypass_proxy(url)
        )


def resolve_outbound_proxy_policy(model_config: dict[str, Any] | None = None, *, extra_auth_json: str | None = None) -> OutboundProxyPolicy:
    # Kept for call-site compatibility. Business snapshots and auth metadata carry
    # no routing authority; only the project container environment enables Mihomo.
    del model_config, extra_auth_json
    configured_url = os.getenv("PROJECT_MIHOMO_PROXY_URL", "").strip()
    proxy_url = configured_url if _is_allowed_project_proxy_url(configured_url) else ""
    return OutboundProxyPolicy(
        enabled=bool(proxy_url),
        proxy_url=proxy_url,
        trust_env=False,
        no_proxy_hosts=_internal_no_proxy_hosts(),
    )


def _internal_no_proxy_hosts() -> frozenset[str]:
    hosts = set(DEFAULT_NO_PROXY_HOSTS)
    for env_name in ("NO_PROXY", "no_proxy"):
        for raw_host in os.getenv(env_name, "").split(","):
            host = _normalize_no_proxy_host(raw_host)
            if host and _is_internal_hostname(host):
                hosts.add(host)
    return frozenset(hosts)


def _normalize_no_proxy_host(value: str) -> str:
    host = value.strip().lower().lstrip(".")
    if host.startswith("*."):
        host = host[2:]
    if host.startswith("[") and "]" in host:
        return host[1 : host.index("]")]
    if host.count(":") == 1:
        candidate, port = host.rsplit(":", 1)
        if port.isdigit():
            host = candidate
    return host


def _is_internal_hostname(hostname: str) -> bool:
    hostname = hostname.strip().lower().rstrip(".")
    if not hostname:
        return False
    if hostname in DEFAULT_NO_PROXY_HOSTS:
        return True
    try:
        address = ipaddress.ip_address(hostname.split("%", 1)[0])
    except ValueError:
        return "." not in hostname or hostname.endswith((".localhost", ".local", ".internal"))
    return not address.is_global


def _host_matches(hostname: str, pattern: str) -> bool:
    base = str(pattern or "").strip().lower().lstrip(".")
    if base.startswith("*."):
        base = base[2:]
    if not base or hostname == base:
        return bool(base)
    # Single-label Docker service names and literal IPs are exact matches. This
    # prevents a legacy NO_PROXY entry such as `.cn` from bypassing all *.cn
    # public traffic after normalization.
    try:
        ipaddress.ip_address(base.split("%", 1)[0])
        return False
    except ValueError:
        return "." in base and hostname.endswith("." + base)


def _is_project_mihomo_url(value: str) -> bool:
    try:
        parsed = urlparse(value)
        return (
            parsed.scheme == "http"
            and parsed.hostname == "mihomo"
            and parsed.port == 7890
            and parsed.username is None
            and parsed.password is None
            and parsed.path in {"", "/"}
            and not parsed.query
            and not parsed.fragment
        )
    except ValueError:
        return False


def _is_allowed_project_proxy_url(value: str) -> bool:
    if _is_project_mihomo_url(value):
        return True
    if os.getenv("ALLOW_LOCAL_HOST_PROXY", "").strip().lower() not in {"1", "true", "yes", "on"}:
        return False
    try:
        parsed = urlparse(value)
        return (
            parsed.scheme == "http"
            and parsed.hostname == "host.docker.internal"
            and parsed.port is not None
            and 1 <= parsed.port <= 65535
            and parsed.username is None
            and parsed.password is None
            and parsed.path in {"", "/"}
            and not parsed.query
            and not parsed.fragment
        )
    except ValueError:
        return False
