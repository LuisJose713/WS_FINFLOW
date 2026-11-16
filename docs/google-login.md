# Continuar con Google en FinFlow

Esta integración conserva las cuentas existentes: el vínculo usa el Google `sub`
verificado y el `users.id` original; **no** une cuentas por correo.

## Activación
1. En Google Cloud, configura la pantalla de consentimiento OAuth y los
   clientes OAuth para Web, Android e iOS, con los dominios y firmas correctos.
2. En Railway configura `FINFLOW_GOOGLE_WEB_CLIENT_ID` (ID OAuth Web público).
   Si el SDK de iOS emite un ID token con otro `aud`, agrega los IDs exactos
   separados por coma en `FINFLOW_GOOGLE_CLIENT_IDS`.
   **Nunca** incluyas secretos OAuth en los clientes ni en Git.
3. No se muestra el botón de Google en Web si el backend no proporciona
   configuración. Los métodos actuales siguen intactos.
4. En Web, un usuario nuevo puede elegir Google en Registro sin crear una
   contraseña previa. FinFlow verifica su identidad, solicita el consentimiento
   de la política publicada y crea la cuenta ya vinculada, con su mes de prueba.
5. Si ese correo ya tiene cuenta, debe iniciar sesión con su contraseña y
   vincular Google en Seguridad. No se duplica ni se fusiona esa cuenta.
6. Las cuentas creadas con Google pueden crear después su primera contraseña
   desde Seguridad, confirmando de nuevo la identidad de Google vinculada.
   Esto permite las acciones que requieren contraseña, incluida la eliminación
   de la cuenta. No es posible desvincular Google dejando la cuenta sin acceso.

## Seguridad
- Se verifica firma RS256 usando JWKS de Google, audiencia permitida,
  emisor, expiración y `email_verified`.
- El registro explícito solo crea usuarios nuevos. Los correos existentes,
  incluidos los registros antiguos con mayúsculas, siguen requiriendo vínculo
  autenticado. Un reintento de registro conserva el mismo ID y el mismo ensayo.
- La creación de cuenta, vínculo, consentimiento y prueba se confirma en una
  transacción; los callbacks concurrentes del mismo registro se serializan.
- Una cuenta de Google nueva tiene el marcador no autenticable `!GOOGLE_ONLY`
  hasta que cree su contraseña; no se genera ni comparte una contraseña secreta.
- Crear la primera contraseña exige JWT FinFlow e ID token del Google `sub`
  vinculado y revoca las sesiones anteriores. No reemplaza contraseñas existentes.
- Los tokens de Google no se guardan en la base de datos.
- La sesión FinFlow sigue siendo propia y revocable; las credenciales de
  navegador son cookies Secure, SameSite y HttpOnly.
- Tabla creada automáticamente al iniciar desde `db/session-schema.sql`.
- `POST /auth/google/login` y `/auth/google/register` son públicos;
  `POST /auth/google/password` y `POST/DELETE /auth/google/link`
  requieren JWT FinFlow vigente y el mismo correo verificado.
