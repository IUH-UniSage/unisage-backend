-- DataInitializer seeded every predefined role (SUPER_ADMIN, INGEST_ADMIN, USER) with
-- is_system_role = true. The end-user role USER must not be one: the frontend reads the flag
-- (AuthResponse.isSystemRole) to decide who is offered the admin workspace, and a plain user must
-- not be. DataInitializer is fixed for fresh seeds; it skips itself once SUPER_ADMIN exists, so this
-- corrects databases initialised earlier. Matched by name and idempotent.

UPDATE public.roles SET is_system_role = false WHERE name = 'USER';
