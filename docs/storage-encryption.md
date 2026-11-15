# Cifrado del almacenamiento de FinFlow

La API cifra los campos privados con AES-256-GCM antes de enviarlos a PostgreSQL.
Cada escritura usa un nonce aleatorio de 96 bits, una etiqueta de autenticación
de 128 bits y contexto asociado al campo. El descifrado ocurre en Java. La clave
no se guarda en PostgreSQL, SQL, Git, logs ni respuestas HTTP.

## Cobertura

| Información | Almacenamiento |
| --- | --- |
| Correos, nombres, apellidos, avatar, zona horaria y antiguo refresh token de usuarios | Cifrado |
| Nombres de categorías y billeteras | Cifrado |
| Importes, títulos, notas, etiquetas y fechas financieras de movimientos, metas, aportes, gastos programados, transferencias y reglas recurrentes | Cifrado |
| Límites de presupuestos actuales y heredados | Cifrado |
| Descripciones de solicitudes de privacidad y correos de verificaciones pendientes | Cifrado |
| Motivos de accesos concedidos y detalles/asuntos de auditoría de facturación | Cifrado |
| Endpoint y claves de suscripciones push | Cifrado; endpoint de búsqueda como HMAC |
| Identidad y correo de Google vinculado | Cifrado; identificador de búsqueda como HMAC |
| Importes, descripciones y fechas históricas de transactions_log | Cifrado |
| Contraseñas y sesiones modernas | BCrypt y hashes de tokens existentes; se conservan |
| IDs, relaciones, roles, enumeraciones de tipo/estado/moneda, periodos de presupuesto, marcas técnicas de auditoría y de ejecución, catálogo público de precios y promociones | Metadatos operativos; no cifrados por esta migración |

Esta protección no es cifrado de extremo a extremo: el backend puede descifrar
los datos para el propietario autenticado y calcular reportes. No oculta el
esquema, el número de filas, las relaciones ni los metadatos de la tabla anterior.
Las copias históricas y snapshots anteriores conservan su estado original y
deben gestionarse con sus propios permisos y retención.

## Configuración y recuperación

- `FINFLOW_FIELD_ENCRYPTION_ENABLED=true` activa el formato protegido.
- `FINFLOW_FIELD_ENCRYPTION_KEY_BASE64` contiene exactamente 32 bytes aleatorios
  codificados en Base64. Usar la misma clave en cada instancia que comparte la BD.
- Guardar la clave como secreto del proveedor y conservar un respaldo seguro.
  No regenerarla en un despliegue ni sustituirla por la clave JWT. La pérdida de
  la clave impide recuperar los campos cifrados.
- Mantener `spring.jpa.hibernate.ddl-auto=none` en producción. Una aplicación con
  cifrado desactivado o una clave diferente rechaza una base ya migrada.
- La rotación de claves exige una migración específica; cambiar una variable
  por otra clave no realiza la rotación.

## Migración y compatibilidad

`StorageEncryptionMigration` se ejecuta antes de marcar el servicio como sano.
Usa un bloqueo asesor y bloqueos de tablas para serializar escritores. En una sola
transacción cambia los tipos de los campos protegidos a texto, cifra las filas,
compara huellas de los valores originales con los valores descifrados y crea
restricciones que rechazan nuevas escrituras legibles. Un error revierte toda
la transacción. La versión y una comprobación HMAC de la clave quedan registradas
en `finflow_storage_encryption`; la clave no aparece en esa tabla.

Se mantienen las claves primarias y foráneas, el propietario administrativo,
la unicidad del correo/usuario y de nombres por propietario, y la versión de
sesión. Los índices de igualdad privados usan HMAC-SHA256 con una subclave
derivada y propósito separado. La validación de límites/importes positivos
continúa en los servicios; las antiguas comprobaciones numéricas se sustituyen
por comprobaciones del formato cifrado.

Las búsquedas de texto, filtros por fechas privadas y sumas se realizan en la
aplicación después de restringir las lecturas por propietario. Esto evita enviar
la clave o importes legibles a SQL, pero requiere leer los movimientos del usuario;
hay que medir esa carga antes de escalar a historiales grandes.

Durante el primer cambio de esquema, las instancias antiguas pueden esperar los
bloqueos o rechazar una operación hasta que el proveedor enrute a la nueva versión.
Actualizar Railway y el servicio Render que todavía comparta la base con la misma
clave y el mismo código. No volver a desplegar un backend anterior sobre la base
cifrada. Una reversión completa requiere coordinar código, secreto y snapshot.

## Verificación

Primero ejecutar sobre una copia aislada de producción. El test optativo
`StorageEncryptionIntegrationTest` requiere `FINFLOW_ENCRYPTION_INTEGRATION=true`
y credenciales de esa copia. Usa una clave exclusiva de pruebas declarada en el
test: jamás configurar esa clave en producción. Comprueba lectura de filas
existentes, registro/login, búsquedas, movimientos, saldos, transferencias,
presupuestos, metas/aportes, recurrencias idempotentes, gastos programados,
PDF/XLSX/CSV, segunda ejecución, rechazo de clave incorrecta, rechazo de
escritura legible y eliminación de la cuenta sintética.

Los tests de `FieldCipherTest` verifican nonces diferentes, Unicode, nulos,
cadena vacía, integridad, separación de contexto, índices HMAC y precisión.
Después del despliegue comprobar versión 3, cobertura sin valores legibles en
los campos del catálogo, integridad referencial, salud HTTP y operaciones de una
cuenta sintética. No registrar valores descifrados durante esas comprobaciones.
