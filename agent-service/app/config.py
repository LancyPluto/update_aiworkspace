import os
from dataclasses import dataclass

try:
    from dotenv import load_dotenv
except ImportError:  # pragma: no cover
    load_dotenv = None


if load_dotenv is not None:
    load_dotenv()


def _bool(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


@dataclass(slots=True)
class Settings:
    app_env: str = os.getenv("APP_ENV", "local")
    host: str = os.getenv("HOST", "0.0.0.0")
    port: int = int(os.getenv("PORT", "8090"))
    log_level: str = os.getenv("LOG_LEVEL", "INFO")
    backend_internal_base_url: str = os.getenv("BACKEND_INTERNAL_BASE_URL", "http://127.0.0.1:8080")
    internal_api_token: str = os.getenv("INTERNAL_API_TOKEN", "local-internal-token")
    agent_verify_internal_signature: bool = _bool("AGENT_VERIFY_INTERNAL_SIGNATURE", False)
    agent_execution_mode: str = os.getenv("AGENT_EXECUTION_MODE", "background")
    agent_deep_agents_enabled: bool = _bool("AGENT_DEEP_AGENTS_ENABLED", False)
    agent_graph_engine_enabled: bool = _bool("AGENT_GRAPH_ENGINE_ENABLED", False)
    agent_graph_max_iterations: int = int(os.getenv("AGENT_GRAPH_MAX_ITERATIONS", "8"))
    model_provider: str = os.getenv("MODEL_PROVIDER", "mock")
    model_api_base_url: str = os.getenv("MODEL_API_BASE_URL", "https://api.deepseek.com")
    model_api_key: str = os.getenv("MODEL_API_KEY", "replace-with-model-key")
    model_name: str = os.getenv("MODEL_NAME", "deepseek-chat")
    minimax_group_id: str = os.getenv("MINIMAX_GROUP_ID", "")
    model_timeout_seconds: int = int(os.getenv("MODEL_TIMEOUT_SECONDS", "60"))
    model_connect_retry_count: int = int(os.getenv("MODEL_CONNECT_RETRY_COUNT", "2"))
    agent_router_candidate_limit: int = int(os.getenv("AGENT_ROUTER_CANDIDATE_LIMIT", "15"))
    agent_router_history_clip: int = int(os.getenv("AGENT_ROUTER_HISTORY_CLIP", "800"))
    agent_max_tool_calls: int = int(os.getenv("AGENT_MAX_TOOL_CALLS", "3"))
    agent_max_model_calls: int = int(os.getenv("AGENT_MAX_MODEL_CALLS", "5"))
    agent_max_history_messages: int = int(os.getenv("AGENT_MAX_HISTORY_MESSAGES", "20"))
    agent_context_max_recent_turns: int = int(os.getenv("AGENT_CONTEXT_MAX_RECENT_TURNS", "5"))
    agent_msg_char_limit: int = int(os.getenv("AGENT_MSG_CHAR_LIMIT", "2000"))
    agent_tool_output_char_limit: int = int(os.getenv("AGENT_TOOL_OUTPUT_CHAR_LIMIT", "800"))
    agent_context_prune_token_budget: int = int(os.getenv("AGENT_CONTEXT_PRUNE_TOKEN_BUDGET", "3000"))
    agent_pruning_tool_char_limit: int = int(os.getenv("AGENT_PRUNING_TOOL_CHAR_LIMIT", "400"))
    agent_pre_compaction_flush_min_saved_percent: float = float(os.getenv("AGENT_PRE_COMPACTION_FLUSH_MIN_SAVED_PERCENT", "10"))
    agent_file_context_char_limit: int = int(os.getenv("AGENT_FILE_CONTEXT_CHAR_LIMIT", "4000"))
    agent_tool_disclosure_enabled: bool = _bool("AGENT_TOOL_DISCLOSURE_ENABLED", True)
    agent_tool_shortlist_k: int = int(os.getenv("AGENT_TOOL_SHORTLIST_K", "5"))
    agent_tool_shortlist_k_media: int = int(os.getenv("AGENT_TOOL_SHORTLIST_K_MEDIA", "8"))
    agent_tool_desc_char_limit: int = int(os.getenv("AGENT_TOOL_DESC_CHAR_LIMIT", "150"))
    agent_tool_schema_prune_fields: bool = _bool("AGENT_TOOL_SCHEMA_PRUNE_FIELDS", True)
    agent_default_consumed_credits: int = int(os.getenv("AGENT_DEFAULT_CONSUMED_CREDITS", "1"))
    agent_model_call_cost: int = int(os.getenv("AGENT_MODEL_CALL_COST", "1"))
    agent_tool_execution_timeout_seconds: int = int(os.getenv("AGENT_TOOL_EXECUTION_TIMEOUT_SECONDS", "120"))
    agent_image_tool_execution_timeout_seconds: int = int(os.getenv("AGENT_IMAGE_TOOL_EXECUTION_TIMEOUT_SECONDS", "600"))
    agent_video_tool_execution_timeout_seconds: int = int(os.getenv("AGENT_VIDEO_TOOL_EXECUTION_TIMEOUT_SECONDS", "900"))
    agent_music_tool_execution_timeout_seconds: int = int(os.getenv("AGENT_MUSIC_TOOL_EXECUTION_TIMEOUT_SECONDS", "900"))
    agent_tool_poll_interval_seconds: float = float(os.getenv("AGENT_TOOL_POLL_INTERVAL_SECONDS", "1"))
    agent_tool_stream_relay_enabled: bool = _bool("AGENT_TOOL_STREAM_RELAY_ENABLED", True)
    agent_llm_router_enabled: bool = _bool("AGENT_LLM_ROUTER_ENABLED", False)
    agent_unified_router_enabled: bool = _bool("AGENT_UNIFIED_ROUTER_ENABLED", True)
    agent_router_embedding_recall_k: int = int(os.getenv("AGENT_ROUTER_EMBEDDING_RECALL_K", "12"))
    agent_routing_v2_enabled: bool = _bool("AGENT_ROUTING_V2_ENABLED", True)
    agent_routing_v2_shadow_mode: bool = _bool("AGENT_ROUTING_V2_SHADOW_MODE", False)
    agent_routing_v2_llm_only: bool = _bool("AGENT_ROUTING_V2_LLM_ONLY", False)
    agent_routing_llm_first: bool = _bool("AGENT_ROUTING_LLM_FIRST", True)
    agent_capability_file_analysis: bool = _bool("AGENT_CAPABILITY_FILE_ANALYSIS", False)
    agent_capability_rag: bool = _bool("AGENT_CAPABILITY_RAG", False)
    agent_capability_workflow: bool = _bool("AGENT_CAPABILITY_WORKFLOW", False)
    agent_product_tool_loop_enabled: bool = _bool("AGENT_PRODUCT_TOOL_LOOP_ENABLED", True)
    agent_product_tool_loop_max_calls: int = int(os.getenv("AGENT_PRODUCT_TOOL_LOOP_MAX_CALLS", "1"))
    agent_product_tool_loop_fallback_to_router: bool = _bool("AGENT_PRODUCT_TOOL_LOOP_FALLBACK_TO_ROUTER", True)
    agent_memory_retrieval_limit: int = int(os.getenv("AGENT_MEMORY_RETRIEVAL_LIMIT", "10"))
    agent_memory_auto_save_enabled: bool = _bool("AGENT_MEMORY_AUTO_SAVE", True)
    agent_memory_tool_loop_enabled: bool = _bool("AGENT_MEMORY_TOOL_LOOP_ENABLED", True)
    agent_memory_consolidation_enabled: bool = _bool("AGENT_MEMORY_CONSOLIDATION_ENABLED", True)
    agent_memory_consolidation_llm_enabled: bool = _bool("AGENT_MEMORY_CONSOLIDATION_LLM_ENABLED", True)
    agent_memory_consolidation_turn_interval: int = int(os.getenv("AGENT_MEMORY_CONSOLIDATION_TURN_INTERVAL", "8"))
    agent_memory_consolidation_char_threshold: int = int(os.getenv("AGENT_MEMORY_CONSOLIDATION_CHAR_THRESHOLD", "4000"))
    agent_memory_consolidation_token_threshold: int = int(os.getenv("AGENT_MEMORY_CONSOLIDATION_TOKEN_THRESHOLD", "3000"))
    agent_memory_consolidation_recent_tool_threshold: int = int(os.getenv("AGENT_MEMORY_CONSOLIDATION_RECENT_TOOL_THRESHOLD", "3"))
    agent_memory_consolidation_max_context_messages: int = int(os.getenv("AGENT_MEMORY_CONSOLIDATION_MAX_CONTEXT_MESSAGES", "24"))
    agent_memory_consolidation_prompt: str = os.getenv("AGENT_MEMORY_CONSOLIDATION_PROMPT", "")
    agent_memory_consolidation_min_confidence: float = float(os.getenv("AGENT_MEMORY_CONSOLIDATION_MIN_CONFIDENCE", "0.72"))
    agent_memory_candidate_confidence_threshold: float = float(os.getenv("AGENT_MEMORY_CANDIDATE_CONFIDENCE_THRESHOLD", "0.55"))


settings = Settings()
