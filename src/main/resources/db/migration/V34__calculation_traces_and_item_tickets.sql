-- UNISAGE-99 (SPEC-calculation-node §7.2(b), §7.3): staff-only calculation traces, and one
-- AI_CALCULATION_WRONG ticket per wrong calculation item next to the regular per-message Report.

-- 1) calculation_traces: the full trace of each calculation item of an assistant message. Never
--    read by any public API; staff only see it copied into the item's ticket description. Rows go
--    away with their message (guest cleanup bulk-deletes messages, so this must be a DB cascade).
CREATE TABLE public.calculation_traces (
    id uuid NOT NULL,
    message_id uuid NOT NULL,
    item_id character varying(4) NOT NULL,
    run_id character varying(100),
    trace jsonb NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT calculation_traces_pkey PRIMARY KEY (id),
    CONSTRAINT calculation_traces_message_item_key UNIQUE (message_id, item_id),
    CONSTRAINT fk_calculation_traces_message FOREIGN KEY (message_id)
        REFERENCES public.messages(id) ON DELETE CASCADE
);

-- 2) tickets.calculation_item_id: set only on AI_CALCULATION_WRONG tickets.
ALTER TABLE public.tickets ADD COLUMN calculation_item_id character varying(4);

-- 3) "One ticket per message" now only holds for regular Reports; each wrong calculation item gets
--    its own ticket, so a message may carry one Report plus one ticket per item.
ALTER TABLE public.tickets DROP CONSTRAINT tickets_message_id_key;
CREATE UNIQUE INDEX tickets_message_report_key
    ON public.tickets (message_id) WHERE calculation_item_id IS NULL;
CREATE UNIQUE INDEX tickets_message_calculation_item_key
    ON public.tickets (message_id, calculation_item_id) WHERE calculation_item_id IS NOT NULL;

-- 4) New type, and the type <-> item pairing enforced in the database.
ALTER TABLE public.tickets
    DROP CONSTRAINT tickets_type_check,
    ADD CONSTRAINT tickets_type_check CHECK ((type)::text = ANY ((ARRAY[
        'AI_SYSTEM_ERROR', 'AI_UNANSWERED', 'AI_SECURITY_BREACH', 'AI_INAPPROPRIATE', 'OTHER',
        'AI_CALCULATION_WRONG'
    ])::text[])),
    ADD CONSTRAINT tickets_calculation_item_check CHECK (
        (calculation_item_id IS NOT NULL) = ((type)::text = 'AI_CALCULATION_WRONG'));
