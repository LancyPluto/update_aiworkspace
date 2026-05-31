import json
import logging
import math
import socket
import time
from typing import Any
from urllib.parse import urlparse

import requests
from requests.exceptions import ConnectTimeout, ProxyError, ReadTimeout, RequestException, SSLError, Timeout


LOGGER = logging.getLogger(__name__)


class OpenAIImagesError(RuntimeError):
    pass


class OpenAIImagesTimeoutError(OpenAIImagesError):
    pass


class OpenAIImagesClient:
    def __init__(
        self,
        *,
        base_url: str | None = None,
        api_key: str | None = None,
        endpoint_path: str | None = None,
        timeout_seconds: int | None = None,
        extra_auth_json: str | None = None,
    ) -> None:
        self.base_url = (base_url or "").rstrip("/")
        self.api_key = (api_key or "").strip()
        self.extra_auth = self._parse_json(extra_auth_json)
        self.timeout = self._resolve_timeout(timeout_seconds)
        self.endpoint_path = str(endpoint_path or self.extra_auth.get("endpointPath") or "/images/generations")
        self.ssl_eof_retries = self._resolve_ssl_eof_retries()
        self.last_usage: dict[str, int] = {}
        self.session = requests.Session()
        self.session.trust_env = _as_bool(self.extra_auth.get("trustEnv"), True)
        self.session.headers.update(
            {
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.api_key}",
                "Connection": "close",
            }
        )
        proxy_url = str(self.extra_auth.get("proxyUrl") or "").strip()
        if proxy_url:
            self.session.proxies.update({"http": proxy_url, "https": proxy_url})

    def generate_images(
        self,
        *,
        prompt: str,
        model: str | None = None,
        image_size: str = "1024x1024",
        batch_size: int = 1,
        quality: str | None = None,
        style: str | None = None,
        output_format: str | None = None,
        response_format: str | None = None,
        **_: Any,
    ) -> list[str]:
        if not self.base_url:
            raise OpenAIImagesError("openai images baseUrl is not configured")
        if not self.api_key or self.api_key.startswith("replace-with-"):
            raise OpenAIImagesError("openai images API key is not configured")
        if not model:
            raise OpenAIImagesError("openai images modelName is required")

        payload: dict[str, Any] = {
            "model": model,
            "prompt": prompt,
            "n": max(1, min(10, int(batch_size or 1))),
            "size": _normalize_size(image_size),
        }
        resolved_quality = (quality or self.extra_auth.get("quality") or "").strip()
        if resolved_quality:
            payload["quality"] = resolved_quality
        resolved_style = (style or self.extra_auth.get("style") or "").strip()
        if resolved_style:
            payload["style"] = resolved_style
        resolved_output_format = (output_format or self.extra_auth.get("outputFormat") or "").strip()
        if resolved_output_format:
            payload["output_format"] = resolved_output_format
        resolved_response_format = (response_format or self.extra_auth.get("responseFormat") or "").strip()
        if resolved_response_format:
            payload["response_format"] = resolved_response_format

        LOGGER.info(
            "openai images request endpoint=%s model=%s n=%s size=%s quality=%s style=%s output_format=%s response_format=%s",
            self.endpoint_path,
            payload.get("model"),
            payload.get("n"),
            payload.get("size"),
            payload.get("quality"),
            payload.get("style"),
            payload.get("output_format"),
            payload.get("response_format"),
        )
        response = self._post(self.endpoint_path, payload)
        urls = self._extract_image_urls(response)
        self.last_usage = self._resolve_usage(response, payload, len(urls))
        return urls

    def _post(self, path: str, payload: dict[str, Any]) -> dict[str, Any]:
        url = f"{self.base_url}{_ensure_leading_slash(path)}"
        attempts = self.ssl_eof_retries + 1
        for attempt in range(1, attempts + 1):
            try:
                return self._post_once(url, payload)
            except SSLError as exc:
                if not _is_ssl_eof_error(exc) or attempt >= attempts:
                    raise OpenAIImagesError(
                        "openai images SSL connection failed before an HTTP response was received. "
                        "Check the gateway URL, local requests/urllib3 versions, proxy/VPN, and TLS interception. "
                        f"detail={exc}"
                    ) from exc
                time.sleep(min(2, attempt))
        raise OpenAIImagesError("openai images request failed")

    def _post_once(self, url: str, payload: dict[str, Any]) -> dict[str, Any]:
        started_at = time.perf_counter()
        diagnostics = self._connection_diagnostics(url)
        LOGGER.info(
            "openai images transport start url=%s timeout=%s diagnostics=%s",
            _redact_url(url),
            self.timeout,
            diagnostics,
        )
        try:
            response = self.session.post(
                url,
                json=payload,
                timeout=self.timeout,
            )
            LOGGER.info(
                "openai images transport completed url=%s status=%s elapsed=%.3fs diagnostics=%s",
                _redact_url(url),
                response.status_code,
                time.perf_counter() - started_at,
                diagnostics,
            )
        except Timeout as exc:
            elapsed = time.perf_counter() - started_at
            timeout_kind = _timeout_kind(exc)
            LOGGER.warning(
                "openai images transport timeout url=%s kind=%s elapsed=%.3fs timeout=%s diagnostics=%s error=%s",
                _redact_url(url),
                timeout_kind,
                elapsed,
                self.timeout,
                diagnostics,
                exc,
            )
            raise OpenAIImagesTimeoutError(
                "openai images request timed out: "
                f"kind={timeout_kind}; elapsed={elapsed:.3f}s; "
                f"connectTimeout={self.timeout[0]}s; readTimeout={self.timeout[1]}s; "
                f"diagnostics={diagnostics}"
            ) from exc
        except SSLError:
            raise
        except ProxyError as exc:
            raise OpenAIImagesError(
                "openai images proxy connection failed. "
                "The request is using an environment or configured proxy; set extraAuthJson trustEnv=false to bypass it, "
                "or configure proxyUrl explicitly. "
                f"detail={exc}"
            ) from exc
        except RequestException as exc:
            raise OpenAIImagesError(f"openai images request failed: {exc}") from exc

        try:
            response.raise_for_status()
        except requests.HTTPError as exc:
            raise OpenAIImagesError(
                f"openai images request failed: status={response.status_code}, body={response.text}"
            ) from exc

        try:
            data = response.json()
        except ValueError as exc:
            raise OpenAIImagesError("openai images returned non-json response") from exc
        if not isinstance(data, dict):
            raise OpenAIImagesError("openai images returned invalid response")
        return data

    def _connection_diagnostics(self, url: str) -> str:
        parsed = urlparse(url)
        host = parsed.hostname or ""
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
        proxy_enabled = bool(self.session.proxies) or self.session.trust_env
        addresses = _resolve_host_addresses(host, port)
        return (
            f"host={host or '-'}; port={port}; trustEnv={self.session.trust_env}; "
            f"configuredProxy={bool(self.session.proxies)}; proxyEnabled={proxy_enabled}; resolved={addresses}"
        )

    def _extract_image_urls(self, payload: dict[str, Any]) -> list[str]:
        data = payload.get("data")
        if not isinstance(data, list):
            raise OpenAIImagesError("openai images response missing data")
        urls: list[str] = []
        for item in data:
            if not isinstance(item, dict):
                continue
            url = item.get("url")
            if isinstance(url, str) and url.strip():
                urls.append(url.strip())
                continue
            b64_json = item.get("b64_json")
            if isinstance(b64_json, str) and b64_json.strip():
                urls.append(f"data:image/png;base64,{b64_json.strip()}")
        if not urls:
            raise OpenAIImagesError("openai images response missing image url")
        return urls

    def _resolve_usage(self, response: dict[str, Any], payload: dict[str, Any], image_count: int) -> dict[str, int]:
        usage = response.get("usage")
        if isinstance(usage, dict):
            input_tokens = _as_int(usage.get("input_tokens") or usage.get("prompt_tokens"))
            output_tokens = _as_int(usage.get("output_tokens") or usage.get("completion_tokens"))
            total_tokens = _as_int(usage.get("total_tokens"))
            if total_tokens <= 0:
                total_tokens = input_tokens + output_tokens
            return {
                "promptTokens": input_tokens,
                "completionTokens": output_tokens,
                "totalTokens": total_tokens,
            }

        prompt_tokens = max(1, math.ceil(len(str(payload.get("prompt") or "")) / 4))
        output_tokens = self._estimate_output_tokens(
            size=str(payload.get("size") or "1024x1024"),
            quality=str(payload.get("quality") or "medium"),
            image_count=image_count,
        )
        return {
            "promptTokens": prompt_tokens,
            "completionTokens": output_tokens,
            "totalTokens": prompt_tokens + output_tokens,
        }

    def _estimate_output_tokens(self, *, size: str, quality: str, image_count: int) -> int:
        configured = self.extra_auth.get("imageTokenEstimate")
        if isinstance(configured, dict):
            size_config = configured.get(size)
            if isinstance(size_config, dict):
                value = _as_int(size_config.get(quality) or size_config.get("default"))
                if value > 0:
                    return value * max(1, image_count)
            value = _as_int(configured.get("defaultOutputTokens"))
            if value > 0:
                return value * max(1, image_count)

        long_edge = max(_parse_size(size))
        base = 1296 if long_edge >= 1536 else 1056
        multiplier = {"low": 0.5, "medium": 1.0, "standard": 1.0, "high": 4.0, "auto": 1.0}.get(
            quality.lower(),
            1.0,
        )
        return max(1, int(base * multiplier)) * max(1, image_count)

    def _resolve_timeout(self, timeout_seconds: int | None) -> tuple[float, float]:
        connect_timeout = _as_float(self.extra_auth.get("connectTimeoutSeconds"), 10)
        read_timeout = _as_float(
            self.extra_auth.get("readTimeoutSeconds") or self.extra_auth.get("timeoutSeconds") or timeout_seconds,
            300,
        )
        return (max(1.0, connect_timeout), max(60.0, read_timeout))

    def _resolve_ssl_eof_retries(self) -> int:
        value = self.extra_auth.get("sslEofRetries")
        if isinstance(value, bool):
            return 1 if value else 0
        return min(2, _as_int(value))

    @staticmethod
    def _parse_json(value: str | None) -> dict[str, Any]:
        if not value or not value.strip():
            return {}
        try:
            parsed = json.loads(value)
        except json.JSONDecodeError:
            return {}
        return parsed if isinstance(parsed, dict) else {}


