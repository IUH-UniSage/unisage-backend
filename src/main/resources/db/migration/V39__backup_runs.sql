-- UNISAGE-102: one row per scheduled backup run (Postgres Full / Differential / WAL shipping,
-- one Qdrant collection snapshot). BackupCoordinator reads it to decide what is due, which Full a
-- Differential is taken against, and what retention may purge.
-- Plain table, no BaseEntity/audit columns: rows are written by the scheduler, not by a user, and
-- must not themselves be audited (same reasoning as system_health_checks, see V13).
CREATE TABLE public.backup_runs (
    id uuid NOT NULL,
    backup_type character varying(32) NOT NULL,
    status character varying(16) NOT NULL,
    -- Qdrant collection name for QDRANT_SNAPSHOT, NULL for the Postgres types.
    target character varying(255),
    -- Schedule slot this run answers, in app.timezone wall time (the time an admin typed in
    -- system_configs). A type is due again once a newer slot passes this value.
    scheduled_for timestamp(6) without time zone NOT NULL,
    -- started_at / finished_at / purged_at are UTC, like every other timestamp written via Clock.
    started_at timestamp(6) without time zone NOT NULL,
    finished_at timestamp(6) without time zone,
    -- The Full a DIFFERENTIAL was taken against; retention purges both together.
    base_run_id uuid,
    storage_prefix character varying(512),
    size_bytes bigint,
    file_count integer,
    checksum character varying(128),
    error_message text,
    purged_at timestamp(6) without time zone
);

ALTER TABLE ONLY public.backup_runs
    ADD CONSTRAINT backup_runs_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.backup_runs
    ADD CONSTRAINT backup_runs_base_run_fkey FOREIGN KEY (base_run_id) REFERENCES public.backup_runs (id);

CREATE INDEX idx_backup_runs_type_scheduled_for ON public.backup_runs (backup_type, scheduled_for DESC);
CREATE INDEX idx_backup_runs_base_run_id ON public.backup_runs (base_run_id);
