-- UNISAGE-56: createdBy/updatedBy move from a free-form varchar (userId.toString(), or the
-- literal 'anonymous' for guest-created rows) to a real FK relation to users(id).
-- See docs/adr/0002-audit-fields-user-relation.md for the full rationale.
--
-- Per that ADR, existing values are NOT migrated: 'anonymous' is not a valid uuid/FK target, and
-- there is no guarantee every remaining value maps to a live users.id on every dev machine. This
-- is an accepted dev-only breaking change — old audit values are dropped, not cast.

ALTER TABLE public.access_levels DROP COLUMN created_by, DROP COLUMN updated_by;
ALTER TABLE public.access_levels ADD COLUMN created_by uuid, ADD COLUMN updated_by uuid;
ALTER TABLE public.access_levels
    ADD CONSTRAINT fk_access_levels_created_by FOREIGN KEY (created_by) REFERENCES public.users(id),
    ADD CONSTRAINT fk_access_levels_updated_by FOREIGN KEY (updated_by) REFERENCES public.users(id);

ALTER TABLE public.categories DROP COLUMN created_by, DROP COLUMN updated_by;
ALTER TABLE public.categories ADD COLUMN created_by uuid, ADD COLUMN updated_by uuid;
ALTER TABLE public.categories
    ADD CONSTRAINT fk_categories_created_by FOREIGN KEY (created_by) REFERENCES public.users(id),
    ADD CONSTRAINT fk_categories_updated_by FOREIGN KEY (updated_by) REFERENCES public.users(id);

ALTER TABLE public.chat_models DROP COLUMN created_by, DROP COLUMN updated_by;
ALTER TABLE public.chat_models ADD COLUMN created_by uuid, ADD COLUMN updated_by uuid;
ALTER TABLE public.chat_models
    ADD CONSTRAINT fk_chat_models_created_by FOREIGN KEY (created_by) REFERENCES public.users(id),
    ADD CONSTRAINT fk_chat_models_updated_by FOREIGN KEY (updated_by) REFERENCES public.users(id);

ALTER TABLE public.conversations DROP COLUMN created_by, DROP COLUMN updated_by;
ALTER TABLE public.conversations ADD COLUMN created_by uuid, ADD COLUMN updated_by uuid;
ALTER TABLE public.conversations
    ADD CONSTRAINT fk_conversations_created_by FOREIGN KEY (created_by) REFERENCES public.users(id),
    ADD CONSTRAINT fk_conversations_updated_by FOREIGN KEY (updated_by) REFERENCES public.users(id);

ALTER TABLE public.departments DROP COLUMN created_by, DROP COLUMN updated_by;
ALTER TABLE public.departments ADD COLUMN created_by uuid, ADD COLUMN updated_by uuid;
ALTER TABLE public.departments
    ADD CONSTRAINT fk_departments_created_by FOREIGN KEY (created_by) REFERENCES public.users(id),
    ADD CONSTRAINT fk_departments_updated_by FOREIGN KEY (updated_by) REFERENCES public.users(id);

ALTER TABLE public.documents DROP COLUMN created_by, DROP COLUMN updated_by;
ALTER TABLE public.documents ADD COLUMN created_by uuid, ADD COLUMN updated_by uuid;
ALTER TABLE public.documents
    ADD CONSTRAINT fk_documents_created_by FOREIGN KEY (created_by) REFERENCES public.users(id),
    ADD CONSTRAINT fk_documents_updated_by FOREIGN KEY (updated_by) REFERENCES public.users(id);

ALTER TABLE public.messages DROP COLUMN created_by, DROP COLUMN updated_by;
ALTER TABLE public.messages ADD COLUMN created_by uuid, ADD COLUMN updated_by uuid;
ALTER TABLE public.messages
    ADD CONSTRAINT fk_messages_created_by FOREIGN KEY (created_by) REFERENCES public.users(id),
    ADD CONSTRAINT fk_messages_updated_by FOREIGN KEY (updated_by) REFERENCES public.users(id);

ALTER TABLE public.permissions DROP COLUMN created_by, DROP COLUMN updated_by;
ALTER TABLE public.permissions ADD COLUMN created_by uuid, ADD COLUMN updated_by uuid;
ALTER TABLE public.permissions
    ADD CONSTRAINT fk_permissions_created_by FOREIGN KEY (created_by) REFERENCES public.users(id),
    ADD CONSTRAINT fk_permissions_updated_by FOREIGN KEY (updated_by) REFERENCES public.users(id);

ALTER TABLE public.roles DROP COLUMN created_by, DROP COLUMN updated_by;
ALTER TABLE public.roles ADD COLUMN created_by uuid, ADD COLUMN updated_by uuid;
ALTER TABLE public.roles
    ADD CONSTRAINT fk_roles_created_by FOREIGN KEY (created_by) REFERENCES public.users(id),
    ADD CONSTRAINT fk_roles_updated_by FOREIGN KEY (updated_by) REFERENCES public.users(id);

ALTER TABLE public.usage_limits DROP COLUMN created_by, DROP COLUMN updated_by;
ALTER TABLE public.usage_limits ADD COLUMN created_by uuid, ADD COLUMN updated_by uuid;
ALTER TABLE public.usage_limits
    ADD CONSTRAINT fk_usage_limits_created_by FOREIGN KEY (created_by) REFERENCES public.users(id),
    ADD CONSTRAINT fk_usage_limits_updated_by FOREIGN KEY (updated_by) REFERENCES public.users(id);

-- users.created_by/updated_by is a self-referencing FK back onto users(id) (User extends
-- BaseEntity too) — the bootstrap/first user has no creator, so this stays nullable.
ALTER TABLE public.users DROP COLUMN created_by, DROP COLUMN updated_by;
ALTER TABLE public.users ADD COLUMN created_by uuid, ADD COLUMN updated_by uuid;
ALTER TABLE public.users
    ADD CONSTRAINT fk_users_created_by FOREIGN KEY (created_by) REFERENCES public.users(id),
    ADD CONSTRAINT fk_users_updated_by FOREIGN KEY (updated_by) REFERENCES public.users(id);
