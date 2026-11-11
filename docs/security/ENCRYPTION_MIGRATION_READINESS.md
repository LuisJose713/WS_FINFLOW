# FinFlow — plan de cifrado de datos (QA; NO aplicar a producción)

Fecha: 2026-11-10. Estado: BLOQUEADO para migración de producción. Este documento no ejecuta DDL ni modifica registros.

## Inventario observado en Neon
Proyecto finflow (PostgreSQL 17), rama principal production. 25 tablas. Columnas sensibles o potencialmente sensibles: users.email, users.username, users.first_name, users.last_name, profile_email_challenges.new_email, transactions.description, transactions_log.old_description/new_description, goals.title/description, planned_expenses.title/notes, financial_accounts.name, account_transfers.note, goal_contributions.note, recurring_rules.title, privacy_rights_requests.description y billing_admin_audit.details/subject. Importes y saldos son información financiera y requieren protección, pero su cifrado directo rompe agregaciones, filtros, ordenación y balances.

La base principal muestra columnas de texto ordinarias. NO se ha comprobado que su contenido esté cifrado por la aplicación; no debe anunciarse cifrado integral. La extensión pgcrypto no aparece instalada (solo plpgsql). Hay una rama QA finflow-encryption-qa-20261110, pero expira el 2026-11-11 15:00 UTC. No hay snapshots Neon listados. Hay ramas históricas con nombres de respaldo, pero no constituyen prueba de restauración verificada ni sustituyen un respaldo actual.

## Puertas de seguridad antes de producción
1. Generar respaldo consistente reciente, aislado y cifrado; comprobar restauración en una rama nueva, integridad de conteos y claves foráneas, y documentar RPO/RTO. Mantenerlo durante el rollback.
2. Gestionar claves AES-256-GCM en KMS/secret manager fuera de Git, Neon, logs y backups de la BD. Versionado de claves, rotación, separación de funciones, permisos mínimos y plan de recuperación de claves. No usar una clave fija embebida en código o SQL.
3. Diseñar cifrado de aplicación por campo con nonce aleatorio único, versión y autenticación; usar blind indexes HMAC para igualdad de email/username (normalización, unicidad, registro y login). Mantener password como hash adaptativo, NO cifrado reversible.
4. Crear columnas nuevas *_ciphertext, *_key_version, *_blind_index cuando proceda; nunca sobrescribir ni eliminar originales en la primera fase. Migración por lotes idempotentes, transacciones pequeñas y checkpoint, métricas sin datos personales.
5. Compatibilidad: lectura dual (nuevo primero, legado fallback) y escritura dual temporal con feature flag; asegurar búsqueda, filtros, exportación, autenticación, auditoría, importes y sumatorias. Para importes evaluar cifrado selectivo frente a necesidades de consultas SQL y cálculos en backend; no prometer cifrar todos los importes sin rediseñar las consultas.
6. Tests con datos sintéticos en rama aislada: roundtrip, autenticidad, tampering, índices ciegos, concurrencia, regresión REST/JPA, integridad de agregados, rotación y rollback. Probar con clientes web/iOS/Android y cargas realistas.
7. Despliegue canario, monitoreo de errores y latencia; reversión de feature flag manteniendo columnas originales; no borrar datos legados hasta ventana de observación y restauración probada.

## Riesgos pendientes
- No existe snapshot verificado en el listado de Neon; las ramas existentes no demuestran restauración exitosa.
- No se ha verificado KMS, secretos de claves ni procedimiento de rotación.
- No se han ejecutado pruebas de cifrado ni de compatibilidad de consultas.
- No se ha verificado el despliegue de una implementación cifrada; no existe tal implementación en este cambio.

**Decisión:** producción intacta. No ejecutar backfill, DROP/ALTER de columnas existentes, rotación de claves ni despliegue de cifrado hasta completar todas las puertas anteriores.
