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
4. El usuario inicia sesión con contraseña, vincula Google en su perfil
   y luego podrá entrar con Google en las plataformas donde esté configurado.

## Seguridad
- Se verifica firma RS256 usando JWKS de Google, audiencia permitida,
  emisor, expiración y `email_verified`.
- No se crean usuarios ni se unen cuentas automáticamente: eso exige
  políticas de consentimiento y reautenticación propias.
- Los tokens de Google no se guardan en la base de datos.
- La sesión FinFlow sigue siendo propia y revocable; las credenciales de
  navegador son cookies Secure, SameSite y HttpOnly.
- Tabla creada automáticamente al iniciar desde `db/session-schema.sql`.
- `POST /auth/google/login` es público; `POST/DELETE /auth/google/link`
  requieren JWT FinFlow vigente y el mismo correo verificado.
