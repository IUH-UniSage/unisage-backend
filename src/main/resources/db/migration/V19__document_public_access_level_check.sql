UPDATE public.documents
SET min_access_level_id = NULL
WHERE is_public IS TRUE AND min_access_level_id IS NOT NULL;

ALTER TABLE public.documents
    ADD CONSTRAINT documents_public_access_level_check
    CHECK (is_public IS NOT TRUE OR min_access_level_id IS NULL);
