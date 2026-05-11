# Agent Service

FastAPI service for the Cloud Universal Agent.

## Stack

- FastAPI for the internal HTTP API.
- LangGraph for the universal agent graph when the dependency is installed.
- LangChain-compatible message/tool boundaries for model and tool orchestration.
- Deep Agents is reserved as a later enhancement layer for planning, sub-agents, memory, and richer workspace context.

## Local Run

```powershell
pip install -r requirements.txt
python -m uvicorn app.main:app --reload --port 8090
```

```powershell
curl http://localhost:8090/health
```

## Tests

```powershell
pytest -q
```

## Runtime Boundary

Spring Boot owns users, sessions, runs, tools, credits, persistence, and audit. This service only loads run context through signed internal APIs, routes intent, executes the LangGraph flow, calls allowed tools through Spring Boot callbacks, and completes or fails the run.
