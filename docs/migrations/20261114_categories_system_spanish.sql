-- FinFlow: nombres de categorías globales en español.
-- Aplicado a la base principal Neon de FinFlow el 2026-11-14/15.
-- Repetible para nuevos entornos: solo renombra categorías antiguas del sistema.
-- Conserva los ID, el tipo interno Income/Expense y todas las claves foráneas.
-- No afecta categorías personalizadas (user_id IS NOT NULL).
-- Los clientes Web, iOS y Android consumen los nombres desde GET /categories.
WITH translated(id, english_name, spanish_name, category_type) AS (VALUES
 (9::bigint,  'Entertainment', 'Entretenimiento', 'Expense'),
 (6::bigint,  'Food',          'Alimentación',    'Expense'),
 (10::bigint, 'Other Expense', 'Otros gastos',   'Expense'),
 (8::bigint,  'Rent',          'Arriendo',        'Expense'),
 (7::bigint,  'Transport',     'Transporte',      'Expense'),
 (4::bigint,  'Bonus',         'Bonificaciones',  'Income'),
 (2::bigint,  'Freelance',     'Trabajo independiente', 'Income'),
 (3::bigint,  'Investment',    'Inversiones',     'Income'),
 (5::bigint,  'Other Income',  'Otros ingresos',  'Income'),
 (1::bigint,  'Salary',        'Salario',         'Income')
)
UPDATE public.categories c
SET name = t.spanish_name
FROM translated t
WHERE c.id = t.id AND c.user_id IS NULL
  AND c.name = t.english_name AND c.type = t.category_type;
