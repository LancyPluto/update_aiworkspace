# Database Backup And Restore

Production backups are encrypted MySQL logical dumps. Keep the backup bucket and encryption
password separate from the application asset buckets and application database credentials.

## Create A Backup

```bash
export BACKUP_ENCRYPTION_PASSWORD='from-secret-manager'
export BACKUP_OSS_URI='oss://independent-backup-bucket/mysql/full'
bash deploy/scripts/backup_mysql.sh
```

During development and controlled internal testing, `BACKUP_OSS_URI` remains optional and an
encrypted local backup can be used. In production, `verify_production_environment.sh` and the
standalone backup script both fail closed unless an encryption password and independent OSS URI
are configured.

Both settings become mandatory commercial-launch acceptance items: configure them in the server's
`deploy/.env`, and use an independent OSS location so the only recovery point is not stored on the
database host. Keeping these values out of the root `.env` also keeps them out of application
containers. The standalone backup and restore commands
remain fail-closed and never create or consume an unencrypted database dump.

The script creates an AES-256 encrypted dump and a versioned manifest containing encryption
parameters, schema migration checkpoint, size, and SHA-256. It confirms that both remote objects
exist after upload. The restore drill uses the checkpoint to validate only the tables that existed
when that backup was created. Local files default to `deploy/backup/files` and are retained for 14 days. Configure the OSS bucket with
server-side encryption, versioning, lifecycle retention, and a write-only backup identity.

## Restore Drill

```bash
export BACKUP_ENCRYPTION_PASSWORD='from-secret-manager'
export TARGET_DB='ai_supermarket_restore'
export BACKUP_FILE='/secure/path/ai_supermarket_v1_YYYYMMDDTHHMMSSZ.sql.gz.enc'
bash deploy/scripts/restore_mysql_to_staging.sh
```

The restore script refuses ordinary database names, recreates only a name ending in `_staging`,
`_restore`, or `_verify`, verifies both encrypted size and SHA-256, and checks core business and
workflow tables. It writes a permission-restricted report under `deploy/backup/restore-drills/`.
Record row counts, commit, backup timestamp, operator, sampling result, and cleanup time in the
monthly restore drill ticket.

## Operational Targets

- Full backup: daily, retain at least 14 daily and 3 monthly copies.
- Binlog archive: continuous, stored outside the database host.
- Initial target: RPO 15 minutes, RTO 2 hours; revise after measured restore drills.
- Alert when backup upload, checksum generation, or monthly restore verification fails.
