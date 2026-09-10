-- Guest chat session persistence: a guest's browser is identified by a hashed, high-entropy
-- token delivered as an httpOnly cookie (see docs/adr for the TTL/cleanup policy), instead of
-- ip_address, which is unfit for identity (shared NAT/corporate networks, rotating mobile IPs)
-- and is dropped entirely from conversations/usage_limits — never reintroduced elsewhere.

CREATE TABLE public.guest_sessions (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by uuid,
    is_active boolean,
    updated_at timestamp(6) without time zone,
    updated_by uuid,
    token_hash character varying(64) NOT NULL,
    expires_at timestamp(6) without time zone NOT NULL
);

ALTER TABLE ONLY public.guest_sessions
    ADD CONSTRAINT guest_sessions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.guest_sessions
    ADD CONSTRAINT guest_sessions_token_hash_key UNIQUE (token_hash);

ALTER TABLE ONLY public.guest_sessions
    ADD CONSTRAINT fk_guest_sessions_created_by FOREIGN KEY (created_by) REFERENCES public.users(id),
    ADD CONSTRAINT fk_guest_sessions_updated_by FOREIGN KEY (updated_by) REFERENCES public.users(id);

-- conversations: guest_session_id replaces ip_address as the guest-ownership signal.
ALTER TABLE public.conversations
    ADD COLUMN guest_session_id uuid;

ALTER TABLE ONLY public.conversations
    ADD CONSTRAINT fk_conversations_guest_session FOREIGN KEY (guest_session_id) REFERENCES public.guest_sessions(id);

ALTER TABLE public.conversations
    ADD CONSTRAINT conversations_owner_xor CHECK (NOT (user_id IS NOT NULL AND guest_session_id IS NOT NULL));

ALTER TABLE public.conversations
    DROP COLUMN ip_address;

-- usage_limits: guest_session_id replaces ip_address as the guest rate-limit key.
ALTER TABLE public.usage_limits
    ADD COLUMN guest_session_id uuid;

ALTER TABLE ONLY public.usage_limits
    ADD CONSTRAINT fk_usage_limits_guest_session FOREIGN KEY (guest_session_id) REFERENCES public.guest_sessions(id);

ALTER TABLE public.usage_limits
    ADD CONSTRAINT usage_limits_owner_xor CHECK (NOT (user_id IS NOT NULL AND guest_session_id IS NOT NULL));

ALTER TABLE public.usage_limits
    DROP CONSTRAINT uknt58510d4ug3o0vcfqtvn76do;

ALTER TABLE public.usage_limits
    DROP COLUMN ip_address;

ALTER TABLE ONLY public.usage_limits
    ADD CONSTRAINT usage_limits_guest_session_unique UNIQUE (guest_session_id, limit_type, scope, scope_date);
