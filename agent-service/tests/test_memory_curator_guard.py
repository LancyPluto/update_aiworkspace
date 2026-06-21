from app.core.schemas import RunContext
from app.runtime.memory_curator import MemoryCuratorService, _looks_like_project_specific_content


def test_project_specific_content_detects_poster_project():
    assert _looks_like_project_specific_content("科比×张雪峰「张继科」文艺片海报项目") is True
    assert _looks_like_project_specific_content("调整图片，人物占比更大") is False


def test_workspace_fact_with_project_content_stays_candidate():
    curator = MemoryCuratorService()
    context = RunContext(runId=1, sessionId=9, userId=2, message="记住这个科比海报项目")
    decision = curator.decide(context, "好的，已记录海报项目细节。")
    assert decision.action == "candidate"
    assert decision.memory_type == "workspace_fact"


def test_heuristic_workspace_fact_detection_is_candidate():
    curator = MemoryCuratorService()
    context = RunContext(runId=3, sessionId=12, userId=2, message="科比×张雪峰「张继科」文艺片海报项目")
    decision = curator.decide(context, "收到。")
    assert decision.action == "candidate"
    assert decision.memory_type == "workspace_fact"
