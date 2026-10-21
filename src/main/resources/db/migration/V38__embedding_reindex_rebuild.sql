-- UNISAGE-104 (hybrid retrieval): a re-index run may target the live embedding model itself to
-- rebuild the collection with the `bm25` sparse vector. The agent copies the dense vectors and
-- adds bm25 (no embedding call); on SUCCEEDED only the pointer moves, the model stays ACTIVE.
ALTER TABLE public.embedding_reindex_runs
    ADD COLUMN rebuild boolean NOT NULL DEFAULT false;
