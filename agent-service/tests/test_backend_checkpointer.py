import json

import pytest
from langgraph.checkpoint.base import empty_checkpoint

from app.runtime.agent_graph.backend_checkpointer import BackendCheckpointSaver


class MemoryCheckpointBackend:
    def __init__(self):
        self.rows = []
        self.writes = {}

    async def save_native_graph_checkpoint(self, run_id, thread_id, checkpoint_ns, checkpoint_id, checkpoint_serde,
                                           checkpoint_payload, metadata_json="{}", parent_checkpoint_id=None):
        self.rows = [row for row in self.rows if (row["threadId"], row["checkpointNs"], row["checkpointId"]) != (thread_id, checkpoint_ns, checkpoint_id)]
        self.rows.append({"threadId": thread_id, "checkpointNs": checkpoint_ns, "checkpointId": checkpoint_id,
                          "checkpointSerde": checkpoint_serde, "checkpointPayload": checkpoint_payload,
                          "metadataJson": metadata_json, "parentCheckpointId": parent_checkpoint_id})

    async def load_native_graph_checkpoint(self, run_id, thread_id, checkpoint_ns="", checkpoint_id=None):
        rows = [row for row in self.rows if row["threadId"] == thread_id and row["checkpointNs"] == checkpoint_ns]
        if checkpoint_id:
            rows = [row for row in rows if row["checkpointId"] == checkpoint_id]
        if not rows:
            return None
        row = dict(rows[-1])
        row["pendingWrites"] = self.writes.get((thread_id, checkpoint_ns, row["checkpointId"]), [])
        return row

    async def save_native_graph_checkpoint_writes(self, run_id, thread_id, checkpoint_ns, checkpoint_id, task_id, task_path, writes):
        key = (thread_id, checkpoint_ns, checkpoint_id)
        self.writes.setdefault(key, []).extend([{**write, "taskId": task_id, "taskPath": task_path} for write in writes])

    async def list_native_graph_checkpoints(self, run_id, thread_id, checkpoint_ns, before_checkpoint_id, limit, metadata_filter):
        rows = [row for row in self.rows if row["threadId"] == thread_id and row["checkpointNs"] == checkpoint_ns]
        if before_checkpoint_id:
            rows = rows[:next(i for i, row in enumerate(rows) if row["checkpointId"] == before_checkpoint_id)]
        return list(reversed(rows[-limit:] if limit else rows))

    async def clear_native_graph_checkpoint(self, run_id, thread_id):
        self.rows = [row for row in self.rows if row["threadId"] != thread_id]


@pytest.mark.asyncio
async def test_saver_keeps_checkpoint_history_namespaces_and_typed_pending_writes():
    backend = MemoryCheckpointBackend()
    saver = BackendCheckpointSaver(backend, run_id=7, thread_id="agent-run:7")
    first = empty_checkpoint()
    first["channel_values"] = {"message": {"structured": [1, 2]}}
    first_config = await saver.aput({"configurable": {"thread_id": "agent-run:7"}}, first, {"step": 1}, {})
    await saver.aput_writes(first_config, [("result", {"nested": ["kept", 2]})], "node-1")
    second = empty_checkpoint()
    second["channel_values"] = {"message": "new"}
    second_config = await saver.aput(first_config, second, {"step": 2}, {})
    other = empty_checkpoint()
    await saver.aput({"configurable": {"thread_id": "agent-run:7", "checkpoint_ns": "child"}}, other, {"step": 1}, {})

    loaded = await saver.aget_tuple(first_config)
    assert loaded is not None
    assert loaded.pending_writes == [("node-1", "result", {"nested": ["kept", 2]})]
    assert (await saver.aget_tuple(second_config)).checkpoint["id"] == second["id"]
    history = [item async for item in saver.alist({"configurable": {"thread_id": "agent-run:7"}}, limit=2)]
    assert [item.checkpoint["id"] for item in history] == [second["id"], first["id"]]
    child = await saver.aget_tuple({"configurable": {"thread_id": "agent-run:7", "checkpoint_ns": "child"}})
    assert child is not None and child.checkpoint["id"] == other["id"]
