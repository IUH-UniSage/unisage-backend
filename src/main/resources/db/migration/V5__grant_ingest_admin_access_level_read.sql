-- DataInitializer.assignIngestAdmin() never granted ACCESS_LEVEL_READ to the INGEST_ADMIN role,
-- so GET /access-levels 403s for that role - the document create/edit form's "Cấp độ truy cập
-- tối thiểu" dropdown then has no options to offer besides the placeholder, and any document
-- left with a null access level is rejected by the ingestion wizard ("chưa được gán phòng ban
-- hoặc cấp độ truy cập tối thiểu, không thể xử lý nạp liệu"). Fixed in DataInitializer for future
-- fresh seeds; this grants the same permission to whatever INGEST_ADMIN role already exists here.
--
-- Both rows are looked up by name rather than hardcoded ids so this works across databases seeded
-- at different times. ON CONFLICT DO NOTHING covers a database where this was already granted by
-- hand.

INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM public.roles r, public.permissions p
WHERE r.name = 'INGEST_ADMIN' AND p.name = 'ACCESS_LEVEL_READ'
ON CONFLICT DO NOTHING;
