"""Execution identity is task-local: the shared BackendClient must never store an owner."""
import asyncio
from contextvars import ContextVar

execution_identity: ContextVar[tuple[int, str] | None] = ContextVar("agent_execution_identity", default=None)
operation_identity: ContextVar[str | None] = ContextVar("agent_operation_identity", default=None)
recovering: ContextVar[bool] = ContextVar("agent_recovering", default=False)


class LeaseLost(asyncio.CancelledError):
    """Stop without failing the run or cancelling an external task."""


class RecoveryDeferred(RuntimeError):
    pass


class RecoveryUnsafe(RuntimeError):
    pass
