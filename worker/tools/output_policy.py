"""统一追加到 system prompt 的输出纪律，减少模型复述用户表单内容。"""

_OUTPUT_DISCIPLINE_MARKER = "[output_discipline:no_form_echo_v1]"

_OUTPUT_DISCIPLINE_TEXT = (
    "输出纪律：不要复述、罗列或摘抄用户在表单中的原始填写（不要整段输出「- 字段名：值」式的信息总表）。"
    "不要以「根据您提供的信息」「基于您的输入」等套话开篇。"
    "从第一个二级标题（##）起只写可直接使用的成品正文、话术或条目，便于用户整段复制粘贴。"
    "请全程使用中文回答。"
    f" {_OUTPUT_DISCIPLINE_MARKER}"
)


def apply_output_discipline(system_prompt: str) -> str:
    base = (system_prompt or "").strip()
    if _OUTPUT_DISCIPLINE_MARKER in base:
        return base
    if not base:
        return _OUTPUT_DISCIPLINE_TEXT.strip()
    return f"{base}\n\n{_OUTPUT_DISCIPLINE_TEXT}"
