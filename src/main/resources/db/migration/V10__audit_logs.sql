-- UNISAGE-60: immutable, append-only audit trail (AuditLogController + event persistence).
-- See docs/adr/0003-audit-log-persistence.md for the full design rationale:
--   - actor identity is denormalized (actor_id/actor_name/actor_code snapshotted at write time),
--     NOT a live FK to users(id), so a row keeps reading correctly even if the user is later
--     renamed or deleted;
--   - rows are insert-only (no updated_at/updated_by, no soft-delete flag) — a Hibernate
--     PostInsertEventListener/PostUpdateEventListener/PostDeleteEventListener captures every
--     entity write centrally, so nothing here is ever mutated by the application afterwards.

-- 1) audit_logs table.
CREATE TABLE public.audit_logs (
    id uuid NOT NULL,
    actor_id uuid,
    actor_name character varying(255),
    actor_code character varying(255),
    action character varying(50) NOT NULL,
    resource_type character varying(50) NOT NULL,
    resource_id character varying(255),
    details text,
    ip_address character varying(64),
    user_agent text,
    created_at timestamp(6) without time zone NOT NULL,
    CONSTRAINT audit_logs_action_check CHECK ((action)::text = ANY ((ARRAY[
        'CREATE', 'UPDATE', 'DELETE'
    ])::text[])),
    CONSTRAINT audit_logs_resource_type_check CHECK ((resource_type)::text = ANY ((ARRAY[
        'USER', 'ROLE', 'PERMISSION', 'DEPARTMENT', 'USER_DEPARTMENT_ACCESS', 'DOCUMENT', 'CATEGORY',
        'ACCESS_LEVEL', 'CHAT_MODEL', 'LLM_TRACE_LOG', 'CONVERSATION', 'MESSAGE', 'TICKET', 'AUDIT_LOG',
        'SYSTEM', 'OTHER'
    ])::text[]))
);

ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT audit_logs_pkey PRIMARY KEY (id);

-- Deliberately NOT a FK to users(id) — see ADR-0003; actor_id is a denormalized snapshot, kept
-- indexed for the actorId filter on GET /audit-logs but never enforced against a live user row.
CREATE INDEX idx_audit_logs_actor_id ON public.audit_logs (actor_id);
CREATE INDEX idx_audit_logs_resource_type ON public.audit_logs (resource_type);
CREATE INDEX idx_audit_logs_action ON public.audit_logs (action);
CREATE INDEX idx_audit_logs_created_at ON public.audit_logs (created_at);

-- 2) DataInitializer only seeds permissions the first time it runs (skipped once SUPER_ADMIN
--    exists), so an already-initialised dev database would never pick up AUDIT_LOG_ALL/READ even
--    though PredefinedPermissions/DataInitializer already define them. Seed idempotently here,
--    mirroring V8's TICKET_* precedent.
INSERT INTO public.permissions (id, created_at, is_active, name, path, method, resource_type, description)
SELECT gen_random_uuid(), now(), true, v.name, v.path, v.method, 'AUDIT_LOG', v.description
FROM (VALUES
    ('AUDIT_LOG_ALL',  '/audit-logs/**', 'ALL', 'Toàn quyền nhật ký hệ thống'),
    ('AUDIT_LOG_READ', '/audit-logs/**', 'GET', 'Xem nhật ký hệ thống')
) AS v(name, path, method, description)
WHERE NOT EXISTS (SELECT 1 FROM public.permissions p WHERE p.name = v.name);

INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM public.roles r, public.permissions p
WHERE r.name = 'SUPER_ADMIN' AND p.name = 'AUDIT_LOG_ALL'
ON CONFLICT DO NOTHING;
