# SQL Migration Rules

These rules apply to all new migrations, including the PPT workspace V2 schema.

## Immutability and ordering

- Never edit a migration that may have been applied. Add a new migration to repair or evolve it.
- Migration identity is the complete filename and checksum; numeric prefixes are ordering aids, not unique versions.
- PPT workspace V2 reserves `120` through `123` for its initial additive migrations.
- Use descriptive snake_case filenames and keep each migration focused on one dependency-safe schema change.

## Schema conventions

- Use `utf8mb4`, explicit primary keys, audit timestamps, and indexes for ownership and task polling queries.
- Add unique constraints for business idempotency, especially job submissions and engine bindings.
- Prefer additive nullable columns and backfills before introducing stricter constraints.
- Preserve legacy PPT tables during V2 rollout. Migration to V2 must be repeatable and must not delete source rows.
- Use soft deletion for user projects; do not cascade-delete billing, job, or audit history.
- JSON columns contain platform-owned specifications or sanitized engine snapshots, never secrets.

## Verification

- Mirror production schema changes in `backend/src/test/resources/schema-test.sql`.
- Test migrations on both an empty schema and a schema containing legacy PPT data.
- Verify migration checksums with `deploy/scripts/apply_sql_migrations.sh`.
- Include indexes for user project lists, active-job recovery, external job correlation, and idempotency lookup.

