# PPT Engine Dependency Rules

This directory contains contracts and deployment metadata for external rendering engines.

## Source and release governance

- Do not vendor an untracked Banana checkout, rsync upstream files, or run an unpinned `git pull` during deployment.
- Product modifications belong in the organization Banana Slides fork. Preserve a clean upstream-sync branch and a reviewed product branch.
- Production consumes an Alibaba Cloud ACR image pinned by digest. Tags are informational and must not be the deployment identity.
- Local development may build the same reviewed commit, but must record that commit in `versions.lock.json`.
- Store OpenAPI snapshots, representative fixtures, version metadata, LICENSE, and NOTICE information in the main repository.
- Never commit `.env`, credentials, cookies, API keys, generated user decks, or engine databases.

## Runtime contract

- Engines are internal services and must not be exposed directly to the public browser.
- Every engine must provide a health endpoint and bounded connect/read timeouts.
- The platform backend owns authentication, projects, jobs, billing, exports, and user-visible errors.
- The platform model catalog and vendor accounts are the sole configuration source for LLM and image providers.
- Product engines must use the `KCD_PLATFORM` provider and task-scoped execution tokens. They must not receive, persist, log, or expose provider API keys.
- Global engine settings are not a production model-integration mechanism. Each submission carries its platform project ID, PPT job ID, idempotency key, gateway URL, and short-lived token.
- The platform gateway owns provider routing, failover, usage accounting, and normalized errors. Engines own only PPT orchestration and rendering.
- Contract changes require fixture updates and backend adapter contract tests before the pinned version changes.
- Deployment configuration must include resource limits, log rotation, restart policy, and a non-public network.
- The Banana web UI stays disabled in production; only the health and project API contract may be reachable from the backend network.

## Version lock requirements

Each engine entry records:

- upstream and fork repository URLs;
- reviewed source commit SHA;
- immutable image reference and digest;
- API contract version;
- license and notice paths;
- build timestamp and supported platform capabilities.
