import pytest
import httpx

from app.clients.model_client import _is_retryable_connection_error, _retry_openai_compatible_call


def test_is_retryable_connection_error_matches_connect_failures():
    assert _is_retryable_connection_error(httpx.ConnectError("All connection attempts failed"))
    assert _is_retryable_connection_error(httpx.ConnectTimeout("connect timeout"))
    assert not _is_retryable_connection_error(httpx.HTTPStatusError("bad", request=httpx.Request("POST", "http://x"), response=httpx.Response(400)))


@pytest.mark.asyncio
async def test_retry_openai_compatible_call_retries_then_succeeds():
    attempts = {"count": 0}

    async def flaky():
        attempts["count"] += 1
        if attempts["count"] < 2:
            raise httpx.ConnectError("All connection attempts failed")
        return {"ok": True}

    result = await _retry_openai_compatible_call(flaky, retry_count=2)
    assert result == {"ok": True}
    assert attempts["count"] == 2
