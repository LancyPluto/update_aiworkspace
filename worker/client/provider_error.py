from __future__ import annotations

from typing import Any


DELIVERY_NOT_SENT = "NOT_SENT"
DELIVERY_REJECTED = "REJECTED"
DELIVERY_ACCEPTED = "ACCEPTED"
DELIVERY_UNKNOWN = "UNKNOWN"

RETRY_ACCOUNT = "ACCOUNT"
RETRY_NONE = "NONE"


_NOT_SENT_EXCEPTION_TYPES = {
    "ConnectTimeoutError",
    "ConnectError",
    "ConnectTimeout",
    "ConnectionRefusedError",
    "NameResolutionError",
    "NewConnectionError",
    "ProxyError",
    "SSLError",
    "gaierror",
}

_SAFE_PROVIDER_REJECTION_CODES = {
    "channel_unavailable",
    "model_not_found",
    "model_unavailable",
    "no_available_channel",
    "provider_unavailable",
}


class ProviderCallError(RuntimeError):
    """Provider failure with transport facts used for conservative failover."""

    def __init__(
        self,
        message: str,
        *,
        delivery_state: str = DELIVERY_UNKNOWN,
        retry_scope: str = RETRY_NONE,
        failure_stage: str = "PROVIDER_SUBMITTED",
        http_status: int | None = None,
        provider_error_code: str | None = None,
        provider_request_id: str | None = None,
        provider_charged: bool | None = None,
        retry_after_seconds: int | None = None,
    ) -> None:
        super().__init__(message)
        self.delivery_state = delivery_state
        self.retry_scope = retry_scope
        self.failure_stage = failure_stage
        self.http_status = http_status
        self.provider_error_code = provider_error_code
        self.provider_request_id = provider_request_id
        self.provider_charged = provider_charged
        self.retry_after_seconds = retry_after_seconds

    def failure_payload(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "deliveryState": self.delivery_state,
            "retryScope": self.retry_scope,
            "failureStage": self.failure_stage,
        }
        if self.provider_error_code:
            payload["providerErrorCode"] = self.provider_error_code
        elif self.http_status is not None:
            payload["providerErrorCode"] = f"HTTP_{self.http_status}"
        if self.provider_request_id:
            payload["providerRequestId"] = self.provider_request_id
        if self.provider_charged is not None:
            payload["providerCharged"] = self.provider_charged
        if self.retry_after_seconds is not None:
            payload["retryAfterSeconds"] = max(0, int(self.retry_after_seconds))
        return payload


def structured_failure_payload(error: BaseException) -> dict[str, Any]:
    if isinstance(error, ProviderCallError):
        return error.failure_payload()
    return {
        "deliveryState": DELIVERY_UNKNOWN,
        "retryScope": RETRY_NONE,
        "failureStage": "PROVIDER_SUBMITTED",
    }


def request_was_not_sent(error: BaseException) -> bool:
    """Return true only for typed failures that happen before an HTTP request is delivered."""

    pending: list[BaseException] = [error]
    seen: set[int] = set()
    while pending:
        current = pending.pop()
        if id(current) in seen:
            continue
        seen.add(id(current))
        if current.__class__.__name__ in _NOT_SENT_EXCEPTION_TYPES:
            return True
        for nested in (current.__cause__, current.__context__, getattr(current, "reason", None)):
            if isinstance(nested, BaseException):
                pending.append(nested)
        pending.extend(argument for argument in current.args if isinstance(argument, BaseException))
    return False


def rejected_http_metadata(
    status_code: int,
    retry_after_seconds: int | None = None,
    provider_error_code: str | None = None,
) -> dict[str, Any]:
    normalized_code = _normalized_provider_error_code(provider_error_code)
    explicitly_not_accepted = normalized_code in _SAFE_PROVIDER_REJECTION_CODES
    if status_code >= 500 and not explicitly_not_accepted:
        return {
            "delivery_state": DELIVERY_UNKNOWN,
            "retry_scope": RETRY_NONE,
            "failure_stage": "PROVIDER_SUBMITTED",
            "http_status": status_code,
            "provider_error_code": provider_error_code,
            "retry_after_seconds": retry_after_seconds,
        }
    safe_account_rejections = {401, 402, 403, 404, 429}
    retry_scope = RETRY_ACCOUNT if status_code in safe_account_rejections or explicitly_not_accepted else RETRY_NONE
    return {
        "delivery_state": DELIVERY_REJECTED,
        "retry_scope": retry_scope,
        "failure_stage": "BEFORE_PROVIDER",
        "http_status": status_code,
        "provider_error_code": provider_error_code,
        "retry_after_seconds": retry_after_seconds,
    }


def response_provider_error_code(response: Any) -> str | None:
    try:
        payload = response.json()
    except (AttributeError, TypeError, ValueError):
        return None
    return _find_provider_error_code(payload)


def _find_provider_error_code(value: Any) -> str | None:
    if not isinstance(value, dict):
        return None
    for key in ("code", "error_code", "errorCode", "type"):
        candidate = value.get(key)
        if isinstance(candidate, str) and _normalized_provider_error_code(candidate) in _SAFE_PROVIDER_REJECTION_CODES:
            return candidate.strip()
    for key in ("error", "data"):
        nested = _find_provider_error_code(value.get(key))
        if nested:
            return nested
    return None


def _normalized_provider_error_code(value: str | None) -> str:
    return str(value or "").strip().lower().replace("-", "_").replace(" ", "_")
