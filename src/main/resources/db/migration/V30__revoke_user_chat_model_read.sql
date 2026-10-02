-- DataInitializer.assignUser() used to grant CHAT_MODEL_READ to the end-user USER role, letting
-- every student call GET /chat-models and read the AI model configuration (providers, base URLs,
-- priorities, error state). Nothing on the chat path needs it: unisage-agent loads models through
-- /internal with the shared secret, and the web chat never calls /chat-models. It also made
-- unisage-agent treat every student as an AI admin when deciding how much failure detail to show.
-- Fixed in DataInitializer for fresh seeds; this revokes it from the USER role that already exists.
--
-- Looked up by name rather than hardcoded ids so this works across databases seeded at different
-- times. Users keep the permission inside their current access token until it expires.

DELETE FROM public.role_permissions rp
USING public.roles r, public.permissions p
WHERE rp.role_id = r.id
  AND rp.permission_id = p.id
  AND r.name = 'USER'
  AND p.name = 'CHAT_MODEL_READ';
