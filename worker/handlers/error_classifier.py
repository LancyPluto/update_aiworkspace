"""Classify model/API error messages into structured error codes."""


def classify_model_error(message: str) -> str:
    normalized = message.lower()

    if _is_risk_control(normalized):
        return "MODEL_RISK_CONTROL_REJECTED"
    if _is_credit_error(normalized):
        return "MODEL_CREDIT_INSUFFICIENT"
    if _is_capability_disabled(normalized):
        return "MODEL_CAPABILITY_DISABLED"
    if _is_auth_error(normalized):
        return "MODEL_AUTH_FAILED"
    if _is_rate_limited(normalized):
        return "MODEL_RATE_LIMITED"
    if _is_timeout(normalized):
        return "MODEL_TIMEOUT"
    return "MODEL_CALL_FAILED"


def _is_risk_control(text: str) -> bool:
    return (
        "risk control" in text
        or "content policy" in text
        or "safety policy" in text
        or "safety system" in text
        or "content_policy_violation" in text
        or "image_generation_user_error" in text
        or ("rejected by" in text and "safety" in text)
        or "sensitive content" in text
        or ("moderation" in text and ("block" in text or "reject" in text or "flagged" in text))
        or "违规" in text
        or "违禁" in text
        or "敏感词" in text
        or "内容审核" in text
        or ("task_status_msg" in text and "risk control" in text)
    )


def _is_auth_error(text: str) -> bool:
    return (
        "status=401" in text
        or "status=403" in text
        or "invalid token" in text
        or "unauthorized" in text
        or "api key" in text
    )


def _is_capability_disabled(text: str) -> bool:
    return (
        "image generation is not enabled" in text
        or "not enabled for this group" in text
        or "image generation disabled" in text
        or "model capability disabled" in text
        or ("permission_error" in text and "image generation" in text)
    )


def _is_credit_error(text: str) -> bool:
    return (
        "account balance not enough" in text
        or "balance not enough" in text
        or "insufficient balance" in text
        or '"code":1102' in text
        or "allocationquota" in text
        or "free quota" in text
        or "free tier" in text
    )


def _is_rate_limited(text: str) -> bool:
    return (
        "status=429" in text
        or "rate limit" in text
        or "too many requests" in text
    )


def _is_timeout(text: str) -> bool:
    return "timed out" in text or "timeout" in text
