-- FinFlow PostgreSQL: reviewed one-time role migration.
-- Run with an authorized database administrator after a verified backup.
-- Application code does not auto-run this file (ddl-auto=none).
BEGIN;
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS role VARCHAR(16);
UPDATE public.users SET role = 'USER' WHERE role IS NULL;
ALTER TABLE public.users ALTER COLUMN role SET DEFAULT 'USER';
ALTER TABLE public.users ALTER COLUMN role SET NOT NULL;
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'users_role_check'
      AND conrelid = 'public.users'::regclass
  ) THEN
    ALTER TABLE public.users ADD CONSTRAINT users_role_check
      CHECK (role IN ('USER', 'ADMIN'));
  END IF;
END $$;
COMMIT;

-- Promote only after account ownership is verified and a secure password is set.
-- Run separately with an authorized administrator; never promote on public registration.
-- UPDATE public.users SET role = 'ADMIN' WHERE lower(email) = lower('<verified-email>');
