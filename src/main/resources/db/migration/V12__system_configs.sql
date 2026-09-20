-- UNISAGE-64: generic key-value system configuration table. GET/PUT-only API — new keys are
-- added via a future migration, not through the API (see SystemConfigController).
-- Plain BaseEntity subclass: AuditHibernateIntegrator (UNISAGE-60) captures every UPDATE
-- automatically once the resource_type value below is a valid CHECK-constraint member.

-- 1) permissions.resource_type / audit_logs.resource_type CHECKs were generated from the old
--    ResourceType enum; rebuild both to allow the new SYSTEM_CONFIG value, mirroring V8/V10's
--    precedent for adding a resource type.
ALTER TABLE public.permissions DROP CONSTRAINT permissions_resource_type_check;
ALTER TABLE public.permissions
    ADD CONSTRAINT permissions_resource_type_check CHECK ((resource_type)::text = ANY ((ARRAY[
        'USER', 'ROLE', 'PERMISSION', 'DEPARTMENT', 'USER_DEPARTMENT_ACCESS', 'DOCUMENT', 'CATEGORY',
        'ACCESS_LEVEL', 'CHAT_MODEL', 'LLM_TRACE_LOG', 'CONVERSATION', 'MESSAGE', 'TICKET', 'AUDIT_LOG',
        'SYSTEM', 'SYSTEM_CONFIG', 'OTHER'
    ])::text[]));

ALTER TABLE public.audit_logs DROP CONSTRAINT audit_logs_resource_type_check;
ALTER TABLE public.audit_logs
    ADD CONSTRAINT audit_logs_resource_type_check CHECK ((resource_type)::text = ANY ((ARRAY[
        'USER', 'ROLE', 'PERMISSION', 'DEPARTMENT', 'USER_DEPARTMENT_ACCESS', 'DOCUMENT', 'CATEGORY',
        'ACCESS_LEVEL', 'CHAT_MODEL', 'LLM_TRACE_LOG', 'CONVERSATION', 'MESSAGE', 'TICKET', 'AUDIT_LOG',
        'SYSTEM', 'SYSTEM_CONFIG', 'OTHER'
    ])::text[]));

-- 2) system_configs table.
CREATE TABLE public.system_configs (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by uuid,
    is_active boolean,
    updated_at timestamp(6) without time zone,
    updated_by uuid,
    config_key character varying(255) NOT NULL,
    value text NOT NULL,
    value_type character varying(20) NOT NULL,
    category character varying(20) NOT NULL,
    label character varying(255) NOT NULL,
    description character varying(500),
    is_editable boolean NOT NULL DEFAULT true,
    CONSTRAINT system_configs_value_type_check CHECK ((value_type)::text = ANY ((ARRAY[
        'STRING', 'NUMBER', 'BOOLEAN', 'JSON'
    ])::text[])),
    CONSTRAINT system_configs_category_check CHECK ((category)::text = ANY ((ARRAY[
        'GENERAL', 'SECURITY', 'INGEST', 'CHAT', 'AUDIT', 'MAINTENANCE'
    ])::text[]))
);

ALTER TABLE ONLY public.system_configs
    ADD CONSTRAINT system_configs_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.system_configs
    ADD CONSTRAINT system_configs_config_key_key UNIQUE (config_key);

ALTER TABLE ONLY public.system_configs
    ADD CONSTRAINT fk_system_configs_created_by FOREIGN KEY (created_by) REFERENCES public.users(id),
    ADD CONSTRAINT fk_system_configs_updated_by FOREIGN KEY (updated_by) REFERENCES public.users(id);

CREATE INDEX idx_system_configs_category ON public.system_configs (category);

-- 3) Seed the 11 known settings, one row per currently-hardcoded @Value-injected property (see
--    UNISAGE-64 ticket notes for the source of each current value). This ticket only makes these
--    values visible/editable via the API above — no consuming service reads from this table yet.
INSERT INTO public.system_configs
    (id, created_at, is_active, config_key, value, value_type, category, label, description, is_editable)
