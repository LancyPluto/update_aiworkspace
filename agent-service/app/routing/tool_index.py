"""Embedding-based tool recall for routing (Phase 2)."""



from __future__ import annotations



import logging

import math

import re

from collections import Counter

from dataclasses import dataclass

from typing import Any



from app.config import settings

from app.core.schemas import RunContext, ToolDescriptor
from app.tools.registry import ToolRegistry, infer_output_modality



LOGGER = logging.getLogger(__name__)





def tool_embedding_text(tool: ToolDescriptor) -> str:

    modality = infer_output_modality(tool) or ""

    hint_parts: list[str] = []

    for value in (tool.hints or {}).values():

        if isinstance(value, str):

            hint_parts.append(value)

        elif isinstance(value, (list, tuple, set)):

            hint_parts.extend(str(item) for item in value)

    return " ".join(

        part

        for part in (

            tool.toolCode,

            tool.toolName,

            tool.description or "",

            modality,

            " ".join(hint_parts),

        )

        if part

    )





def build_recall_query(context: RunContext) -> str:
    from app.routing.v2.thread_state import build_thread_state, format_thread_state_block

    parts = [context.message or ""]
    thread = build_thread_state(context)

    thread_block = format_thread_state_block(thread)

    if thread_block:

        parts.append(thread_block)

    history = list(context.history or [])

    if history:

        last = history[-1]

        parts.append(f"{last.role}: {last.content or ''}")

    return "\n".join(part for part in parts if part.strip())





def _tokenize(text: str) -> Counter[str]:

    tokens = re.findall(r"[\w\u4e00-\u9fff]{2,}", (text or "").lower())

    return Counter(tokens)





def _cosine(left: Counter[str], right: Counter[str]) -> float:

    if not left or not right:

        return 0.0

    shared = set(left) & set(right)

    dot = sum(left[t] * right[t] for t in shared)

    left_norm = math.sqrt(sum(v * v for v in left.values()))

    right_norm = math.sqrt(sum(v * v for v in right.values()))

    if left_norm == 0 or right_norm == 0:

        return 0.0

    return dot / (left_norm * right_norm)





@dataclass(slots=True)

class ToolEmbeddingIndex:

    tools: list[ToolDescriptor]

    vectors: dict[str, Counter[str]]



    @classmethod

    def from_context(cls, context: RunContext) -> ToolEmbeddingIndex:

        tools = list(ToolRegistry(context).list_tools())

        vectors = {tool.toolCode: _tokenize(tool_embedding_text(tool)) for tool in tools}

        return cls(tools=tools, vectors=vectors)



    def rank(self, query: str, *, limit: int) -> list[str]:

        query_vec = _tokenize(query)

        scored: list[tuple[float, str]] = []

        for tool in self.tools:

            score = _cosine(query_vec, self.vectors.get(tool.toolCode, Counter()))

            if score > 0:

                scored.append((score, tool.toolCode))

        scored.sort(key=lambda item: (-item[0], item[1]))

        if scored:

            return [code for _, code in scored[:limit]]

        return [tool.toolCode for tool in self.tools[:limit]]





async def embed_texts(model_client: Any, texts: list[str]) -> list[list[float]] | None:

    embed = getattr(model_client, "embed_texts", None)

    if not callable(embed):

        return None

    try:

        return await embed(texts)

    except Exception as exc:

        LOGGER.debug("embedding API unavailable: %s", exc)

        return None





async def recall_tool_codes_by_embedding(

    context: RunContext,

    model_client: Any | None = None,

    *,

    limit: int | None = None,

) -> list[str]:

    """Top-K tool codes via embedding recall, with lexical fallback."""

    cap = _limit(limit)

    query = build_recall_query(context)

    index = ToolEmbeddingIndex.from_context(context)

    if model_client is not None:

        texts = [query, *[tool_embedding_text(tool) for tool in index.tools]]

        vectors = await embed_texts(model_client, texts)

        if vectors and len(vectors) == len(texts):

            query_vec = vectors[0]

            scored: list[tuple[float, str]] = []

            for tool, vec in zip(index.tools, vectors[1:], strict=False):

                score = _vector_cosine(query_vec, vec)

                if score > 0:

                    scored.append((score, tool.toolCode))

            scored.sort(key=lambda item: (-item[0], item[1]))

            if scored:

                return [code for _, code in scored[:cap]]

    return index.rank(query, limit=cap)





def _vector_cosine(left: list[float], right: list[float]) -> float:

    if not left or not right or len(left) != len(right):

        return 0.0

    dot = sum(a * b for a, b in zip(left, right, strict=True))

    left_norm = math.sqrt(sum(a * a for a in left))

    right_norm = math.sqrt(sum(b * b for b in right))

    if left_norm == 0 or right_norm == 0:

        return 0.0

    return dot / (left_norm * right_norm)





def _limit(limit: int | None) -> int:

    if limit is not None:

        return max(1, limit)

    return max(1, int(getattr(settings, "agent_router_embedding_recall_k", 12)))


