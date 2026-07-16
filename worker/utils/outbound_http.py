from __future__ import annotations

import threading
from dataclasses import dataclass
from typing import Any, Mapping
from urllib.parse import urlparse

import requests
from requests import PreparedRequest, Request


DEFAULT_NO_PROXY_HOSTS = {"localhost", "127.0.0.1", "::1", "0.0.0.0", "backend", "host.docker.internal"}
PROJECT_MIHOMO_PROXY_URL = "http://mihomo:7890"


@dataclass(frozen=True)
class OutboundProxyPolicy:
    enabled: bool = False
    proxy_url: str = ""
    trust_env: bool = False
    no_proxy_hosts: frozenset[str] = frozenset(DEFAULT_NO_PROXY_HOSTS)
    routing_rules: tuple[Mapping[str, Any], ...] = ()
    routing_enabled: bool = True

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
        return not hostname or any(_host_matches(hostname, host) for host in self.policy.no_proxy_hosts)

    def _should_proxy(self, url: str) -> bool:
        if not self.policy.routing_enabled or not self.policy.enabled or self._should_bypass_proxy(url):
            return False
        hostname = (urlparse(url).hostname or "").lower()
        rule = _matching_rule(hostname, self.policy.routing_rules)
        if rule is None:
            return False
        return str(rule.get("strategy") or "").strip().upper() in {"PROXY", "AUTO"}


def resolve_outbound_proxy_policy(model_config: dict[str, Any] | None = None, *, extra_auth_json: str | None = None) -> OutboundProxyPolicy:
    config = model_config or {}
    policy = config.get("proxyPolicy") if isinstance(config.get("proxyPolicy"), dict) else None
    if policy is not None:
        proxy_url = str(policy.get("projectProxyUrl") or policy.get("proxyUrl") or "").strip()
        if not _is_project_mihomo_url(proxy_url):
            proxy_url = ""
        hosts = _normalize_hosts(policy.get("noProxyHosts"))
        rules = _normalize_routing_rules(policy.get("routingRules"))
        routing_enabled = _as_bool(policy.get("routingEnabled"), True)
        return OutboundProxyPolicy(
            enabled=bool(proxy_url) and bool(rules),
            proxy_url=proxy_url,
            trust_env=False,
            no_proxy_hosts=hosts,
            routing_rules=rules,
            routing_enabled=routing_enabled,
        )

    # Legacy model proxyUrl and process HTTP_PROXY values are deliberately ignored.
    # Upstream proxy credentials belong only to Mihomo, never to Worker requests.
    return OutboundProxyPolicy()


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


def _normalize_routing_rules(value: Any) -> tuple[Mapping[str, Any], ...]:
    if not isinstance(value, list):
        return ()
    rules = [item for item in value if isinstance(item, dict) and _as_bool(item.get("enabled"), True)]
    rules.sort(
        key=lambda item: (
            _pattern_rank(str(item.get("patternType") or "")),
            -len(_pattern_base(str(item.get("pattern") or ""))),
            -int(item.get("priority") or 0),
            str(item.get("pattern") or "").lower(),
            str(item.get("id") or ""),
        )
    )
    return tuple(rules)


def _matching_rule(hostname: str, rules: tuple[Mapping[str, Any], ...]) -> Mapping[str, Any] | None:
    for rule in rules:
        pattern_type = str(rule.get("patternType") or "").strip().upper()
        pattern = str(rule.get("pattern") or "").strip().lower()
        base = pattern[2:] if pattern.startswith("*.") else pattern.lstrip(".")
        if pattern_type == "EXACT" and hostname == base:
            return rule
        if pattern_type == "SUFFIX" and (hostname == base or hostname.endswith("." + base)):
            return rule
        if pattern_type == "WILDCARD" and hostname.endswith("." + base) and hostname != base:
            return rule
    return None


def _host_matches(hostname: str, pattern: str) -> bool:
    base = str(pattern or "").strip().lower().lstrip(".")
    if base.startswith("*."):
        base = base[2:]
    return bool(base) and (hostname == base or hostname.endswith("." + base))


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


def _pattern_rank(pattern_type: str) -> int:
    normalized = pattern_type.strip().upper()
    return 0 if normalized == "EXACT" else 1 if normalized in {"SUFFIX", "WILDCARD"} else 2


def _pattern_base(pattern: str) -> str:
    value = pattern.strip().lower()
    return value[2:] if value.startswith("*.") else value.lstrip(".")
