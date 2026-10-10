-- LiteLLM's `deprecation_date` for the model, refreshed by every price sync. NULL = no announced
-- deprecation. A model counts as deprecated from that day on (checked at read time, so it flips
-- without waiting for a sync).
ALTER TABLE public.model_prices
    ADD COLUMN deprecation_date date;
