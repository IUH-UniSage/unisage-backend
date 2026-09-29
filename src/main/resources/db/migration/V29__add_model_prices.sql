-- Model prices owned by backend-java: synced daily from LiteLLM's price map, overridable by SA.
-- All timestamps are UTC, same convention as request_usage_logs.

CREATE TABLE public.model_prices (
    id uuid NOT NULL,
    provider character varying(50) NOT NULL,
    model_name character varying(255) NOT NULL,
    input_per_million numeric(18, 8) NOT NULL,
    output_per_million numeric(18, 8),
    cached_input_per_million numeric(18, 8),
    source character varying(20) NOT NULL,
    synced_at timestamp(6) without time zone,
    created_at timestamp(6) without time zone NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    updated_by uuid,
    CONSTRAINT model_prices_pkey PRIMARY KEY (id),
    CONSTRAINT ux_model_prices_provider_model UNIQUE (provider, model_name),
    CONSTRAINT model_prices_source_check CHECK (source IN ('LITELLM', 'MANUAL')),
    CONSTRAINT model_prices_non_negative CHECK (
        input_per_million >= 0
        AND (output_per_million IS NULL OR output_per_million >= 0)
        AND (cached_input_per_million IS NULL OR cached_input_per_million >= 0)
    ),
    CONSTRAINT fk_model_prices_updated_by FOREIGN KEY (updated_by) REFERENCES public.users(id) ON DELETE SET NULL
);

-- Append-only: one row per actual price change, from sync or from SA.
CREATE TABLE public.model_price_changes (
    id uuid NOT NULL,
    provider character varying(50) NOT NULL,
    model_name character varying(255) NOT NULL,
    change_type character varying(20) NOT NULL,
    old_input_per_million numeric(18, 8),
    new_input_per_million numeric(18, 8),
    old_output_per_million numeric(18, 8),
    new_output_per_million numeric(18, 8),
    old_cached_input_per_million numeric(18, 8),
    new_cached_input_per_million numeric(18, 8),
    changed_by uuid,
    changed_at timestamp(6) without time zone NOT NULL,
    CONSTRAINT model_price_changes_pkey PRIMARY KEY (id),
    CONSTRAINT model_price_changes_type_check CHECK (change_type IN (
        'SYNC_CREATE', 'SYNC_UPDATE', 'MANUAL_CREATE', 'MANUAL_UPDATE', 'MANUAL_RESET'
    )),
    CONSTRAINT fk_model_price_changes_changed_by FOREIGN KEY (changed_by) REFERENCES public.users(id) ON DELETE SET NULL
);

CREATE INDEX ix_model_price_changes_model ON public.model_price_changes (provider, model_name, changed_at);
CREATE INDEX ix_model_price_changes_changed_at ON public.model_price_changes (changed_at);

INSERT INTO public.permissions (id, created_at, is_active, name, path, method, resource_type, description)
SELECT gen_random_uuid(), now(), true, v.name, v.path, v.method, v.resource_type, v.description
FROM (VALUES
    ('MODEL_PRICING_ALL', '/model-pricing/**', 'ALL', 'BUDGET', 'Toàn quyền bảng giá model AI'),
    ('MODEL_PRICING_READ', '/model-pricing/**', 'GET', 'BUDGET', 'Xem bảng giá và lịch sử giá model AI'),
    ('MODEL_PRICING_CREATE', '/model-pricing/**', 'POST', 'BUDGET', 'Thêm giá model AI và đồng bộ giá'),
    ('MODEL_PRICING_UPDATE', '/model-pricing/**', 'PUT', 'BUDGET', 'Sửa giá model AI'),
    ('MODEL_PRICING_DELETE', '/model-pricing/**', 'DELETE', 'BUDGET', 'Khôi phục giá model AI')
) AS v(name, path, method, resource_type, description)
WHERE NOT EXISTS (SELECT 1 FROM public.permissions p WHERE p.name = v.name);

INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM public.roles r, public.permissions p
WHERE r.name = 'SUPER_ADMIN' AND p.name = 'MODEL_PRICING_ALL'
ON CONFLICT DO NOTHING;
