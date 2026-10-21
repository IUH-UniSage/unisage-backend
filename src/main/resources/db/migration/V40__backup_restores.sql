-- UNISAGE-102 (ADR-0010): restoring a backup from the "Sao lưu & khôi phục" admin page.

-- 1) One row per restore an admin started. Like backup_runs, written by the backup runner rather
--    than through a user-facing CRUD, so no BaseEntity/audit columns. A Postgres restore replaces
--    this very table with the backup's copy; PostgresRestoreService writes the rows back afterwards.
CREATE TABLE public.restore_runs (
    id uuid NOT NULL,
    -- POSTGRES or QDRANT.
    restore_type character varying(32) NOT NULL,
    -- RUNNING / SUCCEEDED / FAILED, like backup_runs.
    status character varying(16) NOT NULL,
    -- The backup restored: a FULL/DIFFERENTIAL run for Postgres, a QDRANT_SNAPSHOT run for Qdrant.
    -- No foreign key: the restored database may not contain that row yet.
    source_run_id uuid,
    -- Qdrant collection; NULL for Postgres.
    target character varying(255),
    -- Point-in-time target (UTC); NULL restores the backup itself.
    target_time timestamp(6) without time zone,
    -- Backup of the state just before the restore, so the restore can itself be undone.
    safety_run_id uuid,
    requested_by uuid,
    requested_by_code character varying(64),
    started_at timestamp(6) without time zone NOT NULL,
    finished_at timestamp(6) without time zone,
    error_message text
);

ALTER TABLE ONLY public.restore_runs
    ADD CONSTRAINT restore_runs_pkey PRIMARY KEY (id);

CREATE INDEX idx_restore_runs_started_at ON public.restore_runs (started_at DESC);

-- 2) Permissions for /admin/backups/**. DataInitializer skips itself on an initialised database,
--    so seed them here (idempotent, by name) and grant BACKUP_ALL to SUPER_ADMIN, as V8 does for
--    tickets. On an empty database the role does not exist yet and DataInitializer grants it.
INSERT INTO public.permissions (id, created_at, is_active, name, path, method, resource_type, description)
SELECT gen_random_uuid(), now(), true, v.name, v.path, v.method, 'SYSTEM', v.description
FROM (VALUES
    ('BACKUP_ALL',  '/admin/backups/**', 'ALL', 'Toàn quyền sao lưu và khôi phục dữ liệu'),
    ('BACKUP_READ', '/admin/backups/**', 'GET', 'Xem lịch sử sao lưu và khôi phục')
) AS v(name, path, method, description)
WHERE NOT EXISTS (SELECT 1 FROM public.permissions p WHERE p.name = v.name);

INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM public.roles r, public.permissions p
WHERE r.name = 'SUPER_ADMIN' AND p.name = 'BACKUP_ALL'
ON CONFLICT DO NOTHING;
