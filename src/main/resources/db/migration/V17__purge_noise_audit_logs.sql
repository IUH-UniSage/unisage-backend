-- UNISAGE-92: purge audit rows that the listener/aspect no longer writes, so the admin trail
-- only shows data changes and sign-in events.
-- Numbered V17 (not V16) to avoid colliding with V16__dashboard_permission.sql on UNISAGE-72.

-- SystemHealthCheck rows were audited on every scheduled run (every few minutes, no actor),
-- burying real admin actions under "Hệ thống / Khác" noise.
DELETE FROM audit_logs
WHERE resource_type = 'OTHER'
  AND details LIKE '%"_entity":"SystemHealthCheck"%';

-- Read-path auditing (@Auditable on document/user detail GETs) was removed: the document
-- detail fetch was logged as DOWNLOAD on every dialog/form open, while real file downloads
-- go straight to MinIO and were never captured.
DELETE FROM audit_logs
WHERE action IN ('VIEW', 'DOWNLOAD');
