from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel

from app.api.internal_runs import _verify_internal_request
from app.clients.model_client import ModelClient, ModelClientError
from app.clients.model_config_tester import _settings_from_config
from app.core.schemas import AgentModelConfig, ChatMessage

router = APIRouter()


class MarketChatCompletionRequest(BaseModel):
    modelConfig: AgentModelConfig
    messages: list[ChatMessage]


@router.post("/internal/v1/market/chat/completions")
async def market_chat_completions(request_body: MarketChatCompletionRequest, request: Request):
    await _verify_internal_request(request)
    if not request_body.messages:
        raise HTTPException(status_code=400, detail="messages is required")
    try:
        answer = await ModelClient(_settings_from_config(request_body.modelConfig)).chat(request_body.messages)
    except (ModelClientError, ValueError) as exception:
        raise HTTPException(status_code=502, detail=str(exception)) from exception
    return {"content": answer}
