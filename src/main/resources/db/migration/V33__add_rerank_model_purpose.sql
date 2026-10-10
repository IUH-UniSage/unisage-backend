-- RERANK: the chat-time model that judges which retrieved chunks answer the question.
-- Split from EXTRACTION (bulk ingest enrichment, picked for cost) so each can use a
-- different model. Optional - with no ACTIVE RERANK row the agent uses EXTRACTION.
ALTER TABLE public.chat_models
    DROP CONSTRAINT chat_models_model_purpose_check,
    ADD CONSTRAINT chat_models_model_purpose_check
        CHECK (model_purpose IN ('CHAT', 'EMBEDDING', 'EXTRACTION', 'RERANK'));