def _normalize_size(value: str) -> str:
    size = (value or "1024x1024").strip()
    if ":" in size:
        return {
            "1:1": "1024x1024",
            "16:9": "1536x864",
            "9:16": "864x1536",
            "4:3": "1024x768",
            "3:4": "768x1024",
        }.get(size, "1024x1024")
    return size


def _parse_size(value: str) -> tuple[int, int]:
    parts = value.lower().split("x", 1)
    if len(parts) != 2:
        return (1024, 1024)
    try:
        return (max(1, int(parts[0])), max(1, int(parts[1])))
    except ValueError:
        return (1024, 1024)


def _ensure_leading_slash(value: str) -> str:
    return value if value.startswith("/") else f"/{value}"


def _as_int(value: Any) -> int:
    try:
        return max(0, int(value))
    except (TypeError, ValueError):
        return 0


def _as_float(value: Any, fallback: float) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return fallback


def _as_bool(value: Any, fallback: bool) -> bool:
    if value is None:
        return fallback
    if isinstance(value, bool):
        return value
    text = str(value).strip().lower()
    if text in {"1", "true", "yes", "on"}:
        return True
    if text in {"0", "false", "no", "off"}:
        return False
    return fallback


def _is_ssl_eof_error(exc: BaseException) -> bool:
    message = str(exc).lower()
    return "eof occurred in violation of protocol" in message or "ssleoferror" in message


def _timeout_kind(exc: Timeout) -> str:
    if isinstance(exc, ConnectTimeout):
        return "connect"
    if isinstance(exc, ReadTimeout):
        return "read"
    return "timeout"


def _resolve_host_addresses(host: str, port: int) -> str:
    if not host:
        return "-"
    try:
        infos = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    except OSError as exc:
        return f"dns_error:{exc}"
    addresses: list[str] = []
    for info in infos:
        address = info[4][0]
        if address not in addresses:
            addresses.append(address)
    return ",".join(addresses[:8]) or "-"


def _redact_url(url: str) -> str:
    parsed = urlparse(url)
    if not parsed.hostname:
        return url
    port = f":{parsed.port}" if parsed.port else ""
    path = parsed.path or "/"
    return f"{parsed.scheme}://{parsed.hostname}{port}{path}"
