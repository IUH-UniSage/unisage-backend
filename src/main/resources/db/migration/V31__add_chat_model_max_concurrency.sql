-- Per-credential cap on in-flight provider requests, enforced by unisage-agent before every call
-- (Z.ai's free GLM models are limited by concurrency, not requests per minute). NULL = no limit,
-- same as max_rpm.
ALTER TABLE public.chat_models
    ADD COLUMN max_concurrency integer;

ALTER TABLE public.chat_models
    ADD CONSTRAINT chat_models_max_concurrency_positive CHECK (max_concurrency IS NULL OR max_concurrency > 0);
