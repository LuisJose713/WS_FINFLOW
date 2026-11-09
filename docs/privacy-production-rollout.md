# FinFlow — privacidad: despliegue y activación

## Estado del código

Backend y frontend incluyen la API y las pantallas para:
- Política versionada `GET /privacy/policy`, pública.
- Registro de aceptación expresa por versión, con registro transaccional en `privacy_consents` y fecha.
- Comunicaciones comerciales **opcionales** (sin preselección), cambio posterior y auditoría append-only.
- Comprobante de autorización para cada persona autenticada.
- Solicitudes autenticadas de acceso, rectificación, supresión y revocatoria, con estado `PENDING`.
- Diálogo de autorización para cuentas existentes. **No se invalidan JWT por desplegar la función.**
- Filtro del backend que deniega operaciones financieras sin consentimiento **únicamente cuando hay política publicada**.

Los registros preexistentes nunca se marcan como aceptados por una migración. Solicitar supresión crea un caso de revisión; **nunca borra datos automáticamente**.

## Dependencias legales antes de activar

La Ley 1581 de 2012 exige identificación, medios de contacto, finalidades y prueba de la autorización. La publicación legal no se debe fingir. La configuración de Railway espera las variables siguientes; **no escribir información ficticia**:

| Variable | Descripción |
| --- | --- |
| `FINFLOW_PRIVACY_CONTROLLER_NAME` | Razón social vigente del responsable; no confundir marca AEVQOR con razón social aún no inscrita |
| `FINFLOW_PRIVACY_CONTROLLER_NIT` | NIT declarado del responsable, sin inventar dígito de verificación |
| `FINFLOW_PRIVACY_CONTACT_EMAIL` | Correo real y atendido para ejercer derechos |
| `FINFLOW_PRIVACY_CONTACT_ADDRESS` | Domicilio / dirección de notificaciones del responsable |
| `FINFLOW_PRIVACY_CONTACT_PHONE` | Teléfono verificable |
| `FINFLOW_PRIVACY_PUBLISHED` | **false** hasta aprobación legal del documento y confirmación de contactos; **true** solo después |

La API devuelve `ready:false` hasta que **todos** los datos anteriores sean correctos y el indicador de publicación esté explícitamente activado. La página pública muestra que la política es un borrador. No se bloquea el inicio de sesión ni el registro heredado durante esta transición. Cuando `ready:true`, todo nuevo registro requiere `privacyAccepted=true` y `privacyVersion` vigente; entonces el backend registra los datos transaccionalmente, y las cuentas antiguas deben consentir para acceder a operaciones financieras. El consentimiento publicitario es independiente. El sistema no prueba por sí solo cumplimiento jurídico integral.

**No modificar JWT_SECRET, Neon existente, token_version ni dominios durante esta activación.** Conservar Render como reversión hasta validar la web.

## Datos: Neon

Tablas `privacy_consents` y `privacy_rights_requests` ya existían en producción. La migración aditiva `docs/migrations/20261109_privacy_marketing_events.sql` crea `privacy_marketing_events` para el historial de cambios comerciales. **No inserta ni modifica consentimientos existentes.**

## Pruebas antes de declarar cumplimiento

1. `GET /privacy/policy` devuelve HTTP 200; con estado pendiente muestra `ready:false`.
2. `GET /privacy/status` sin JWT no expone datos; con JWT solo devuelve datos de la cuenta autenticada.
3. `POST /privacy/consent` sin política legal publicada devuelve 503 y no guarda consentimiento.
4. Con política publicada, la aceptación incorrecta o versión anterior devuelve 400; la correcta crea registro con fecha y versión, sin duplicar.
5. El checkbox comercial no viene marcado y nunca se infiere de la aceptación necesaria. El cambio posterior se audita.
6. `POST /privacy/rights-request` permite al titular registrar solicitud, sin borrar datos ni conceder acceso a otra cuenta.
7. Comprobar inicio de sesión con JWT previo, facturas / movimientos / metas, CORS Vercel y versión web en móvil.
8. Preparar procedimiento real para que una persona autorizada **atienda y responda** las solicitudes de derechos; almacenar no equivale a resolver.
9. Revisar el texto legal, responsables, subencargados, conservación y transferencias con asesoría pertinente.

## Limitaciones actualmente explícitas

- Los datos reales del responsable y la aceptación del contenido legal no se pueden inferir.
- Las solicitudes se registran para tratamiento humano; no hay borrado ni respuesta por correo automáticos.
- La política publicada debe precisar conservación, subencargados, transferencias y canal de atención.
- No activar el flag de publicación sin una revisión legal y pruebas de extremo a extremo.

Identidad aportada por titular: SoftDevCode S.A.S. — NIT 901796447, marca de producto AEVQOR / FinFlow. Verificar en RUES antes de la aprobación jurídica. No reemplazar la razón social con AEVQOR S.A.S. sin cambio inscrito.
