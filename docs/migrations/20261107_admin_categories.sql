-- FinFlow / AEVQOR
-- Applied on Neon production via a tested Neon migration on 2026-11-07.
-- Keep here as schema history for reproducible environments.
-- IMPORTANT: do not blindly re-run this already-applied file in production.
-- Categories with NULL user_id are shared read-only system categories.
-- Every custom category belongs to exactly one authenticated account.
ALTER TABLE public.users
    ADD COLUMN IF NOT EXISTS role VARCHAR(16) NOT NULL DEFAULT 'USER';
ALTER TABLE public.users
    ADD CONSTRAINT users_role_allowed CHECK (role IN ('USER','ADMIN'));
UPDATE public.users
SET role = CASE WHEN lower(email) = 'stpptx@icloud.com' THEN 'ADMIN' ELSE 'USER' END;
ALTER TABLE public.users
    ADD CONSTRAINT users_admin_email_guard
    CHECK (role <> 'ADMIN' OR lower(email) = 'stpptx@icloud.com');
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_single_admin
    ON public.users (role) WHERE role = 'ADMIN';
ALTER TABLE public.categories
    ADD COLUMN IF NOT EXISTS user_id INTEGER REFERENCES public.users(id) ON DELETE CASCADE;
ALTER TABLE public.categories DROP CONSTRAINT IF EXISTS categories_name_key;
CREATE UNIQUE INDEX IF NOT EXISTS idx_categories_global_name_type
    ON public.categories (lower(name), lower(type)) WHERE user_id IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_categories_owner_name_type
    ON public.categories (user_id, lower(name), lower(type)) WHERE user_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_categories_user_id ON public.categories (user_id);
