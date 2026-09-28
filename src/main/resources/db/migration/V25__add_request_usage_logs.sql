-- UNISAGE-90: Cost Tracking + Budget Management, Task 1. Parent/child tables that
-- record every LLM/embedding provider call so cost can be measured, budgeted and
-- audited. See changes/23-09-2026-Cost-Tracking-Budget-Management/plan.md
-- "Data Model" for the full design this migration encodes.

-- ── request_usage_logs: one row per business request that made >= 1 provider call ──

CREATE TABLE public.request_usage_logs (
    id uuid NOT NULL,
    request_id uuid NOT NULL,
    purpose character varying(20) NOT NULL,
    conversation_id uuid,
    user_message_id uuid,
    assistant_message_id uuid,
    user_id uuid,
    guest_ip character varying(255),
    status character varying(20) NOT NULL,
    total_input_tokens integer NOT NULL DEFAULT 0,
    total_output_tokens integer NOT NULL DEFAULT 0,
    total_cached_tokens integer NOT NULL DEFAULT 0,
    total_cost_usd numeric(18, 8) NOT NULL DEFAULT 0,
    estimated_unpriced_cost_usd numeric(18, 8) NOT NULL DEFAULT 0,
    unpriced_line_count integer NOT NULL DEFAULT 0,
    line_count integer NOT NULL DEFAULT 0,
    latency_ms integer,
    started_at timestamp(6) without time zone NOT NULL,
    finished_at timestamp(6) without time zone,
    created_at timestamp(6) without time zone NOT NULL DEFAULT now(),
    CONSTRAINT request_usage_logs_pkey PRIMARY KEY (id),
    CONSTRAINT ux_request_usage_logs_request_id UNIQUE (request_id),
    CONSTRAINT request_usage_logs_purpose_check
        CHECK (purpose IN ('CHAT', 'EMBEDDING', 'EXTRACTION')),
    CONSTRAINT request_usage_logs_status_check
        CHECK (status IN ('SUCCESS', 'ERROR', 'PARTIAL')),
    CONSTRAINT fk_request_usage_logs_conversation
        FOREIGN KEY (conversation_id) REFERENCES public.conversations(id) ON DELETE SET NULL,
    CONSTRAINT fk_request_usage_logs_user_message
        FOREIGN KEY (user_message_id) REFERENCES public.messages(id) ON DELETE SET NULL,
    CONSTRAINT fk_request_usage_logs_assistant_message
        FOREIGN KEY (assistant_message_id) REFERENCES public.messages(id) ON DELETE SET NULL,
    CONSTRAINT fk_request_usage_logs_user
        FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE SET NULL
);

CREATE INDEX ix_request_usage_logs_started_at ON public.request_usage_logs (started_at);
CREATE INDEX ix_request_usage_logs_purpose_started_at ON public.request_usage_logs (purpose, started_at);
CREATE INDEX ix_request_usage_logs_user_id_started_at ON public.request_usage_logs (user_id, started_at);
CREATE INDEX ix_request_usage_logs_status_started_at ON public.request_usage_logs (status, started_at);
CREATE INDEX ix_request_usage_logs_user_message_id ON public.request_usage_logs (user_message_id);
CREATE INDEX ix_request_usage_logs_assistant_message_id ON public.request_usage_logs (assistant_message_id);
CREATE INDEX ix_request_usage_logs_conversation_id ON public.request_usage_logs (conversation_id);

-- ── request_usage_lines: one row per provider call attempt within a request ────────

CREATE TABLE public.request_usage_lines (
    id uuid NOT NULL,
    usage_log_id uuid NOT NULL,
    seq integer NOT NULL,
    node_name character varying(255) NOT NULL,
    attempt integer NOT NULL DEFAULT 0,
    chat_model_id uuid,
    provider character varying(255),
    model_name character varying(255),
    source_type character varying(20),
    input_tokens integer NOT NULL DEFAULT 0,
    output_tokens integer NOT NULL DEFAULT 0,
    cached_tokens integer NOT NULL DEFAULT 0,
    cost_usd numeric(18, 8),
    estimated_cost_usd numeric(18, 8) NOT NULL,
    cost_status character varying(20) NOT NULL,
    latency_ms integer,
    status character varying(20) NOT NULL,
    error_code character varying(255),
    occurred_at timestamp(6) without time zone NOT NULL,
    CONSTRAINT request_usage_lines_pkey PRIMARY KEY (id),
    CONSTRAINT ux_request_usage_lines_usage_log_seq UNIQUE (usage_log_id, seq),
    CONSTRAINT request_usage_lines_source_type_check
        CHECK (source_type IN ('CLOUD_API', 'SELF_HOSTED')),
    CONSTRAINT request_usage_lines_cost_status_check
        CHECK (cost_status IN ('PRICED', 'UNPRICED', 'FREE')),
    CONSTRAINT request_usage_lines_status_check
        CHECK (status IN ('SUCCESS', 'ERROR')),
    -- costStatus = PRICED must carry a real costUsd; any other status must not.
    CONSTRAINT request_usage_lines_cost_usd_matches_status_check
        CHECK ((cost_status = 'PRICED' AND cost_usd IS NOT NULL)
            OR (cost_status <> 'PRICED' AND cost_usd IS NULL)),
    CONSTRAINT fk_request_usage_lines_usage_log
        FOREIGN KEY (usage_log_id) REFERENCES public.request_usage_logs(id) ON DELETE CASCADE,
    CONSTRAINT fk_request_usage_lines_chat_model
        FOREIGN KEY (chat_model_id) REFERENCES public.chat_models(id) ON DELETE SET NULL
);

CREATE INDEX ix_request_usage_lines_occurred_at ON public.request_usage_lines (occurred_at);
CREATE INDEX ix_request_usage_lines_provider_occurred_at ON public.request_usage_lines (provider, occurred_at);
CREATE INDEX ix_request_usage_lines_chat_model_id_occurred_at ON public.request_usage_lines (chat_model_id, occurred_at);
