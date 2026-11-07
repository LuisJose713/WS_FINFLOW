-- FinFlow: configurable billing catalog, promotions, internal Pro entitlements.
-- Apply BEFORE deploying the corresponding backend release.
-- No customer payments are processed or activated by this migration.
CREATE TABLE IF NOT EXISTS public.billing_plans (
    code VARCHAR(16) PRIMARY KEY CHECK (code IN ('FREE','MONTHLY','YEARLY','LIFETIME')),
    title VARCHAR(90) NOT NULL,
    description VARCHAR(240) NOT NULL DEFAULT '',
    price_cop BIGINT NOT NULL CHECK (price_cop >= 0 AND price_cop <= 10000000),
    is_active BOOLEAN NOT NULL DEFAULT true,
    sort_order INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (code <> 'FREE' OR (price_cop = 0 AND is_active = true)),
    CHECK (code = 'FREE' OR price_cop >= 1000)
);

INSERT INTO public.billing_plans(code,title,description,price_cop,is_active,sort_order)
VALUES ('FREE','FinFlow Free','Registra y organiza tus finanzas sin pagar.',0,true,0),
       ('MONTHLY','FinFlow Pro mensual','Acceso Pro con renovación mensual.',9900,true,1),
       ('YEARLY','FinFlow Pro anual','Acceso Pro con renovación anual.',79900,true,2),
       ('LIFETIME','FinFlow Pro de por vida','Pago único por las funciones Pro definidas en los términos de compra.',189900,true,3)
ON CONFLICT (code) DO NOTHING;

CREATE TABLE IF NOT EXISTS public.billing_promotions (
    code VARCHAR(32) PRIMARY KEY,
    title VARCHAR(90) NOT NULL,
    plan_code VARCHAR(16) NOT NULL REFERENCES public.billing_plans(code),
    discount_type VARCHAR(12) NOT NULL CHECK (discount_type IN ('PERCENT','FIXED')),
    discount_value BIGINT NOT NULL CHECK (discount_value > 0),
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    max_redemptions INTEGER CHECK (max_redemptions IS NULL OR max_redemptions > 0),
    redemption_count INTEGER NOT NULL DEFAULT 0 CHECK (redemption_count >= 0),
    is_active BOOLEAN NOT NULL DEFAULT true,
    is_public BOOLEAN NOT NULL DEFAULT false,
    created_by BIGINT REFERENCES public.users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (ends_at > starts_at),
    CHECK (discount_type <> 'PERCENT' OR discount_value BETWEEN 1 AND 90)
);
CREATE INDEX IF NOT EXISTS idx_billing_promotion_valid
    ON public.billing_promotions (plan_code,is_active,starts_at,ends_at);

-- Only admin grants can be created today. Verified gateway orders will be wired
-- to this table in a separate payment integration.
CREATE TABLE IF NOT EXISTS public.billing_entitlements (
    user_id BIGINT PRIMARY KEY REFERENCES public.users(id) ON DELETE CASCADE,
    plan_code VARCHAR(16) NOT NULL REFERENCES public.billing_plans(code),
    source VARCHAR(24) NOT NULL CHECK (source IN ('ADMIN_GRANT','VERIFIED_PAYMENT','STORE_RECEIPT')),
    valid_until TIMESTAMPTZ,
    assigned_by BIGINT REFERENCES public.users(id) ON DELETE SET NULL,
    reason VARCHAR(240),
    issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (plan_code <> 'FREE')
);
CREATE INDEX IF NOT EXISTS idx_billing_entitlements_expiry ON public.billing_entitlements (valid_until);

CREATE TABLE IF NOT EXISTS public.billing_admin_audit (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_user_id BIGINT REFERENCES public.users(id) ON DELETE SET NULL,
    action VARCHAR(48) NOT NULL,
    subject VARCHAR(120) NOT NULL,
    details VARCHAR(350) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_billing_admin_audit_created ON public.billing_admin_audit (created_at DESC);
