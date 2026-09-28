-- UNISAGE-90: Cost Tracking + Budget Management, Task 3. Seeds the USAGE_LOG/BUDGET permissions
-- for databases that already exist (DataInitializer only seeds on a fresh DB - it stops as soon as
-- the SUPER_ADMIN role already exists), mirroring V16__dashboard_permission.sql's precedent.

-- resource_type on permissions/audit_logs is constrained to a fixed list - widen it first or the
-- inserts below (and any later AuditLog entry tagged BUDGET/USAGE_LOG) would be rejected.
ALTER TABLE public.permissions DROP CONSTRAINT permissions_resource_type_check;
ALTER TABLE public.permissions
    ADD CONSTRAINT permissions_resource_type_check CHECK ((resource_type)::text = ANY ((ARRAY[
        'USER', 'ROLE', 'PERMISSION', 'DEPARTMENT', 'USER_DEPARTMENT_ACCESS', 'DOCUMENT', 'CATEGORY',
        'ACCESS_LEVEL', 'CHAT_MODEL', 'LLM_TRACE_LOG', 'CONVERSATION', 'MESSAGE', 'USAGE_LIMIT_PLAN',
        'TICKET', 'AUDIT_LOG', 'SYSTEM', 'SYSTEM_CONFIG', 'USAGE_LOG', 'BUDGET', 'OTHER'
    ])::text[]));

ALTER TABLE public.audit_logs DROP CONSTRAINT audit_logs_resource_type_check;
ALTER TABLE public.audit_logs
    ADD CONSTRAINT audit_logs_resource_type_check CHECK ((resource_type)::text = ANY ((ARRAY[
        'USER', 'ROLE', 'PERMISSION', 'DEPARTMENT', 'USER_DEPARTMENT_ACCESS', 'DOCUMENT', 'CATEGORY',
        'ACCESS_LEVEL', 'CHAT_MODEL', 'LLM_TRACE_LOG', 'CONVERSATION', 'MESSAGE', 'USAGE_LIMIT_PLAN',
        'TICKET', 'AUDIT_LOG', 'SYSTEM', 'SYSTEM_CONFIG', 'USAGE_LOG', 'BUDGET', 'OTHER'
    ])::text[]));

INSERT INTO public.permissions (id, created_at, is_active, name, path, method, resource_type, description)
SELECT gen_random_uuid(), now(), true, v.name, v.path, v.method, v.resource_type, v.description
FROM (VALUES
    ('USAGE_LOG_READ', '/usage-logs/**', 'GET', 'USAGE_LOG', 'Xem nhật ký chi phí AI'),
    ('BUDGET_ALL', '/budgets/**', 'ALL', 'BUDGET', 'Toàn quyền ngân sách AI'),
    ('BUDGET_READ', '/budgets/**', 'GET', 'BUDGET', 'Xem ngân sách AI'),
    ('BUDGET_CREATE', '/budgets', 'POST', 'BUDGET', 'Tạo ngân sách AI'),
    ('BUDGET_UPDATE', '/budgets/**', 'PUT', 'BUDGET', 'Cập nhật ngân sách AI'),
    ('BUDGET_DELETE', '/budgets/**', 'DELETE', 'BUDGET', 'Xóa ngân sách AI'),
    ('BUDGET_ALERT_SETTING_READ', '/budget-alert-settings', 'GET', 'BUDGET', 'Xem cấu hình cảnh báo ngân sách'),
    ('BUDGET_ALERT_SETTING_UPDATE', '/budget-alert-settings', 'PUT', 'BUDGET', 'Cập nhật cấu hình cảnh báo ngân sách'),
    ('BUDGET_ALERT_READ', '/budget-alerts/**', 'GET', 'BUDGET', 'Xem lịch sử cảnh báo ngân sách'),
    ('BUDGET_ALERT_DISMISS', '/budget-alerts/*/dismiss', 'POST', 'BUDGET', 'Ẩn cảnh báo ngân sách')
) AS v(name, path, method, resource_type, description)
WHERE NOT EXISTS (SELECT 1 FROM public.permissions p WHERE p.name = v.name);

INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM public.roles r, public.permissions p
WHERE r.name = 'SUPER_ADMIN'
  AND p.name IN (
      'USAGE_LOG_READ', 'BUDGET_ALL', 'BUDGET_READ', 'BUDGET_CREATE', 'BUDGET_UPDATE', 'BUDGET_DELETE',
      'BUDGET_ALERT_SETTING_READ', 'BUDGET_ALERT_SETTING_UPDATE', 'BUDGET_ALERT_READ', 'BUDGET_ALERT_DISMISS'
  )
ON CONFLICT DO NOTHING;
