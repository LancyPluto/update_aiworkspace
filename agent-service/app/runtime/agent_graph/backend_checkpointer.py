from __future__ import annotations

import base64
import json
from collections.abc import AsyncIterator, Iterator, Sequence
from typing import Any

from langgraph.checkpoint.base import BaseCheckpointSaver, Checkpoint, CheckpointMetadata, CheckpointTuple, ChannelVersions, PendingWrite


class BackendCheckpointSaver(BaseCheckpointSaver[str]):
    """Durable LangGraph saver backed by the backend checkpoint history API."""

    def __init__(self, backend, *, run_id: int, thread_id: str) -> None:
        super().__init__()
        self.backend = backend
        self.run_id = run_id
        self.thread_id = thread_id

    @staticmethod
    def _values(config: dict[str, Any]) -> tuple[str, str, str]:
        values = config.get("configurable", {})
        return (str(values.get("thread_id") or ""), str(values.get("checkpoint_ns") or ""), str(values.get("checkpoint_id") or ""))

    def _config(self, checkpoint_id: str, checkpoint_ns: str = "") -> dict[str, Any]:
        return {"configurable": {"thread_id": self.thread_id, "checkpoint_ns": checkpoint_ns, "checkpoint_id": checkpoint_id}}

    def _row_to_tuple(self, row: dict[str, Any]) -> CheckpointTuple:
        checkpoint = self.serde.loads_typed((str(row["checkpointSerde"]), base64.b64decode(str(row["checkpointPayload"]))))
        metadata = json.loads(row.get("metadataJson") or "{}")
        writes: list[PendingWrite] = []
        for write in row.get("pendingWrites") or []:
            value = self.serde.loads_typed((str(write["valueType"]), base64.b64decode(str(write["valueBase64"]))))
            writes.append((str(write["taskId"]), str(write["channelName"]), value))
        namespace = str(row.get("checkpointNs") or "")
        parent = row.get("parentCheckpointId")
        return CheckpointTuple(self._config(str(row["checkpointId"]), namespace), checkpoint, metadata,
                               self._config(str(parent), namespace) if parent else None, writes)

    async def aget_tuple(self, config):
        thread_id, namespace, checkpoint_id = self._values(config)
        if thread_id != self.thread_id:
            return None
        row = await self.backend.load_native_graph_checkpoint(self.run_id, self.thread_id, namespace, checkpoint_id or None)
        return self._row_to_tuple(row) if row else None

    async def aput(self, config, checkpoint: Checkpoint, metadata: CheckpointMetadata, new_versions: ChannelVersions):
        thread_id, namespace, parent_id = self._values(config)
        if thread_id != self.thread_id:
            raise ValueError("checkpoint thread_id does not belong to this saver")
        serde, blob = self.serde.dumps_typed(checkpoint)
        checkpoint_id = str(checkpoint["id"])
        await self.backend.save_native_graph_checkpoint(
            self.run_id, self.thread_id, namespace, checkpoint_id, str(serde), base64.b64encode(blob).decode("ascii"),
            metadata_json=json.dumps(metadata, ensure_ascii=False, separators=(",", ":")), parent_checkpoint_id=parent_id or None,
        )
        return self._config(checkpoint_id, namespace)

    async def aput_writes(self, config, writes: Sequence[tuple[str, Any]], task_id: str, task_path: str = ""):
        thread_id, namespace, checkpoint_id = self._values(config)
        if thread_id != self.thread_id or not checkpoint_id:
            raise ValueError("pending writes require this saver's checkpoint config")
        encoded = []
        for index, (channel, value) in enumerate(writes):
            value_type, blob = self.serde.dumps_typed(value)
            encoded.append({"writeIndex": index, "channelName": channel, "valueType": str(value_type), "valueBase64": base64.b64encode(blob).decode("ascii")})
        if encoded:
            await self.backend.save_native_graph_checkpoint_writes(self.run_id, self.thread_id, namespace, checkpoint_id, task_id, task_path, encoded)

    async def alist(self, config, *, filter=None, before=None, limit=None) -> AsyncIterator[CheckpointTuple]:
        thread_id, namespace, _ = self._values(config or {"configurable": {"thread_id": self.thread_id}})
        if thread_id != self.thread_id:
            return
        before_id = self._values(before)[2] if before else None
        rows = await self.backend.list_native_graph_checkpoints(self.run_id, self.thread_id, namespace, before_id or None, limit, filter or {})
        for row in rows:
            yield self._row_to_tuple(row)

    async def adelete_thread(self, thread_id: str) -> None:
        if thread_id == self.thread_id:
            await self.backend.clear_native_graph_checkpoint(self.run_id, thread_id)

    def get_tuple(self, config): raise RuntimeError("BackendCheckpointSaver is async-only")
    def put(self, config, checkpoint, metadata, new_versions): raise RuntimeError("BackendCheckpointSaver is async-only")
    def put_writes(self, config, writes, task_id, task_path=""): raise RuntimeError("BackendCheckpointSaver is async-only")
    def list(self, config, *, filter=None, before=None, limit=None) -> Iterator[CheckpointTuple]: raise RuntimeError("BackendCheckpointSaver is async-only")