VALUES
    (gen_random_uuid(), now(), true,
     'chat.usage_limit.enabled', 'false', 'BOOLEAN', 'CHAT',
     'Bật giới hạn số tin nhắn',
     'Bật/tắt giới hạn số tin nhắn được gửi mỗi ngày.', true),

    (gen_random_uuid(), now(), true,
     'chat.usage_limit.user_daily_limit', '30', 'NUMBER', 'CHAT',
     'Giới hạn tin nhắn/ngày (người dùng)',
     'Số tin nhắn tối đa một người dùng đã đăng nhập được gửi mỗi ngày.', true),

    (gen_random_uuid(), now(), true,
     'chat.usage_limit.guest_daily_limit', '10', 'NUMBER', 'CHAT',
     'Giới hạn tin nhắn/ngày (khách)',
     'Số tin nhắn miễn phí tối đa một phiên khách được gửi mỗi ngày.', true),

    (gen_random_uuid(), now(), true,
     'chat.max_history_messages', '20', 'NUMBER', 'CHAT',
     'Số tin nhắn lịch sử tối đa',
     'Số tin nhắn gần nhất được đưa vào ngữ cảnh khi trợ lý trả lời.', true),

    (gen_random_uuid(), now(), true,
     'maintenance.guest_session.ttl_days', '30', 'NUMBER', 'MAINTENANCE',
     'Thời gian lưu phiên khách (ngày)',
     'Số ngày một phiên khách được giữ lại trước khi bị dọn dẹp.', true),

    (gen_random_uuid(), now(), true,
     'maintenance.guest_session.cleanup_batch_size', '500', 'NUMBER', 'MAINTENANCE',
     'Kích thước lô dọn dẹp phiên khách',
     'Số phiên khách hết hạn được xử lý trong mỗi lần chạy tác vụ dọn dẹp.', true),

    (gen_random_uuid(), now(), true,
     'ingest.max_file_size_mb', '10', 'NUMBER', 'INGEST',
     'Kích thước file tối đa (MB)',
     'Dung lượng tối đa của một file tài liệu được phép tải lên.', true),

    (gen_random_uuid(), now(), true,
     'ingest.allowed_file_extensions', '[".txt",".pdf",".docx",".doc",".html"]', 'JSON', 'INGEST',
     'Định dạng file được phép',
     'Danh sách phần mở rộng file được chấp nhận khi nạp tài liệu.', true),

    (gen_random_uuid(), now(), true,
     'security.access_token_ttl_seconds', '86400', 'NUMBER', 'SECURITY',
     'Thời hạn access token (giây)',
     'Thời gian hiệu lực của access token trước khi hết hạn.', true),

    (gen_random_uuid(), now(), true,
     'security.refresh_token_ttl_seconds', '2592000', 'NUMBER', 'SECURITY',
     'Thời hạn refresh token (giây)',
     'Thời gian hiệu lực của refresh token trước khi hết hạn.', true),

    (gen_random_uuid(), now(), true,
     'ingest.presigned_url_expiry_seconds', '3600', 'NUMBER', 'INGEST',
     'Thời hạn URL tải file (giây)',
     'Thời gian hiệu lực của presigned URL dùng để tải file từ MinIO.', true);

-- 4) DataInitializer only seeds permissions the first time it runs (skipped once SUPER_ADMIN
--    exists), so an already-initialised dev database would never pick up SYSTEM_CONFIG_READ/
--    SYSTEM_CONFIG_UPDATE even though PredefinedPermissions/DataInitializer already define them.
--    Seed idempotently here, mirroring V10__audit_logs.sql's AUDIT_LOG_* precedent.
INSERT INTO public.permissions (id, created_at, is_active, name, path, method, resource_type, description)
SELECT gen_random_uuid(), now(), true, v.name, v.path, v.method, 'SYSTEM_CONFIG', v.description
FROM (VALUES
    ('SYSTEM_CONFIG_READ',   '/system-configs/**', 'GET', 'Xem cấu hình hệ thống'),
    ('SYSTEM_CONFIG_UPDATE', '/system-configs/**', 'PUT', 'Cập nhật cấu hình hệ thống')
) AS v(name, path, method, description)
WHERE NOT EXISTS (SELECT 1 FROM public.permissions p WHERE p.name = v.name);

-- SystemConfig has no _ALL wildcard (only GET/PUT are ever exposed), so SUPER_ADMIN is granted
-- both permissions explicitly, mirroring DataInitializer.assignSuperAdmin's explicit grant.
INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM public.roles r, public.permissions p
WHERE r.name = 'SUPER_ADMIN' AND p.name IN ('SYSTEM_CONFIG_READ', 'SYSTEM_CONFIG_UPDATE')
ON CONFLICT DO NOTHING;
