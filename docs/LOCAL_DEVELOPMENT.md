# FinFlow — desarrollo y pruebas aisladas en Windows

> Usa un equipo donde esté autorizado trabajar en proyectos personales.
> No copies datos ni credenciales de Neon o Render al entorno local.

## Requisitos

- Windows 10/11 con Git, Java 21, Node.js 20 y Docker Desktop (si la política del equipo lo permite).
- Dos repositorios hermanos: `finflow` (Angular) y `WS_FINFLOW` (API Java).
- No necesitas instalar PostgreSQL nativamente; Docker proporciona PostgreSQL 17.

## 1. Clonar

Abre PowerShell:

```powershell
mkdir "$HOME\Documents\FinFlowLab"
cd "$HOME\Documents\FinFlowLab"
git clone https://github.com/LuisJose713/finflow.git
git clone https://github.com/LuisJose713/WS_FINFLOW.git
```

## 2. Base de datos de prueba

```powershell
cd .\WS_FINFLOW
docker compose -f docker-compose.local.yml up -d
docker compose -f docker-compose.local.yml ps
```

PostgreSQL escucha **solo en 127.0.0.1:55432**, separado de Neon y de cualquier PostgreSQL local en el puerto 5432.

- Base de datos: `finflow_dev`
- Usuario: `finflow_dev`
- Contraseña: `finflow_local_only_2026` (EXCLUSIVA PARA DESARROLLO LOCAL).
- No abras el puerto de este contenedor en el firewall o la red corporativa.

## 3. Backend en modo local

En una nueva terminal PowerShell, dentro de `WS_FINFLOW`:

```powershell
$env:SPRING_PROFILES_ACTIVE = "local"
$env:JWT_SECRET = "finflow-local-only-signing-key-not-for-production-2026"
.\mvnw.cmd spring-boot:run
```

Verifica `http://localhost:8081/categories` (requiere token). La API usa PostgreSQL local por el perfil `local`; el perfil de producción NO está habilitado.

Cuando el backend haya iniciado una vez, puedes insertar categorías demo:

```powershell
Get-Content .\scripts\seed-local.sql | docker compose -f docker-compose.local.yml exec -T postgres psql -U finflow_dev -d finflow_dev
```

Hibernate crea las tablas necesarias en **la base de datos local** con `ddl-auto=update`. Este mecanismo sirve exclusivamente para desarrollo, no reemplaza migraciones controladas de producción.

## 4. Frontend Angular

En otra terminal:

```powershell
cd ..\finflow
npm ci
npm start
```

Abre `http://localhost:4200`. El archivo `src/environments/environment.ts` ya apunta a `http://localhost:8081` en desarrollo; no necesitas editarlo.

Crea dos cuentas ficticias distintas y prueba que la cuenta A no pueda consultar, editar o eliminar los recursos de la cuenta B. Revisa también registro, login/logout, dashboard, transacciones, metas, abonos, gastos planeados, exportación y cambio de tema.

## 5. Ejecutar pruebas automatizadas

En backend:

```powershell
.\mvnw.cmd clean verify
```

En frontend:

```powershell
npm run build:prod
```

La compilación valida código, pero no sustituye pruebas reales de navegación y CRUD.

## 6. Reiniciar el laboratorio

Para detener los contenedores conservando los datos:

```powershell
docker compose -f docker-compose.local.yml down
```

Para **destruir únicamente** la base local y empezar de cero:

```powershell
docker compose -f docker-compose.local.yml down -v
```

ATENCIÓN: `down -v` elimina los datos de prueba del contenedor local. Nunca ejecutes comandos de borrado contra Neon/producción.

## Si Docker está bloqueado por TI

Pide autorización antes de instalar PostgreSQL 17 nativo, configura una base de datos `finflow_dev` en el puerto local disponible, y ajusta `FINFLOW_DEV_JDBC_URL`, `FINFLOW_DEV_DB_USER` y `FINFLOW_DEV_DB_PASSWORD`. No uses Neon producción como sustituto. Para un ambiente remoto de pruebas, configura un proyecto/base separados con datos completamente ficticios.
