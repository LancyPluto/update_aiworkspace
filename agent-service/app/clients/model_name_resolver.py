from app.clients.volcengine_model import resolve_volcengine_model_name

DEEPSEEK_MODEL_ALIASES: dict[str, str] = {
    "deepseek-v4-pro": "deepseek-chat",
    "deepseek-v4-flash": "deepseek-chat",
}


def resolve_chat_model_name(model_name: str, base_url: str, provider: str) -> str:
    resolved = resolve_volcengine_model_name(model_name, base_url)
    normalized_provider = (provider or "").strip().lower()
    normalized_base = (base_url or "").strip().lower()
    if normalized_provider in {"deepseek", "deepseek_compatible", "openai_compatible"} or "deepseek.com" in normalized_base:
        return DEEPSEEK_MODEL_ALIASES.get(resolved.strip().lower(), resolved)
    return resolved
