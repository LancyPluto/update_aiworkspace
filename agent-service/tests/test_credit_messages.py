from app.clients.backend_client import BackendBusinessError
from app.credit_messages import credit_message_from_backend_error


def test_credit_message_from_backend_error_includes_balance_and_tool():
    exc = BackendBusinessError(
        "fallback",
        error_code="CREDIT_NOT_ENOUGH",
        data={"availableCredits": 12, "requiredCredits": 30, "toolCode": "image_generation"},
    )
    message = credit_message_from_backend_error(exc, "image_generation")
    assert "当前 12" in message
    assert "至少需要 30" in message
    assert "image_generation" in message
    assert "会员与算力" in message
