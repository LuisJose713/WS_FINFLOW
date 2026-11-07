-- Run only against finflow_dev AFTER the backend has bootstrapped the schema.
-- Harmless, idempotent categories for testing; no real customer data.
INSERT INTO public.categories (name, type, created_at)
SELECT s.name, s.type, CURRENT_TIMESTAMP
FROM (VALUES
  ('Alimentación','Expense'),
  ('Vivienda','Expense'),
  ('Transporte','Expense'),
  ('Salud','Expense'),
  ('Entretenimiento','Expense'),
  ('Salario','Income'),
  ('Otros ingresos','Income')
) AS s(name,type)
WHERE NOT EXISTS (
  SELECT 1 FROM public.categories c WHERE c.name = s.name AND c.type = s.type
);
