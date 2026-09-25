-- Dynamic Model Registry: chat_models gains a real lifecycle (status) and a
-- purpose (CHAT/EMBEDDING/EXTRACTION), staged credential rotation (revision +
-- candidate_generation), a pull-based verification job table, and an
-- immutable embedding-index identity table. See plan.md "State machine",
-- "Credential rotation", "Verification lifecycle", "Embedding identity guard"
-- in changes/23-09-2026-Dynamic-Model-Registry-Runtime-Failover/ for the full
-- design this migration encodes.

-- ── chat_models: purpose + status + staged rotation ────────────────────────

ALTER TABLE public.chat_models
    ADD COLUMN model_purpose character varying(20) NOT NULL DEFAULT 'CHAT',
    ADD COLUMN status character varying(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN verified_at timestamp(6) without time zone,
    ADD COLUMN revision integer NOT NULL DEFAULT 0,
    ADD COLUMN candidate_generation integer NOT NULL DEFAULT 0;

ALTER TABLE public.chat_models
    ADD CONSTRAINT chat_models_model_purpose_check
        CHECK (model_purpose IN ('CHAT', 'EMBEDDING', 'EXTRACTION')),
    ADD CONSTRAINT chat_models_status_check
        CHECK (status IN ('PENDING', 'ACTIVE', 'INACTIVE', 'DISABLED')),
    ADD CONSTRAINT chat_models_active_status_consistent
        CHECK (NOT (is_active = false AND status = 'ACTIVE'));

-- Only row used for routing: is_active = true AND status = 'ACTIVE'. At most
-- one ACTIVE EMBEDDING row at a time — changing embedding model mid-flight
-- would put mismatched vectors in the same Qdrant collection.
CREATE UNIQUE INDEX ux_chat_models_single_active_embedding
    ON public.chat_models (model_purpose)
    WHERE model_purpose = 'EMBEDDING' AND status = 'ACTIVE' AND is_active = true;

-- Backfill: every existing row is a CHAT credential already in production use.
UPDATE public.chat_models
SET model_purpose = 'CHAT',
    status = CASE WHEN is_active THEN 'ACTIVE' ELSE 'INACTIVE' END,
    verified_at = CASE WHEN is_active THEN now() ELSE NULL END,
    revision = 1;

-- ── chat_model_verifications: pull-based verification job ──────────────────

CREATE TABLE public.chat_model_verifications (
    id uuid NOT NULL,
    chat_model_id uuid NOT NULL,
    status character varying(20) NOT NULL,
    candidate_generation integer NOT NULL,
    base_revision integer NOT NULL,
    candidate_llm_provider character varying(255),
    candidate_llm_model_name character varying(255),
    candidate_model_source_ref character varying(255),
    candidate_api_key_encrypted text,
    candidate_api_base_url character varying(255),
    embedding_dimension integer,
    embedding_fingerprint real[],
    attempt integer NOT NULL DEFAULT 0,
    max_attempts integer NOT NULL DEFAULT 3,
    next_attempt_at timestamp(6) without time zone,
    lease_until timestamp(6) without time zone,
    lease_token uuid,
    last_result_lease_token uuid,
    error_type character varying(20),
    error_code character varying(100),
    error_message text,
    created_at timestamp(6) without time zone NOT NULL DEFAULT now(),
    started_at timestamp(6) without time zone,
    finished_at timestamp(6) without time zone,
    CONSTRAINT chat_model_verifications_pkey PRIMARY KEY (id),
    CONSTRAINT fk_chat_model_verifications_chat_model
        FOREIGN KEY (chat_model_id) REFERENCES public.chat_models(id),
    CONSTRAINT chat_model_verifications_status_check
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SUPERSEDED', 'CANCELLED', 'REINDEX_REQUIRED'))
);

CREATE INDEX ix_chat_model_verifications_chat_model_id ON public.chat_model_verifications (chat_model_id);

-- At most 1 job not yet in a final state per credential — a new candidate
-- supersedes the old job in the same transaction (see plan.md), never lets
-- two open jobs race each other.
CREATE UNIQUE INDEX ux_chat_model_verifications_one_open_per_model
    ON public.chat_model_verifications (chat_model_id)
    WHERE status IN ('QUEUED', 'RUNNING');

-- ── embedding_index_identity: immutable identity of the vectors already in
--    a Qdrant collection — never updated or deleted, only inserted once ────

CREATE TABLE public.embedding_index_identity (
    collection_name character varying(255) NOT NULL,
    provider character varying(255) NOT NULL,
    model_name character varying(255) NOT NULL,
    model_source_ref character varying(255),
    api_base_url character varying(255),
    dimension integer NOT NULL,
    fingerprint real[] NOT NULL,
    established_at timestamp(6) without time zone NOT NULL DEFAULT now(),
    established_by character varying(50) NOT NULL,
    CONSTRAINT embedding_index_identity_pkey PRIMARY KEY (collection_name),
    CONSTRAINT embedding_index_identity_established_by_check
        CHECK (established_by IN ('bootstrap-cli', 'first-upsert'))
);

-- Belt-and-suspenders on top of "no UPDATE/DELETE repository method" and the
-- INSERT ... ON CONFLICT DO NOTHING endpoint: even a stray hand-written SQL
-- statement can't mutate an established identity.
CREATE OR REPLACE FUNCTION public.reject_embedding_index_identity_mutation()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'embedding_index_identity rows are immutable once inserted (collection_name=%)',
        COALESCE(OLD.collection_name, NEW.collection_name);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_embedding_index_identity_immutable
    BEFORE UPDATE OR DELETE ON public.embedding_index_identity
    FOR EACH ROW EXECUTE FUNCTION public.reject_embedding_index_identity_mutation();
