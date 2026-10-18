-- UNISAGE-103: re-index the Qdrant collection when the embedding model changes (blue/green).
-- See changes/17-10-2026-Embedding-Reindex/plan.md and docs/adr/0008-embedding-reindex-blue-green.md.

-- Which collection is live for a base collection (model-registry.embedding-collection-name).
-- No row = the base collection itself is live, so existing data keeps working untouched.
CREATE TABLE public.embedding_index_pointer (
    base_collection character varying(255) NOT NULL,
    active_collection character varying(255) NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL DEFAULT now(),
    CONSTRAINT embedding_index_pointer_pkey PRIMARY KEY (base_collection)
);

-- One re-embed of every point of source_collection into target_collection with the target
-- model. Claimed by unisage-agent under a lease, like chat_model_verifications; cut over on
-- SUCCEEDED (pointer -> target_collection, target model ACTIVE).
CREATE TABLE public.embedding_reindex_runs (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    base_collection character varying(255) NOT NULL,
    source_collection character varying(255) NOT NULL,
    target_collection character varying(255) NOT NULL,
    target_chat_model_id uuid NOT NULL,
    target_model_revision integer NOT NULL,
    status character varying(20) NOT NULL,
    total_points integer,
    processed_points integer NOT NULL DEFAULT 0,
    failed_points integer NOT NULL DEFAULT 0,
    lease_token uuid,
    lease_until timestamp(6) without time zone,
    error_message character varying(500),
    requested_by uuid,
    created_at timestamp(6) without time zone NOT NULL DEFAULT now(),
    started_at timestamp(6) without time zone,
    finished_at timestamp(6) without time zone,
    CONSTRAINT embedding_reindex_runs_pkey PRIMARY KEY (id),
    CONSTRAINT ux_embedding_reindex_runs_target UNIQUE (target_collection),
    CONSTRAINT embedding_reindex_runs_status_check
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT fk_embedding_reindex_runs_model FOREIGN KEY (target_chat_model_id)
        REFERENCES public.chat_models(id),
    CONSTRAINT fk_embedding_reindex_runs_requested_by FOREIGN KEY (requested_by)
        REFERENCES public.users(id)
);

-- At most one open run per base collection.
CREATE UNIQUE INDEX ux_embedding_reindex_runs_open
    ON public.embedding_reindex_runs (base_collection) WHERE status IN ('QUEUED', 'RUNNING');

CREATE INDEX ix_embedding_reindex_runs_claimable
    ON public.embedding_reindex_runs (status, lease_until);

-- A run registers the identity of its target collection itself.
ALTER TABLE public.embedding_index_identity
    DROP CONSTRAINT embedding_index_identity_established_by_check;
ALTER TABLE public.embedding_index_identity
    ADD CONSTRAINT embedding_index_identity_established_by_check
        CHECK (established_by IN ('bootstrap-cli', 'first-upsert', 'reindex'));
