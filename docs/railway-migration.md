# Migración de FinFlow: Render → Railway

## Alcance y seguridad

Solo se traslada el backend Java 21 / Spring Boot de Render a Railway.
La web continúa en Vercel, PostgreSQL continúa en Neon y el repositorio iOS
conserva su código. No crear una segunda base de datos ni reejecutar
migraciones sobre producción.

**No introducir secretos en Git, PR, tickets, capturas ni logs.**
Trabajar únicamente en un proyecto Railway cuya titularidad haya sido confirmada.
Desplegar esta rama primero como servicio de prueba. Mantener Render activo hasta
completar las verificaciones y el cambio de URL.

## Antes de desplegar

1. Confirmar que Railway corresponde a la cuenta/workspace autorizada.
2. Crear un proyecto privado `finflow` y un servicio conectado al repositorio
   `LuisJose713/WS_FINFLOW`, rama `master` una vez aprobado y fusionado este PR.
3. Configurar la región más cercana posible a Neon (AWS us-east-1).
4. Usar el Dockerfile incluido; aplicación y dependencias usan Java 21.
5. Configurar en **Railway Variables** las mismas credenciales de producción de Render,
   **sin mostrarlas**:

| Variable | Uso |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `SPRING_DATASOURCE_URL` | JDBC de **la misma** instancia Neon: debe comenzar con `jdbc:postgresql://` |
| `SPRING_DATASOURCE_USERNAME` | Usuario actual de Neon |
| `SPRING_DATASOURCE_PASSWORD` | Contraseña actual de Neon |
| `JWT_SECRET` | **Exactamente la misma** clave actual de Render para validar los JWT existentes |

El perfil `prod` mantiene `spring.jpa.hibernate.ddl-auto=none`.
No inventar, regenerar ni rotar `JWT_SECRET` durante el traslado: cambiarlo
cerraría sesiones válidas. Las versiones de token guardadas en Neon también se
conservan porque es la misma base.

Railway puede suministrar `PORT` automáticamente. El backend escucha
en `${PORT:8081}`, por lo que el entorno local anterior sigue usando 8081.
No añadir credenciales a la configuración del repositorio.

## Comprobaciones del servicio

- Configurar el healthcheck HTTP de Railway como `/actuator/health`
  (solo expone estado, sin detalles). Esperar HTTP 200 y estado `UP`.
- Revisar logs de inicio de Spring Boot, conexión JDBC a Neon y estado del deploy.
- Probar `GET /billing/catalog` (ruta pública) y
  `GET /auth/me` sin token (debe devolver 401).
- Con un usuario de prueba existente, comparar `POST /auth/login` desde
  Render y Railway, y repetir `GET /auth/me` en Railway con un JWT válido
  emitido antes del cambio. Registrar tiempos p50/p95 si es posible.
- Verificar preflight CORS desde `https://finflow-opal-five.vercel.app`
  hacia la nueva URL y flujos de registro, metas y transacciones sin modificar
  datos reales innecesariamente.
- Verificar el backend iOS; actualizar cualquier URL de API fija solo
  después de validar el nuevo servicio.

## Cambio y reversión

1. Mantener Render disponible durante todas las pruebas.
2. Cambiar la URL base del frontend en Vercel a la URL HTTPS de Railway,
   usando el mecanismo de configuración real del frontend; publicar y verificar.
3. Actualizar la configuración del cliente SwiftUI cuando corresponda.
4. Confirmar inicio de sesión sin cierre forzado, CORS, gestión de cuentas,
   movimientos, presupuestos y objetivos.
5. Medir latencia en móvil (incluido arranque en frío) y comparar con Render.
6. **Solo después** desactivar Render. Si falla cualquier comprobación, devolver
   la URL del frontend a Render y conservar Neon y sus datos intactos.

## Tareas recurrentes

Existe un planificador `RecurringScheduler` en el backend. Su método
`processDue` usa transacciones, `FOR UPDATE SKIP LOCKED` y
`ON CONFLICT DO NOTHING` para impedir entradas duplicadas en ejecuciones
concurrentes. Aun así, evitar prolongar innecesariamente dos instancias
activas y verificar las reglas recurrentes después del cambio.
