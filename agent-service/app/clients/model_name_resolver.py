from app.clients.volcengine_model import resolve_volcengine_model_name


def resolve_chat_model_name(model_name: str, base_url: str, provider: str) -> str:
    return resolve_volcengine_model_name(model_name, base_url)
