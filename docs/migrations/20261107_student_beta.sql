-- Student Beta program. No educational documents, identification numbers,
-- or scans are collected. Admin verifies eligibility separately, before approval.
-- No checkout or automatic payment activation is provided here.
CREATE TABLE IF NOT EXISTS public.billing_student_beta_settings (
 id SMALLINT PRIMARY KEY CHECK (id=1),
 active BOOLEAN NOT NULL DEFAULT true,
 discount_percent INTEGER NOT NULL DEFAULT 30 CHECK (discount_percent BETWEEN 1 AND 70),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 updated_by BIGINT REFERENCES public.users(id) ON DELETE SET NULL
);
INSERT INTO public.billing_student_beta_settings(id,active,discount_percent)
VALUES (1,true,30) ON CONFLICT(id) DO NOTHING;

CREATE TABLE IF NOT EXISTS public.billing_student_beta_applications (
 user_id BIGINT PRIMARY KEY REFERENCES public.users(id) ON DELETE CASCADE,
 institution VARCHAR(120) NOT NULL,
 status VARCHAR(12) NOT NULL DEFAULT 'PENDING'
   CHECK (status IN ('PENDING','APPROVED','REJECTED')),
 submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 reviewed_at TIMESTAMPTZ,
 approved_until TIMESTAMPTZ,
 reviewed_by BIGINT REFERENCES public.users(id) ON DELETE SET NULL,
 CHECK (status<>'APPROVED' OR approved_until IS NOT NULL)
);
CREATE INDEX IF NOT EXISTS idx_student_beta_status
 ON public.billing_student_beta_applications(status,submitted_at DESC);
