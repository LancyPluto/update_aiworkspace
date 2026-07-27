# PPT Production Delivery Rules

These rules govern PPT-related deployment and release automation.

## Supply chain

- Production Banana images must come from the approved ACR repository and use an immutable `@sha256:` reference.
- The deployed reference must exactly match `engines/versions.lock.json`. Never substitute a tag, `latest`, an upstream image, or a locally built image.
- A missing fork commit, image digest, license snapshot, or `READY` release status is a hard deployment failure.
- Banana is an image-only production service: pull it; do not build it in the main repository delivery job.

## Runtime and secrets

- Include `docker-compose.ppt.yml` whenever PPT is enabled. A Compose profile without the overlay is invalid.
- Never place ACR passwords, platform tokens, model credentials, or smoke-user credentials in tracked files or command output.
- The engine receives only task-scoped execution tokens. Provider credentials remain in platform-owned services.
- `PPT_WORKBENCH_ENABLED` is the runtime kill switch. Disabling it blocks new projects and generation while preserving reads and downloads.

## Release gates

- Validate the engine lock, Compose interpolation, model capability availability, engine health, backend-to-engine connectivity, and authenticated PPT smoke generation before promoting a release.
- The production smoke test must create a small deck, wait for all automatic stages, download a valid OOXML ZIP, and delete the smoke project.
- Any PPT gate failure aborts promotion and invokes the normal application rollback. Database migrations remain additive and backward compatible.
- PR events run validation only. Only a push to `dev` may deploy production.
