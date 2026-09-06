-- Commit 9cd81fb (2026-08-23) removed the unused INGEST resource_type/permission from the Java
-- enum (ResourceType) and from PredefinedPermissions - it was never enforced by
-- DynamicAuthorizationManager - but never cleaned up the rows it had already seeded. Any dev
-- database seeded before that commit still carries `permissions` rows with
-- resource_type = 'INGEST', which Hibernate can no longer deserialize (no matching enum constant)
-- as soon as anything loads that row - in practice, this crashes login for whichever role holds
-- it (INGEST_ADMIN, via role_permissions), with `IllegalArgumentException: No enum constant
-- ...ResourceType.INGEST` surfacing as a 500 from Hibernate's SingleIdEntityLoader.
--
-- Deletes role_permissions links first (FK), then the orphaned permissions rows themselves.

DELETE FROM public.role_permissions
WHERE permission_id IN (SELECT id FROM public.permissions WHERE resource_type = 'INGEST');

DELETE FROM public.permissions WHERE resource_type = 'INGEST';
