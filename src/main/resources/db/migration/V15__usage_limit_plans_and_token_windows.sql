-- Usage limits move from a per-day message count (config driven) to token quotas defined by plans.
--
-- 1. usage_limit_plans: named quotas; roles point at one, everyone else falls back to the single
--    default plan (enforced by a partial unique index).
-- 2. roles.usage_limit_plan_id: nullable; null = use the default plan.
-- 3. usage_limits is DROPPED AND RECREATED. This is destructive: every existing per-user / per-guest
--    counter is lost, so everyone starts with a full quota after this migration. No other table
--    references usage_limits (it only has foreign keys to users and guest_sessions), so no other data
--    is lost. Take a backup first on any environment that holds real usage data.

CREATE TABLE public.usage_limit_plans (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by uuid,
    updated_at timestamp(6) without time zone,
    updated_by uuid,
    is_active boolean,
    name character varying(255) NOT NULL,
    daily_token_limit bigint,
    weekly_token_limit bigint,
    is_default boolean DEFAULT false NOT NULL,
    CONSTRAINT usage_limit_plans_pkey PRIMARY KEY (id),
    CONSTRAINT usage_limit_plans_name_key UNIQUE (name),
    CONSTRAINT usage_limit_plans_daily_positive CHECK (daily_token_limit IS NULL OR daily_token_limit > 0),
    CONSTRAINT usage_limit_plans_weekly_positive CHECK (weekly_token_limit IS NULL OR weekly_token_limit > 0),
    CONSTRAINT fk_usage_limit_plans_created_by FOREIGN KEY (created_by) REFERENCES public.users(id),
    CONSTRAINT fk_usage_limit_plans_updated_by FOREIGN KEY (updated_by) REFERENCES public.users(id)
);

-- At most one default plan; the application refuses to start if there is none.
CREATE UNIQUE INDEX usage_limit_plans_single_default ON public.usage_limit_plans (is_default) WHERE is_default;

INSERT INTO public.usage_limit_plans (id, created_at, is_active, name, daily_token_limit, weekly_token_limit, is_default)
VALUES
    (gen_random_uuid(), now(), true, 'Mặc định', 30000, 150000, true),
    (gen_random_uuid(), now(), true, 'Không giới hạn', NULL, NULL, false);

ALTER TABLE public.roles ADD COLUMN usage_limit_plan_id uuid;
ALTER TABLE public.roles
    ADD CONSTRAINT fk_roles_usage_limit_plan FOREIGN KEY (usage_limit_plan_id) REFERENCES public.usage_limit_plans(id);

-- Databases initialised earlier already have the admin roles; fresh databases get the same
-- assignment from DataInitializer (which skips itself once SUPER_ADMIN exists).
UPDATE public.roles
SET usage_limit_plan_id = (SELECT id FROM public.usage_limit_plans WHERE name = 'Không giới hạn')
WHERE name IN ('SUPER_ADMIN', 'INGEST_ADMIN');

DROP TABLE public.usage_limits;

CREATE TABLE public.usage_limits (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by uuid,
    updated_at timestamp(6) without time zone,
    updated_by uuid,
    is_active boolean,
    user_id uuid,
    guest_session_id uuid,
    window_type character varying(255) NOT NULL,
    window_start timestamp(6) without time zone,
    used_tokens bigint DEFAULT 0 NOT NULL,
    CONSTRAINT usage_limits_pkey PRIMARY KEY (id),
    CONSTRAINT usage_limits_window_type_check CHECK (window_type IN ('DAILY', 'WEEKLY')),
    CONSTRAINT usage_limits_owner_xor CHECK ((user_id IS NULL) <> (guest_session_id IS NULL)),
    CONSTRAINT usage_limits_user_window_unique UNIQUE (user_id, window_type),
    CONSTRAINT usage_limits_guest_window_unique UNIQUE (guest_session_id, window_type),
    CONSTRAINT fk_usage_limits_user FOREIGN KEY (user_id) REFERENCES public.users(id),
    CONSTRAINT fk_usage_limits_guest_session FOREIGN KEY (guest_session_id) REFERENCES public.guest_sessions(id),
    CONSTRAINT fk_usage_limits_created_by FOREIGN KEY (created_by) REFERENCES public.users(id),
    CONSTRAINT fk_usage_limits_updated_by FOREIGN KEY (updated_by) REFERENCES public.users(id)
);

