-- UNISAGE-82: support tickets ("My Support Requests"). A user reports one AI answer (message) as a
-- ticket; staff with TICKET_* permissions move it through OPEN/PROCESSING/RESOLVED/CLOSED and write a
-- resolution. No assignee and no delete in this version.

-- 1) permissions.resource_type is guarded by a CHECK that V1 generated from the old enum, so
--    inserting a TICKET permission would fail. Rebuild it from the current ResourceType constants
--    plus TICKET (INGEST was removed by V4 and is no longer a valid value).
ALTER TABLE public.permissions DROP CONSTRAINT permissions_resource_type_check;
ALTER TABLE public.permissions
    ADD CONSTRAINT permissions_resource_type_check CHECK ((resource_type)::text = ANY ((ARRAY[
        'USER', 'ROLE', 'PERMISSION', 'DEPARTMENT', 'USER_DEPARTMENT_ACCESS', 'DOCUMENT', 'CATEGORY',
        'ACCESS_LEVEL', 'CHAT_MODEL', 'LLM_TRACE_LOG', 'CONVERSATION', 'MESSAGE', 'TICKET', 'AUDIT_LOG',
        'SYSTEM', 'OTHER'
    ])::text[]));

-- 2) tickets table.
CREATE TABLE public.tickets (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by uuid,
    is_active boolean,
    updated_at timestamp(6) without time zone,
    updated_by uuid,
    description text NOT NULL,
    resolution text,
    status character varying(255) NOT NULL,
    title character varying(200) NOT NULL,
    type character varying(255) NOT NULL,
    message_id uuid NOT NULL,
    user_id uuid NOT NULL,
    CONSTRAINT tickets_status_check CHECK ((status)::text = ANY ((ARRAY[
        'OPEN', 'PROCESSING', 'RESOLVED', 'CLOSED'
    ])::text[])),
    CONSTRAINT tickets_type_check CHECK ((type)::text = ANY ((ARRAY[
        'AI_SYSTEM_ERROR', 'AI_UNANSWERED', 'AI_SECURITY_BREACH', 'AI_INAPPROPRIATE', 'OTHER'
    ])::text[]))
);

ALTER TABLE ONLY public.tickets
    ADD CONSTRAINT tickets_pkey PRIMARY KEY (id);

-- One ticket per message; also what stops a double-click from creating two.
ALTER TABLE ONLY public.tickets
    ADD CONSTRAINT tickets_message_id_key UNIQUE (message_id);

ALTER TABLE ONLY public.tickets
    ADD CONSTRAINT fk_tickets_message FOREIGN KEY (message_id) REFERENCES public.messages(id),
    ADD CONSTRAINT fk_tickets_user FOREIGN KEY (user_id) REFERENCES public.users(id),
    ADD CONSTRAINT fk_tickets_created_by FOREIGN KEY (created_by) REFERENCES public.users(id),
    ADD CONSTRAINT fk_tickets_updated_by FOREIGN KEY (updated_by) REFERENCES public.users(id);

CREATE INDEX idx_tickets_user_id ON public.tickets (user_id);
CREATE INDEX idx_tickets_status ON public.tickets (status);

-- 3) DataInitializer skips itself once SUPER_ADMIN exists, so an already-initialised database would
--    never get the TICKET_* permissions. Seed them here (idempotent, by name) and grant TICKET_ALL
--    to SUPER_ADMIN, mirroring DataInitializer. On an empty database the roles do not exist yet, so
--    the grant matches nothing and DataInitializer assigns it on first start instead.
--    SUPER_ADMIN is already covered by the SUPER_ADMIN_ALL wildcard; these rows exist so the RBAC
--    screen lists them and custom roles can be granted ticket access.
INSERT INTO public.permissions (id, created_at, is_active, name, path, method, resource_type, description)
SELECT gen_random_uuid(), now(), true, v.name, v.path, v.method, 'TICKET', v.description
FROM (VALUES
    ('TICKET_ALL',    '/tickets/**', 'ALL',    'Toàn quyền yêu cầu hỗ trợ'),
    ('TICKET_READ',   '/tickets/**', 'GET',    'Xem yêu cầu hỗ trợ'),
    ('TICKET_CREATE', '/tickets',    'POST',   'Tạo yêu cầu hỗ trợ'),
    ('TICKET_UPDATE', '/tickets/**', 'PATCH',  'Cập nhật yêu cầu hỗ trợ'),
    ('TICKET_DELETE', '/tickets/**', 'DELETE', 'Xóa yêu cầu hỗ trợ')
) AS v(name, path, method, description)
WHERE NOT EXISTS (SELECT 1 FROM public.permissions p WHERE p.name = v.name);

INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM public.roles r, public.permissions p
WHERE r.name = 'SUPER_ADMIN' AND p.name = 'TICKET_ALL'
ON CONFLICT DO NOTHING;
