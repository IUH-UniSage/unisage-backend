-- groq and mistral are no longer supported cloud providers. Existing rows are kept (SA can still
-- see and delete them) but taken out of routing, and any open verification job that would build
-- a groq/mistral client is cancelled - the agent no longer ships those SDKs.

UPDATE public.chat_model_verifications v
SET status = 'CANCELLED'
WHERE v.status IN ('QUEUED', 'RUNNING')
  AND (
    lower(v.candidate_llm_provider) IN ('groq', 'mistral')
    OR v.chat_model_id IN (
        SELECT m.id FROM public.chat_models m
        WHERE m.source_type = 'CLOUD_API' AND lower(m.llm_provider) IN ('groq', 'mistral')
    )
  );

UPDATE public.chat_models
SET status = 'INACTIVE'
WHERE source_type = 'CLOUD_API'
  AND lower(llm_provider) IN ('groq', 'mistral')
  AND status <> 'INACTIVE';

-- The agent only reloads its credential snapshot when this version moves.
UPDATE public.model_registry_version SET version = version + 1, updated_at = now() WHERE id = 1;
