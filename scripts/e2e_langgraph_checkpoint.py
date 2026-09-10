#!/usr/bin/env python3
"""Real Backend/MySQL LangGraph checkpoint and execution-lease smoke test.

The script talks to the running Backend internal API; it does not use a fake
backend or write the checkpoint tables directly. Provide an existing run id:

    LANGGRAPH_E2E_RUN_ID=250 python scripts/e2e_langgraph_checkpoint.py
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "agent-service"))

from app.clients.backend_client import BackendClient
from app.config import Settings


async def main(run_id: int, base_url: str, token: str) -> None:
    client = BackendClient(Settings(backend_internal_base_url=base_url, internal_api_token=token))
    thread_id = f"agent-run:{run_id}"
    namespace = "e2e-langgraph"
    first_id = "e2e-langgraph-1"
    second_id = "e2e-langgraph-2"
    encoded = base64.b64encode(b"real-langgraph-e2e").decode("ascii")

    owners = await asyncio.gather(
        client.acquire_execution_lease(run_id, "e2e-owner-a", 60),
        client.acquire_execution_lease(run_id, "e2e-owner-b", 60),
    )
    assert sorted(owners) == [False, True], owners
    winner = "e2e-owner-a" if owners[0] else "e2e-owner-b"
    loser = "e2e-owner-b" if owners[0] else "e2e-owner-a"
    assert not await client.renew_execution_lease(run_id, loser, 60)
    assert await client.renew_execution_lease(run_id, winner, 60)

    try:
        await client.save_native_graph_checkpoint(
            run_id, thread_id, namespace, first_id, "msgpack", encoded,
            metadata_json='{"e2e":true,"revision":1}',
        )
        await client.save_native_graph_checkpoint_writes(
            run_id, thread_id, namespace, first_id, "task-e2e", "tools", [{
                "writeIndex": 0,
                "channelName": "messages",
                "valueType": "msgpack",
                "valueBase64": encoded,
            }],
        )
        await client.save_native_graph_checkpoint(
            run_id, thread_id, namespace, second_id, "msgpack", encoded,
            metadata_json='{"e2e":true,"revision":2}', parent_checkpoint_id=first_id,
        )

        selected = await client.load_native_graph_checkpoint(run_id, thread_id, namespace, first_id)
        history = await client.list_native_graph_checkpoints(run_id, thread_id, namespace, None, 10, {"e2e": True})
        assert selected and selected["checkpointId"] == first_id
        assert len(selected.get("pendingWrites", [])) == 1
        assert [row["checkpointId"] for row in history] == [second_id, first_id], history
        print("checkpoint_history=ok pending_writes=ok namespace=ok")
    finally:
        await client.clear_native_graph_checkpoint(run_id, thread_id)
        await client.release_execution_lease(run_id, winner)

    print("execution_lease=ok cleanup=ok")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-id", type=int, default=int(os.environ.get("LANGGRAPH_E2E_RUN_ID", "0")))
    parser.add_argument("--base-url", default=os.environ.get("BACKEND_INTERNAL_BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--token", default=os.environ.get("INTERNAL_API_TOKEN", "local-internal-token"))
    args = parser.parse_args()
    if not args.run_id:
        parser.error("--run-id or LANGGRAPH_E2E_RUN_ID is required")
    asyncio.run(main(args.run_id, args.base_url, args.token))
