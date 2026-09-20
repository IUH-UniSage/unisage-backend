-- UNISAGE-60 (hybrid extension): widen audit_logs.action CHECK constraint to cover the new
-- non-DB-write AuditAction values added for the AOP (@Auditable) and domain-event (login/logout)
-- audit paths. See docs/adr/0003-audit-log-persistence.md, section "Update — hybrid extension".
ALTER TABLE public.audit_logs DROP CONSTRAINT audit_logs_action_check;

ALTER TABLE public.audit_logs
    ADD CONSTRAINT audit_logs_action_check CHECK ((action)::text = ANY ((ARRAY[
        'CREATE', 'UPDATE', 'DELETE',
        'LOGIN', 'LOGIN_FAILED', 'LOGOUT', 'DOWNLOAD', 'VIEW'
    ])::text[]));
