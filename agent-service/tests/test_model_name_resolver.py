import pytest

from app.clients.model_name_resolver import resolve_chat_model_name


@pytest.mark.parametrize("model_name", ["deepseek-v4-pro", "deepseek-v4-flash"])
def test_current_deepseek_v4_model_names_are_not_downgraded(model_name: str) -> None:
    assert (
        resolve_chat_model_name(
            model_name,
            "https://api.deepseek.com",
            "deepseek",
        )
        == model_name
    )


def test_volcengine_marketing_alias_is_still_resolved() -> None:
    assert (
        resolve_chat_model_name(
            "doubao-seed-2.0-pro",
            "https://ark.cn-beijing.volces.com/api/v3",
            "openai_compatible",
        )
        == "doubao-seed-2-0-pro-260215"
    )
