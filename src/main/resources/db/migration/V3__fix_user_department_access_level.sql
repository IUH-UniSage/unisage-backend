-- UNISAGE-56 shipped UserDepartmentAccess.accessLevel as a @ManyToOne AccessLevel
-- (@JoinColumn "access_level_id") back on 2026-08-23 (commit 9cd81fb), but V1's baseline dump
-- was taken from a database that still had the old pre-refactor shape - user_department_access
-- never got this column, only users.access_level_id did. Any fresh boot (or any dev database that
-- was never manually patched) fails Hibernate's ddl-auto=validate with "missing column
-- [access_level_id] in table [user_department_access]" before this migration existed.
--
-- The old `access_level integer` column is fully dead: nothing in the codebase reads or writes it
-- (grepped for the literal column name across src/main/java) - it's dropped here rather than kept
-- alongside the new FK.

ALTER TABLE public.user_department_access DROP COLUMN access_level;
ALTER TABLE public.user_department_access ADD COLUMN access_level_id uuid;
ALTER TABLE public.user_department_access
    ADD CONSTRAINT fk_user_department_access_access_level FOREIGN KEY (access_level_id) REFERENCES public.access_levels(id);
