-- Dynamic Model Registry: health reports (POST /internal/model-registry/credentials/{id}/health)
-- need somewhere to store *why* the last error happened, not just that one happened
-- (error_count/last_error_at already existed before this feature). See plan.md
-- "Internal API contract" endpoint #3.

ALTER TABLE public.chat_models
    ADD COLUMN last_error_code character varying(100),
    ADD COLUMN last_error_message character varying(500);
