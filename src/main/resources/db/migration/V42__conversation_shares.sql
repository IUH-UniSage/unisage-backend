-- UNISAGE-107: ChatGPT-style share links. A share is a frozen *reference* to a prefix of one
-- conversation (every COMPLETED message with (created_at, id) <= (cutoff_at, cutoff_message_id)),
-- not a copy of its messages - see docs/adr/0011-conversation-share-snapshot-and-token.md.
-- The token is stored in clear (unlike guest sessions, which store a hash) so the owner's
-- "shared links" page can copy a link again. Revoking only sets revoked_at; rows are never deleted.
CREATE TABLE public.conversation_shares (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by uuid,
    is_active boolean,
    updated_at timestamp(6) without time zone,
    updated_by uuid,
    token character varying(64) NOT NULL,
    conversation_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    title character varying(255),
    cutoff_message_id uuid NOT NULL,
    cutoff_at timestamp(6) without time zone NOT NULL,
    message_count integer NOT NULL,
    revoked_at timestamp(6) without time zone,
    CONSTRAINT conversation_shares_pkey PRIMARY KEY (id),
    CONSTRAINT conversation_shares_token_key UNIQUE (token),
    CONSTRAINT fk_conversation_shares_conversation FOREIGN KEY (conversation_id)
        REFERENCES public.conversations(id),
    CONSTRAINT fk_conversation_shares_owner FOREIGN KEY (owner_id) REFERENCES public.users(id),
    CONSTRAINT fk_conversation_shares_cutoff_message FOREIGN KEY (cutoff_message_id)
        REFERENCES public.messages(id),
    CONSTRAINT fk_conversation_shares_created_by FOREIGN KEY (created_by) REFERENCES public.users(id),
    CONSTRAINT fk_conversation_shares_updated_by FOREIGN KEY (updated_by) REFERENCES public.users(id)
);

-- "My shared links" page: the owner's live links, newest first.
CREATE INDEX idx_conversation_shares_owner_active
    ON public.conversation_shares (owner_id, created_at DESC) WHERE revoked_at IS NULL;
-- Revoke-on-delete (user delete and the retention job) looks shares up by conversation.
CREATE INDEX idx_conversation_shares_conversation_active
    ON public.conversation_shares (conversation_id) WHERE revoked_at IS NULL;

-- A conversation created by "Tiếp tục" remembers the link it came from. One live copy per
-- (link, user): a second "Tiếp tục" (or a double-click) reopens the existing copy; once that copy
-- is deleted, a new one may be made.
ALTER TABLE public.conversations ADD COLUMN source_share_id uuid;
ALTER TABLE public.conversations
    ADD CONSTRAINT fk_conversations_source_share FOREIGN KEY (source_share_id)
        REFERENCES public.conversation_shares(id);
CREATE UNIQUE INDEX uq_conversations_source_share_user_active
    ON public.conversations (source_share_id, user_id) WHERE deleted_at IS NULL;
