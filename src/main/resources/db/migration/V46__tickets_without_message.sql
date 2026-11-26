-- UNISAGE-107 UX audit: the support page's "Tạo yêu cầu" form files general requests that are not
-- about one chat answer, so a ticket may have no message.

-- 1) message_id becomes optional. The partial unique indexes from V34 are unaffected: NULLs are
--    distinct in a Postgres unique index, so any number of message-less tickets can coexist, and
--    the "one Report per message" / "one ticket per calculation item" rules still hold for the
--    tickets that do have a message.
ALTER TABLE public.tickets ALTER COLUMN message_id DROP NOT NULL;

-- 2) Two new types for message-less tickets (rebuilt from V34's list plus the new values).
ALTER TABLE public.tickets
    DROP CONSTRAINT tickets_type_check,
    ADD CONSTRAINT tickets_type_check CHECK ((type)::text = ANY ((ARRAY[
        'AI_SYSTEM_ERROR', 'AI_UNANSWERED', 'AI_SECURITY_BREACH', 'AI_INAPPROPRIATE', 'OTHER',
        'AI_CALCULATION_WRONG', 'GENERAL_QUESTION', 'ACCOUNT_ISSUE'
    ])::text[]));

-- 3) The type <-> message pairing, mirroring TicketServiceImpl.create: a message-less ticket is
--    GENERAL_QUESTION, ACCOUNT_ISSUE or OTHER; a ticket about a message is never one of the two
--    general types. Every existing row has a message and an old type, so this validates as is.
ALTER TABLE public.tickets
    ADD CONSTRAINT tickets_message_type_check CHECK (
        CASE WHEN message_id IS NULL
            THEN (type)::text = ANY ((ARRAY['GENERAL_QUESTION', 'ACCOUNT_ISSUE', 'OTHER'])::text[])
            ELSE (type)::text <> ALL ((ARRAY['GENERAL_QUESTION', 'ACCOUNT_ISSUE'])::text[])
        END);
