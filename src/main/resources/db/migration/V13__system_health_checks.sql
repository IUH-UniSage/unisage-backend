-- UNISAGE-62: system health-check history. Immutable, insert-only time-series log written by
-- SystemHealthCheckJob (every app.health-check.history.cron, default 5 min) so past downtime is
-- queryable via GET /admin/health/history, not just a live status snapshot.
-- Plain table, no BaseEntity/audit columns: SystemHealthCheck is never updated after being
-- written and must not itself be audited (same reasoning as audit_logs, see V10).

-- 1) system_health_checks table.
CREATE TABLE public.system_health_checks (
    id uuid NOT NULL,
    checked_at timestamp(6) without time zone NOT NULL,
    overall_status character varying(32) NOT NULL,
    components_json text NOT NULL
);

ALTER TABLE ONLY public.system_health_checks
    ADD CONSTRAINT system_health_checks_pkey PRIMARY KEY (id);

-- History queries filter/sort by checked_at (GET /admin/health/history?from=&to=).
CREATE INDEX idx_system_health_checks_checked_at ON public.system_health_checks (checked_at DESC);

-- 2) SYSTEM_HEALTH_READ permission. DataInitializer only seeds permissions the first time it
--    runs (skipped once SUPER_ADMIN exists), so seed idempotently here too, mirroring
--    V12__system_configs.sql's SYSTEM_CONFIG_* precedent.
INSERT INTO public.permissions (id, created_at, is_active, name, path, method, resource_type, description)
SELECT gen_random_uuid(), now(), true, v.name, v.path, v.method, 'SYSTEM', v.description
FROM (VALUES
    ('SYSTEM_HEALTH_READ', '/admin/health/**', 'GET', 'Xem tình trạng hệ thống')
) AS v(name, path, method, description)
WHERE NOT EXISTS (SELECT 1 FROM public.permissions p WHERE p.name = v.name);

-- Read-only, no _ALL wildcard — SUPER_ADMIN is granted explicitly, mirroring
-- DataInitializer.assignSuperAdmin's explicit grant for SystemConfig.
INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM public.roles r, public.permissions p
WHERE r.name = 'SUPER_ADMIN' AND p.name = 'SYSTEM_HEALTH_READ'
ON CONFLICT DO NOTHING;
