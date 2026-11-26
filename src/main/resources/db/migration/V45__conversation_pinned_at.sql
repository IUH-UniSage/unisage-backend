-- UNISAGE-107 UX audit: "Ghim" a conversation in the chat sidebar. NULL = not pinned; the owner's
-- list shows pinned conversations first (newest pin first), then the rest by updated_at. Set and
-- cleared by a single-column UPDATE that never touches updated_at, so (un)pinning does not move a
-- conversation in the "recent" order.
ALTER TABLE public.conversations ADD COLUMN pinned_at timestamp(6) without time zone;
