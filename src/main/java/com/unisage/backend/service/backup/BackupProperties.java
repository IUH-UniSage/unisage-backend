package com.unisage.backend.service.backup;

import java.nio.file.Path;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.Getter;

/**
 * Deployment-level backup settings ({@code app.backup.*}): where the tools, files and services
 * are. What to back up and when lives in system_configs instead, see {@link BackupSettings}.
 */
@Component
@Getter
public class BackupProperties {

    @Value("${app.backup.bucket:unisage-backups}")
    private String bucket;

    /** Scratch space for one backup at a time; each run deletes its own folder afterwards. */
    @Value("${app.backup.work-dir:${java.io.tmpdir}/unisage-backup}")
    private Path workDir;

    /** Folder holding pg_basebackup; blank means it is on the PATH (the Docker image). */
    @Value("${app.backup.pg-bin-dir:}")
    private String pgBinDir;

    @Value("${app.backup.pg-timeout-minutes:120}")
    private long pgTimeoutMinutes;

    @Value("${app.backup.db.host:${DB_HOST:localhost}}")
    private String dbHost;

    @Value("${app.backup.db.port:${DB_PORT:5432}}")
    private int dbPort;

    /** Needs the REPLICATION attribute (a superuser has it) for pg_basebackup. */
    @Value("${app.backup.db.user:${DB_USER:postgres}}")
    private String dbUser;

    @Value("${app.backup.db.password:${DB_PASSWORD:}}")
    private String dbPassword;

    /** The folder the Postgres archive_command writes to, mounted into this container. */
    @Value("${app.backup.wal-archive-dir:}")
    private String walArchiveDir;

    /**
     * The folder shared with the unisage-db container at /restore (ADR-0010). Blank means a
     * Postgres restore cannot be started from the app.
     */
    @Value("${app.backup.restore-dir:}")
    private String restoreDir;

    /** How long a prepared restore may wait for the unisage-db container to pick it up. */
    @Value("${app.backup.restore-handoff-minutes:5}")
    private long restoreHandoffMinutes;

    @Value("${app.backup.qdrant.url:http://localhost:6333}")
    private String qdrantUrl;

    @Value("${app.backup.qdrant.api-key:}")
    private String qdrantApiKey;

    @Value("${app.backup.qdrant.timeout-minutes:30}")
    private long qdrantTimeoutMinutes;

    /** Backup folder and file names carry this wall time, so they read like the admin page. */
    @Value("${app.timezone:Asia/Ho_Chi_Minh}")
    private ZoneId zone;
}
