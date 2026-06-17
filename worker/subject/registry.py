from __future__ import annotations

from subject.kling_subject_adapter import KlingSubjectAdapter, SubjectAdapterProtocol

SUBJECT_ADAPTERS: dict[str, SubjectAdapterProtocol] = {
    "kling_video": KlingSubjectAdapter(),
}


def require_subject_adapter(provider_code: str) -> SubjectAdapterProtocol:
    normalized = str(provider_code or "").strip()
    adapter = SUBJECT_ADAPTERS.get(normalized)
    if adapter is None:
        raise KeyError(f"unsupported subject provider: {normalized}")
    return adapter
