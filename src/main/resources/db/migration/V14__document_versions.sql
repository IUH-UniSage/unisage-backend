-- UNISAGE-86: document version history. When a document's file is replaced via
-- PUT /documents/{id}, the OLD file is snapshotted into document_versions instead of being
-- deleted from MinIO (old files are kept forever, no retention/cleanup). Append-only history
-- rows, no BaseEntity audit columns (updated_at/is_active) needed — a version snapshot is never
-- updated after being written.

CREATE TABLE public.document_versions (
    id uuid NOT NULL,
    document_id uuid NOT NULL,
    version_number integer NOT NULL,
    source_url character varying(255) NOT NULL,
    file_type character varying(255) NOT NULL,
    uploaded_by uuid,
    created_at timestamp(6) without time zone NOT NULL
);

ALTER TABLE ONLY public.document_versions
    ADD CONSTRAINT document_versions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.document_versions
    ADD CONSTRAINT fk_document_versions_document_id FOREIGN KEY (document_id) REFERENCES public.documents(id);

ALTER TABLE ONLY public.document_versions
    ADD CONSTRAINT fk_document_versions_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES public.users(id);

-- Version history lookups filter/sort by document_id + version_number descending
-- (GET /documents/{id}/versions).
CREATE INDEX idx_document_versions_document_id ON public.document_versions (document_id, version_number DESC);
