import time

from app.security.signature import signature_headers, verify_signature


def test_signature_headers_can_be_verified():
    body = b'{"runId":10001}'
    headers = signature_headers("POST", "/internal/v1/agent/runs/10001/execute", body, "secret")

    assert verify_signature(
        "POST",
        "/internal/v1/agent/runs/10001/execute",
        headers["X-Internal-Timestamp"],
        headers["X-Internal-Nonce"],
        headers["X-Internal-Signature"],
        body,
        "secret",
    )


def test_signature_rejects_wrong_body():
    body = b'{"runId":10001}'
    headers = signature_headers("POST", "/internal/v1/agent/runs/10001/execute", body, "secret")

    assert not verify_signature(
        "POST",
        "/internal/v1/agent/runs/10001/execute",
        headers["X-Internal-Timestamp"],
        headers["X-Internal-Nonce"],
        headers["X-Internal-Signature"],
        b'{"runId":999}',
        "secret",
    )


def test_signature_rejects_stale_timestamp():
    stale = str(int((time.time() - 600) * 1000))

    assert not verify_signature("POST", "/x", stale, "nonce", "signature", b"", "secret")


def test_signature_rejects_missing_fields():
    assert not verify_signature("POST", "/x", "", "nonce", "signature", b"", "secret")
    assert not verify_signature("POST", "/x", "1", "", "signature", b"", "secret")
    assert not verify_signature("POST", "/x", "1", "nonce", "", b"", "secret")
