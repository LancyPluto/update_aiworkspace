from volcengine_model import normalize_volcengine_openai_base_url, resolve_volcengine_images_paths


def test_normalize_root_ark_base_url_to_api_v3() -> None:
    assert (
        normalize_volcengine_openai_base_url("https://ark.cn-beijing.volces.com")
        == "https://ark.cn-beijing.volces.com/api/v3"
    )


def test_resolve_images_paths_upgrades_default_ark_paths() -> None:
    endpoint_path, edit_endpoint_path = resolve_volcengine_images_paths(
        "https://ark.cn-beijing.volces.com",
        "/images/generations",
        "/images/edits",
    )
    assert endpoint_path == "/api/v3/images/generations"
    assert edit_endpoint_path == "/api/v3/images/edits"


def test_resolve_images_paths_keeps_custom_paths() -> None:
    endpoint_path, edit_endpoint_path = resolve_volcengine_images_paths(
        "https://ark.cn-beijing.volces.com",
        "/custom/images",
        "/custom/edits",
    )
    assert endpoint_path == "/custom/images"
    assert edit_endpoint_path == "/custom/edits"
