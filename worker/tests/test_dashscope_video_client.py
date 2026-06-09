from client.dashscope_video_client import DashScopeVideoClient


def test_extracts_dashscope_task_result() -> None:
    payload = {
        "request_id": "req-1",
        "output": {
            "task_id": "task-1",
            "task_status": "SUCCEEDED",
            "video_url": "https://example.com/result.mp4",
            "usage": {"output_video_duration": 6},
        },
    }

    assert DashScopeVideoClient._extract_task_id(payload) == "task-1"
    assert DashScopeVideoClient._extract_request_id(payload) == "req-1"
    assert DashScopeVideoClient._extract_status(payload) == "SUCCEEDED"
    assert DashScopeVideoClient._extract_video_url(payload) == "https://example.com/result.mp4"
    assert DashScopeVideoClient._extract_usage(payload) == {"output_video_duration": 6}


def test_dashscope_timeout_has_happyhorse_floor() -> None:
    assert DashScopeVideoClient(api_key="fake", timeout_seconds=900).timeout_seconds == 3600
    assert DashScopeVideoClient(api_key="fake", timeout_seconds=7200).timeout_seconds == 7200
