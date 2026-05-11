import hashlib
import hmac
import time
import uuid
from urllib.parse import urlsplit


SIGNATURE_TTL_SECONDS = 300


def _content(method: str, path: str, timestamp: str, nonce: str, body: bytes) -> str:
    request_path = urlsplit(path).path
    body_hash = hashlib.sha256(body or b"").hexdigest()
    return "\n".join([method.upper(), request_path, timestamp, nonce, body_hash])


def signature_headers(method: str, path: str, body: bytes, secret: str) -> dict[str, str]:
    timestamp = str(int(time.time() * 1000))
    nonce = str(uuid.uuid4())
    signature = hmac.new(
        secret.encode("utf-8"),
        _content(method, path, timestamp, nonce, body).encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()
    return {
        "X-Internal-Timestamp": timestamp,
        "X-Internal-Nonce": nonce,
        "X-Internal-Signature": signature,
    }


def verify_signature(
    method: str,
    path: str,
    timestamp: str | None,
    nonce: str | None,
    signature: str | None,
    body: bytes,
    secret: str,
) -> bool:
    if not method or not path or not timestamp or not nonce or not signature:
        return False
    try:
        timestamp_millis = int(timestamp)
    except ValueError:
        return False
    age_seconds = abs((time.time() * 1000 - timestamp_millis) / 1000)
    if age_seconds > SIGNATURE_TTL_SECONDS:
        return False

    expected = hmac.new(
        secret.encode("utf-8"),
        _content(method, path, timestamp, nonce, body).encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()
    return hmac.compare_digest(expected, signature)
