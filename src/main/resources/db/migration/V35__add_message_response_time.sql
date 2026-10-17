-- Total time of an assistant turn in milliseconds, from the agent receiving the question to the
-- answer being stored; the chat shows it next to the answer's actions. NULL = not measured
-- (USER messages, failed or stopped answers, and answers written before this column).
ALTER TABLE public.messages
    ADD COLUMN response_time_ms integer;
