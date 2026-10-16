-- Staff-only retrieval trace of an assistant message, pushed by unisage-agent once per turn: the
-- trace id its log lines carry, and every chunk and web page the answer was generated from (cited
-- or not). Never read by any public API - only the staff ticket detail shows it. One row per
-- message; rows go away with their message (guest cleanup bulk-deletes messages, so a DB cascade).
CREATE TABLE public.message_traces (
    message_id uuid NOT NULL,
    trace_id character varying(100),
    chunks jsonb NOT NULL DEFAULT '[]'::jsonb,
    web_sources jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT message_traces_pkey PRIMARY KEY (message_id),
    CONSTRAINT fk_message_traces_message FOREIGN KEY (message_id)
        REFERENCES public.messages(id) ON DELETE CASCADE
);
