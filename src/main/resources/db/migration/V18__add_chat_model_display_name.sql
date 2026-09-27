-- Admin often has several credentials for the same provider+model (rotated keys, a test
-- account vs. a production account...) - a nickname lets them tell which is which without
-- decrypting/copying the API key. Purely a label, no effect on routing/verification.
ALTER TABLE public.chat_models
    ADD COLUMN display_name character varying(100);
