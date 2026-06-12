from client.seedance_video_client import SeedanceVideoClient


def test_normalize_endpoint_avoids_double_api_v3_prefix() -> None:
    base, path = SeedanceVideoClient._normalize_endpoint(
        "https://ark.cn-beijing.volces.com/api/v3",
        "/api/v3/contents/generations/tasks",
    )
    assert base == "https://ark.cn-beijing.volces.com/api/v3"
    assert path == "/contents/generations/tasks"


def test_normalize_endpoint_keeps_host_only_base_url() -> None:
    base, path = SeedanceVideoClient._normalize_endpoint(
        "https://ark.cn-beijing.volces.com",
        "/api/v3/contents/generations/tasks",
    )
    assert base == "https://ark.cn-beijing.volces.com"
    assert path == "/api/v3/contents/generations/tasks"
