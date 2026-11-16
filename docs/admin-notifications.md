# Notificaciones programadas de FinFlow

El administrador puede crear mensajes con título (80 caracteres), contenido
(320 caracteres), destinatarios (`SELF` o `ALL`) y fecha futura. La interfaz usa
hora de Colombia; la API recibe un instante ISO 8601 con zona y PostgreSQL guarda
`TIMESTAMPTZ`. Los mensajes se pueden cancelar antes de comenzar el envío.

## Seguridad y persistencia

- `/admin/notifications` (GET/POST) y `/admin/notifications/{id}` (DELETE)
  requieren `ROLE_ADMIN` en Spring Security.
- El título y contenido se cifran con el `FieldCipher` vigente antes de llegar
  a la base de datos. No se introduce ninguna clave nueva ni se modifican los
  datos financieros existentes.
- La migración `db/notifications-schema.sql` es aditiva e idempotente y se
  ejecuta mediante la inicialización SQL ya existente de Spring Boot.
- El planificador revisa mensajes vencidos cada 30 segundos. Una actualización
  condicional reclama cada campaña una vez. No se reintenta automáticamente un
  envío de resultado incierto; queda marcado como error para revisión.
- `SELF` se resuelve al administrador creador. `ALL` alcanza solo suscripciones
  Web activas y dispositivos nativos que han autorizado avisos.

## Canales y límites reales

Web/PWA usa las claves VAPID ya configuradas. El registro ahora está disponible
para cada usuario autenticado, mediante su autorización en el dispositivo. Los
recordatorios diarios mantienen horarios separados, 10:00 y 16:00 de Colombia.
La prueba individual nunca envía mensajes a otros usuarios.

Android usa una bandeja autenticada y deduplicada por usuario y dispositivo.
Comprueba mensajes cada 30 segundos mientras está activo y mediante WorkManager
en segundo plano (intervalo mínimo de 15 minutos, sujeto a ahorro de batería).
Este mecanismo **no es FCM** y no garantiza entrega al minuto con la app cerrada.
Los IDs enviados se registran; no se persiste texto de mensajes en preferencias.
Cerrar sesión cancela la sincronización y elimina avisos publicados de la cuenta.

La API admite dispositivos `IOS`, pero la recepción remota con la app iOS cerrada
necesita integrar APNs y sus credenciales. Los avisos diarios locales de iOS son
independientes de APNs. No se ha afirmado entrega remota iOS sin ese requisito.

La aceptación del proveedor Web y la disponibilidad en la bandeja nativa no
prueban que la persona haya visto el aviso. La interfaz distingue estos conteos.

## API nativa

- `PUT /notifications/device`: `{deviceId: UUID, platform: ANDROID|IOS, enabled: boolean}`.
- `GET /notifications/messages?deviceId=UUID`: mensajes vencidos y sin acuse de
  este usuario/dispositivo, posteriores a su autorización, durante siete días.
- `POST /notifications/messages/{id}/ack?deviceId=UUID`: acuse idempotente del
  dispositivo después de publicar el aviso. No permite consultar otro usuario.

No se programan campañas reales ni se envían pruebas masivas al desplegar.
