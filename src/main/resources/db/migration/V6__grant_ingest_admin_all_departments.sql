-- DataInitializer never gave the general-purpose ingest admin (IA-001, ingest@unisage.com) any
-- user_department_access rows - only SUPER_ADMIN got assignAllDepartmentsToUser(), and the other
-- seeded ingest account (IA-002) is deliberately scoped to just PHONG_DAO_TAO as a narrow
-- permission-gate test fixture. With zero department-access rows, the ingestion wizard rejects
-- every document for IA-001 with "Bạn không có quyền truy cập phòng ban '<uuid>'" no matter what
-- else is configured on the document. Fixed in DataInitializer for future fresh seeds (now also
-- calls assignAllDepartmentsToUser for this user); this grants the same access, at the same
-- access level (5, the max) it already logs in with, to whatever IA-001 user already exists here.
--
-- Mirrors assignAllDepartmentsToUser()'s own idempotency (skip a department already granted)
-- via ON CONFLICT DO NOTHING on the table's (department_id, user_id) primary key.

INSERT INTO public.user_department_access (department_id, user_id, access_level_id)
SELECT d.id, u.id, (SELECT id FROM public.access_levels WHERE level = 5)
FROM public.departments d, public.users u
WHERE u.code = 'IA-001'
ON CONFLICT DO NOTHING;
