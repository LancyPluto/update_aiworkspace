# Database Backup And Restore

Production backups are encrypted MySQL logical dumps. Keep the backup bucket and encryption
password separate from the application asset buckets and application database credentials.

## Create A Backup

```bash
export BACKUP_ENCRYPTION_PASSWORD='from-secret-manager'
export BACKUP_OSS_URI='oss://independent-backup-bucket/mysql/full'
bash deploy/scripts/backup_mysql.sh
```

During development and controlled internal testing, both `BACKUP_ENCRYPTION_PASSWORD` and
`BACKUP_OSS_URI` are optional so missing infrastructure does not block team delivery. Without an
encryption password, automated deployment logs a warning and skips the pre-migration backup. With
a password but no OSS URI, it creates an encrypted local backup only.

Both settings become mandatory commercial-launch acceptance items: configure the password in the
server's root `.env` or `deploy/.env`, and configure an independent OSS location so the only
recovery point is not stored on the database host. The standalone backup and restore commands
remain fail-closed and never create or consume an unencrypted database dump.

The script creates an AES-256 encrypted dump and a manifest containing size and SHA-256. Local
files default to `deploy/backup/files` and are retained for 14 days. Configure the OSS bucket with
server-side encryption, versioning, lifecycle retention, and a write-only backup identity.

## Restore Drill

```bash
export BACKUP_ENCRYPTION_PASSWORD='from-secret-manager'
export TARGET_DB='ai_supermarket_restore'
export BACKUP_FILE='/secure/path/ai_supermarket_v1_YYYYMMDDTHHMMSSZ.sql.gz.enc'
bash deploy/scripts/restore_mysql_to_staging.sh
```

The restore script refuses ordinary database names, recreates only a name ending in `_staging`,
`_restore`, or `_verify`, and checks the users, balances, recharge orders, tasks, credit logs, and
settings tables. Record row counts, elapsed time, commit, backup timestamp, and operator in the
monthly restore drill ticket.

## Operational Targets

- Full backup: daily, retain at least 14 daily and 3 monthly copies.
- Binlog archive: continuous, stored outside the database host.
- Initial target: RPO 15 minutes, RTO 2 hours; revise after measured restore drills.
- Alert when backup upload, checksum generation, or monthly restore verification fails.
