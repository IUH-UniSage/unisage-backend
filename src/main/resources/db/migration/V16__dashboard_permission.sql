-- UNISAGE-72: dashboard summary API. DataInitializer only seeds permissions the first time it
-- runs (skipped once SUPER_ADMIN exists), so seed idempotently here too, mirroring
-- V13__system_health_checks.sql's SYSTEM_HEALTH_READ precedent.
INSERT INTO public.permissions (id, created_at, is_active, name, path, method, resource_type, description)
SELECT gen_random_uuid(), now(), true, v.name, v.path, v.method, 'SYSTEM', v.description
FROM (VALUES
    ('DASHBOARD_READ', '/admin/dashboard/**', 'GET', 'Xem tổng quan hệ thống')
) AS v(name, path, method, description)
WHERE NOT EXISTS (SELECT 1 FROM public.permissions p WHERE p.name = v.name);

-- Read-only, no _ALL wildcard — SUPER_ADMIN is granted explicitly, mirroring
-- DataInitializer.assignSuperAdmin's explicit grant for SystemHealth.
INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM public.roles r, public.permissions p
WHERE r.name = 'SUPER_ADMIN' AND p.name = 'DASHBOARD_READ'
ON CONFLICT DO NOTHING;
