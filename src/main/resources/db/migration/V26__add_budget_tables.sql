-- UNISAGE-90: Cost Tracking + Budget Management, Task 3. Budget configuration, global alert
-- settings (singleton), and alert send history. See
-- changes/23-09-2026-Cost-Tracking-Budget-Management/plan.md "Budget", "BudgetAlertSetting",
-- "BudgetAlertLog" for the full design this migration encodes.

-- ── budgets ──────────────────────────────────────────────────────────────────

CREATE TABLE public.budgets (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone,
    created_by uuid,
    updated_at timestamp(6) without time zone,
    updated_by uuid,
    is_active boolean DEFAULT true,
    scope character varying(20) NOT NULL,
    scope_provider character varying(255),
    scope_purpose character varying(20),
    period character varying(20) NOT NULL,
    limit_usd numeric(18, 8) NOT NULL,
    action character varying(20) NOT NULL,
    throttle_max_concurrency integer,
    is_enabled boolean NOT NULL DEFAULT true,
    CONSTRAINT budgets_pkey PRIMARY KEY (id),
    CONSTRAINT fk_budgets_created_by FOREIGN KEY (created_by) REFERENCES public.users(id),
    CONSTRAINT fk_budgets_updated_by FOREIGN KEY (updated_by) REFERENCES public.users(id),
    CONSTRAINT budgets_scope_check CHECK (scope IN ('SYSTEM', 'PROVIDER', 'PURPOSE')),
    CONSTRAINT budgets_period_check CHECK (period IN ('DAILY', 'MONTHLY')),
    CONSTRAINT budgets_action_check CHECK (action IN ('ALERT', 'THROTTLE', 'BLOCK')),
    CONSTRAINT budgets_scope_ref_consistent_check CHECK (
        (scope = 'SYSTEM' AND scope_provider IS NULL AND scope_purpose IS NULL)
        OR (scope = 'PROVIDER' AND scope_provider IS NOT NULL AND scope_purpose IS NULL)
        OR (scope = 'PURPOSE' AND scope_provider IS NULL AND scope_purpose IS NOT NULL)
    ),
    CONSTRAINT budgets_throttle_consistent_check CHECK (
        (action = 'THROTTLE' AND throttle_max_concurrency IS NOT NULL AND throttle_max_concurrency > 0)
        OR (action <> 'THROTTLE' AND throttle_max_concurrency IS NULL)
    )
);

-- Not a single combined index: PostgreSQL treats every NULL as distinct in a unique index, so one
-- index over (scope, scope_provider, scope_purpose, period) would never block two enabled SYSTEM
-- budgets for the same period (scope_provider/scope_purpose are both NULL on both rows).
CREATE UNIQUE INDEX ux_budgets_system ON public.budgets (period)
    WHERE is_enabled AND is_active AND scope = 'SYSTEM';
CREATE UNIQUE INDEX ux_budgets_provider ON public.budgets (lower(scope_provider), period)
    WHERE is_enabled AND is_active AND scope = 'PROVIDER';
CREATE UNIQUE INDEX ux_budgets_purpose ON public.budgets (scope_purpose, period)
    WHERE is_enabled AND is_active AND scope = 'PURPOSE';

-- ── budget_alert_settings: singleton, seeded below ──────────────────────────

CREATE TABLE public.budget_alert_settings (
    id smallint PRIMARY KEY DEFAULT 1,
    thresholds_percent integer[] NOT NULL DEFAULT ARRAY[50, 80, 100],
    spike_detection_enabled boolean NOT NULL DEFAULT false,
    spike_threshold_percent integer NOT NULL DEFAULT 50,
    in_app_enabled boolean NOT NULL DEFAULT true,
    email_enabled boolean NOT NULL DEFAULT false,
    email_recipients text[] NOT NULL DEFAULT ARRAY[]::text[],
    slack_enabled boolean NOT NULL DEFAULT false,
    updated_at timestamp(6) without time zone NOT NULL DEFAULT now(),
    CONSTRAINT budget_alert_settings_singleton CHECK (id = 1)
);

INSERT INTO public.budget_alert_settings (id) VALUES (1);

-- ── budget_alert_log ─────────────────────────────────────────────────────────

CREATE TABLE public.budget_alert_log (
    id uuid NOT NULL,
    alert_type character varying(20) NOT NULL,
    budget_id uuid,
    period_start date NOT NULL,
    threshold_percent integer,
    channel character varying(20) NOT NULL,
    dedupe_key character varying(255) NOT NULL,
    spent_usd numeric(18, 8) NOT NULL,
    limit_usd numeric(18, 8),
    status character varying(20) NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    last_attempt_at timestamp(6) without time zone,
    next_attempt_at timestamp(6) without time zone,
    error_message text,
    sent_at timestamp(6) without time zone,
    dismissed_at timestamp(6) without time zone,
    dismissed_by uuid,
    created_at timestamp(6) without time zone NOT NULL DEFAULT now(),
    CONSTRAINT budget_alert_log_pkey PRIMARY KEY (id),
    CONSTRAINT ux_budget_alert_log_dedupe_key UNIQUE (dedupe_key),
    CONSTRAINT fk_budget_alert_log_budget FOREIGN KEY (budget_id) REFERENCES public.budgets(id) ON DELETE SET NULL,
    CONSTRAINT fk_budget_alert_log_dismissed_by FOREIGN KEY (dismissed_by) REFERENCES public.users(id) ON DELETE SET NULL,
    CONSTRAINT budget_alert_log_alert_type_check CHECK (alert_type IN ('THRESHOLD', 'SPIKE')),
    CONSTRAINT budget_alert_log_channel_check CHECK (channel IN ('IN_APP', 'EMAIL', 'SLACK')),
    CONSTRAINT budget_alert_log_status_check
        CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'GAVE_UP', 'SKIPPED'))
);

-- Supports the retry job's WHERE (status IN ('PENDING','FAILED') AND next_attempt_at <= now()).
CREATE INDEX ix_budget_alert_log_status_next_attempt ON public.budget_alert_log (status, next_attempt_at);