-- ResourceType gains USAGE_LIMIT_PLAN. Both tables that store the enum name are guarded by a CHECK
-- built from the old constants, so rebuild them (same list as V12__system_configs plus the new value).
ALTER TABLE public.permissions DROP CONSTRAINT permissions_resource_type_check;
ALTER TABLE public.permissions
    ADD CONSTRAINT permissions_resource_type_check CHECK ((resource_type)::text = ANY ((ARRAY[
        'USER', 'ROLE', 'PERMISSION', 'DEPARTMENT', 'USER_DEPARTMENT_ACCESS', 'DOCUMENT', 'CATEGORY',
        'ACCESS_LEVEL', 'CHAT_MODEL', 'LLM_TRACE_LOG', 'CONVERSATION', 'MESSAGE', 'USAGE_LIMIT_PLAN',
        'TICKET', 'AUDIT_LOG', 'SYSTEM', 'SYSTEM_CONFIG', 'OTHER'
    ])::text[]));

ALTER TABLE public.audit_logs DROP CONSTRAINT audit_logs_resource_type_check;
ALTER TABLE public.audit_logs
    ADD CONSTRAINT audit_logs_resource_type_check CHECK ((resource_type)::text = ANY ((ARRAY[
        'USER', 'ROLE', 'PERMISSION', 'DEPARTMENT', 'USER_DEPARTMENT_ACCESS', 'DOCUMENT', 'CATEGORY',
        'ACCESS_LEVEL', 'CHAT_MODEL', 'LLM_TRACE_LOG', 'CONVERSATION', 'MESSAGE', 'USAGE_LIMIT_PLAN',
        'TICKET', 'AUDIT_LOG', 'SYSTEM', 'SYSTEM_CONFIG', 'OTHER'
    ])::text[]));

-- DataInitializer skips itself once SUPER_ADMIN exists, so an already-initialised database would never
-- get the USAGE_LIMIT_PLAN_* permissions. Seed them here (idempotent, by name) and grant
-- USAGE_LIMIT_PLAN_ALL to SUPER_ADMIN, mirroring DataInitializer. On an empty database the roles do not
-- exist yet, so the grant matches nothing and DataInitializer assigns it on first start instead.
INSERT INTO public.permissions (id, created_at, is_active, name, path, method, resource_type, description)
SELECT gen_random_uuid(), now(), true, v.name, v.path, v.method, 'USAGE_LIMIT_PLAN', v.description
FROM (VALUES
    ('USAGE_LIMIT_PLAN_ALL',    '/usage-limit-plans/**', 'ALL',    'Toàn quyền gói hạn mức'),
    ('USAGE_LIMIT_PLAN_READ',   '/usage-limit-plans/**', 'GET',    'Xem gói hạn mức'),
    ('USAGE_LIMIT_PLAN_CREATE', '/usage-limit-plans',    'POST',   'Tạo gói hạn mức'),
    ('USAGE_LIMIT_PLAN_UPDATE', '/usage-limit-plans/**', 'PUT',    'Cập nhật gói hạn mức'),
    ('USAGE_LIMIT_PLAN_DELETE', '/usage-limit-plans/**', 'DELETE', 'Xóa gói hạn mức')
) AS v(name, path, method, description)
WHERE NOT EXISTS (SELECT 1 FROM public.permissions p WHERE p.name = v.name);

INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM public.roles r, public.permissions p
WHERE r.name = 'SUPER_ADMIN' AND p.name = 'USAGE_LIMIT_PLAN_ALL'
ON CONFLICT DO NOTHING;

-- The on/off switch is the admin-editable system setting chat.usage_limit.enabled (V12__system_configs).
-- It now switches token quotas, so re-label it, and drop the two per-day message-count settings that no
-- longer control anything (quotas come from plans). A switch nobody has edited yet is turned on so the
-- feature is effective after release; one an admin already touched is left as they set it.
UPDATE public.system_configs
SET label = 'Bật giới hạn hạn mức sử dụng',
    description = 'Bật/tắt giới hạn lượng token trò chuyện theo gói hạn mức (24 giờ và 7 ngày).'
WHERE config_key = 'chat.usage_limit.enabled';

UPDATE public.system_configs
SET value = 'true'
WHERE config_key = 'chat.usage_limit.enabled' AND value = 'false' AND updated_at IS NULL;

DELETE FROM public.system_configs
WHERE config_key IN ('chat.usage_limit.user_daily_limit', 'chat.usage_limit.guest_daily_limit');
