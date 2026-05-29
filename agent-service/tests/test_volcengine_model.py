from app.clients.volcengine_model import resolve_volcengine_model_name


def test_resolve_seed_2_lite_alias_for_ark_base_url():
    resolved = resolve_volcengine_model_name(
        "doubao-seed-2.0-lite",
        "https://ark.cn-beijing.volces.com/api/v3",
    )
    assert resolved == "doubao-seed-2-0-lite-260215"


def test_keep_unknown_model_name():
    assert (
        resolve_volcengine_model_name("custom-endpoint-id", "https://ark.cn-beijing.volces.com/api/v3")
        == "custom-endpoint-id"
    )


def test_skip_alias_for_non_volcengine_base_url():
    assert (
        resolve_volcengine_model_name("doubao-seed-2.0-lite", "https://api.openai.com/v1")
        == "doubao-seed-2.0-lite"
    )
