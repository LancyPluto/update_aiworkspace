from utils.volcengine_config import is_volcengine_model_config, resolve_volcengine_task_model


def test_resolve_volcengine_task_model_prefers_params_model() -> None:
    resolved = resolve_volcengine_task_model(
        {"model": "doubao-seedream-5-0-260128"},
        {
            "provider": "volcengine_images",
            "modelName": "doubao-seedream-4-5-251128",
            "baseUrl": "https://ark.cn-beijing.volces.com/api/v3",
        },
    )
    assert resolved == "doubao-seedream-5-0-260128"


def test_resolve_volcengine_task_model_falls_back_to_model_config() -> None:
    resolved = resolve_volcengine_task_model(
        {},
        {
            "provider": "seedance",
            "modelName": "doubao-seedance-1-5-pro-251215",
            "baseUrl": "https://ark.cn-beijing.volces.com",
        },
    )
    assert resolved == "doubao-seedance-1-5-pro-251215"


def test_resolve_volcengine_task_model_applies_chat_alias() -> None:
    resolved = resolve_volcengine_task_model(
        {"model": "doubao-seed-2.0-lite"},
        {
            "provider": "openai_compatible",
            "modelName": "doubao-seed-2-0-pro-260215",
            "baseUrl": "https://ark.cn-beijing.volces.com/api/v3",
        },
    )
    assert resolved == "doubao-seed-2-0-lite-260215"


def test_is_volcengine_model_config_detects_seedance_provider() -> None:
    assert is_volcengine_model_config({"provider": "seedance", "modelName": "doubao-seedance-1-5-pro-251215"})
