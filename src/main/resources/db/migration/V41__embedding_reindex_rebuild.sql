-- UNISAGE-104 (hybrid retrieval): a re-index run may target the live embedding model itself to
-- rebuild the collection with the `bm25` sparse vector. The agent copies the dense vectors and
-- adds bm25 (no embedding call); on SUCCEEDED only the pointer moves, the model stays ACTIVE.
--
-- Was V38 on the feature branch; renumbered to V41 because UNISAGE-102 took V38-V40 on main.
-- IF NOT EXISTS: a database that applied the old V38 (its flyway_schema_history row must be
-- deleted so the backup V38-V40 can run) keeps the column and this becomes a no-op.
ALTER TABLE public.embedding_reindex_runs
    ADD COLUMN IF NOT EXISTS rebuild boolean NOT NULL DEFAULT false;
