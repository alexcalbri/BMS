# BMS --- Self-Hosting Guide

> Status: **Bootstrap guide**
>
> BMS is still evolving. This document intentionally separates
> steps that can be verified from the repository from architecture that
> is planned. Before publishing a release, replace placeholders with
> exact commands tested against that release.

## 1. Deployment Model

### Web application in the initial installation

The installer builds and installs both Ktor and the browser application.
`https://YOUR_DOMAIN/` serves the web login instead of the Ktor greeting.
Nginx serves `/opt/bms/web`, with an `index.html` fallback for client
navigation, and forwards `/auth/` to Ktor on loopback without rewriting
the path. The browser login starts with the current origin as its server URL;
use that URL for same-origin authentication. Other server URLs require a
separately configured CORS policy and are not enabled by this installer.

The web build command is:

```bash
./gradlew -PwebOnly=true :app:webApp:jsBrowserDistribution
```

The result is `app/webApp/build/dist/js/productionExecutable/`.
`core/build-web.gradle.kts` and `app/shared/build-web.gradle.kts` reuse
existing sources with JS-only targets so Ubuntu needs no Android/iOS SDK.
Keep their shared dependencies aligned with the normal build profiles.
Gradle downloads Node/Yarn and frontend dependencies automatically.

Publish these build profiles, the client sources, settings and installer
together before installing from GitHub. This change applies to new
installations; an already running installer will not acquire the new logic.
Do not rerun this first-install script against an installed server. Existing
deployments need a separate web build/deployment and Nginx update.

### Initial owner provisioning (local implementation; clean-host test pending)

The installer now asks for a central administration database and one set of
credentials for `server_owner`. It does not create the first company. The
server owner is a PostgreSQL login role with tenant-provisioning privileges,
not a SQL superuser. The
central database holds server-owner users/sessions, an initially empty company
registry with code, name, database, active status and branding fields, and the
installed server module catalog. Company users, sessions, the singleton `business_owner` record, the shared
Customer/Prospect schema and the tenant module registry will live inside each
company database after the Empresa core module provisions it. The tenant module
registry seeds `clientes` as an enabled base module.

The installer applies Core authentication migrations and the central registry
migrations to the central database. The database records scripts in
`schema_migrations` by module, version and checksum. Database ownership remains
with the provisioning administrator. The runtime account receives the
table permissions needed for authentication and the company registry, plus
server module catalog permissions and membership in the `server_owner` role so
it can provision tenants only after the backend validates a `server_owner`
session token. `DB_URL`
points at the central database; root-only `TENANT_DATABASES_JSON` starts as
`[]`. The Empresa screen can create a company database, apply tenant
migrations, seed its `business_owner` and register the tenant in the running
server so company login works without restart. From the same screen,
`server_owner` can enable or disable modules that are installed on the server;
`business_owner` cannot change module availability. `clientes` stays enabled as
the base customer module. Optional module providers compiled into the server
are available to install, but only installed modules can be enabled per company.
The Empresa screen also includes a basic tenant user flow: `business_owner`
can create application users and assign permissions declared by active modules
inside its own tenant database.

Since Core 0.11.0 the owner can also create **server administrators** from the
app (control migration `V015__create_server_admins.sql`, applied by `install.sh`
and `update.sh`, which also grant the service user `server_admins*` and
`INSERT` of new rows in `application_users`). Administrators sign in to the
server like the owner (no company code) with an application password (16+
characters, changed at the first sign-in); they get no PostgreSQL role and only
the permissions and companies the owner gives them. See `SERVER_ADMINS.md`.

Since Core 0.12.0 every company has an append-only **activity log** (tenant
migration `V011__create_activity_log.sql`, applied by `update.sh` to every
company and by the server at startup and to new companies): who changed what,
when and where. The service user gets only `SELECT, INSERT` on `activity_log`
and its chain, triggers refuse `UPDATE`, `DELETE` and `TRUNCATE`, and there is
no retention purge. Server sign-ins and failures go to `provisioning_audit_log`.
See "Activity log integrity" in §14 and `ACTIVITY_LOG.md` §9 for what this does
and does not guarantee.

Since Core 0.13.0 (security hardening): optional **two-step verification**
(TOTP, recovery codes and a delayed recovery by email; control migrations
`V017`-`V019` applied by `update.sh`, tenant `V012`-`V015` applied to every
company by `update.sh` and by the server at startup), a **storage quota** per
company with a free-disk floor (STORAGE_QUOTA.md), pinned and optionally signed
Core updates and packages (SIGNING.md), and the PostgreSQL role layout of §5
(the service's role owns nothing; a separate provisioning login migrates). The
service role only reads and creates `tenant_data_keys`, and never deletes rows
of `application_user_2fa_email_recovery`.

Before reporting success, the installer tests `/auth/login` with the
`server_owner`, revokes the test session, and rejects passwordless
authentication for that account. This verifies login and the backend is wired
for server-owner company/module administration and business-owner self-service;
end-to-end verification against a new Ubuntu installation is pending. Module files
are not yet downloaded selectively; the installer still clones the repository.

Publish the matching backend, installer and migration files to the selected
Git branch before running on Ubuntu. This script is for a fresh installation;
it refuses existing application files/accounts instead of resetting owners.
An interrupted installation after provisioning requires diagnosis, not a
blind rerun. Do not apply these initial scripts manually to an existing server
with the previous central-user layout; data migration is a separate task.

For company login, POST JSON to `/auth/login` with `username`, `password` and
`companyCode` after a company has been created from the app. For server
administration omit `companyCode` or set it to null. Unknown/inactive companies are rejected.
Responses include `scope`, `companyCode`, `role` and the session token.
The client login view consumes this response; administrative actions are not yet exposed.

### Instalador interactivo para Ubuntu

**Implementado; pendiente de prueba integral en un servidor Ubuntu limpio.**
El archivo `scripts/ubuntu/install.sh` prepara una primera instalación en
Ubuntu 24.04 o 26.04 con systemd, sin PostgreSQL ni Java preinstalados.
Instala PostgreSQL y OpenJDK 21 desde APT, descarga este repositorio,
compila el backend con su Gradle Wrapper y crea el servicio `bms`.
Ktor se incorpora como dependencia de la aplicación; no se instala como
un paquete independiente del sistema.

No necesitas instalar Nginx ni las herramientas del backend previamente.
El script consulta el estado de los paquetes de Ubuntu para Java 21,
PostgreSQL y su cliente, Nginx, Git, curl, certificados CA, Python 3,
herramientas DNS y OpenSSL. Muestra cuáles están disponibles y cuáles
faltan, instala estos últimos y verifica el resultado. Comprueba Certbot
por separado después de validar el dominio, reutilizando una instalación
existente incluso si no proviene de APT. No actualiza deliberadamente
todos los paquetes ya instalados: el mantenimiento del sistema sigue
siendo una operación independiente.

El punto de partida es Ubuntu Server 24.04/26.04 con sus herramientas
básicas (Bash, APT/dpkg, systemd y utilidades de cuentas), acceso root
mediante sudo, terminal interactiva e Internet. El comando de descarga
de abajo instala curl si la instalación limpia todavía no lo tiene.

Primero publica los cambios del instalador y del backend en la rama que
vas a utilizar. Desde una terminal SSH en Ubuntu:

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl
curl --fail --show-error --location \
  https://raw.githubusercontent.com/alexcalbri/BMS/master/scripts/ubuntu/install.sh \
  --output install-bms.sh
less install-bms.sh
sudo bash install-bms.sh
```

`master` es la rama estable: recibe `develop` sin los tests mediante
`bash scripts/release-to-master.sh`. Para probar lo último, cambia `master`
por `develop` en la URL y escribe `develop` cuando pregunte el instalador.
Antes de limpiar o modificar el servidor, el instalador verifica que la rama
elegida tenga todos los archivos que necesita; si no, se detiene sin tocar nada. El repositorio debe poder
clonarse públicamente por HTTPS; este instalador no gestiona credenciales
para repositorios privados. Necesita Internet para APT, GitHub, Gradle y
las dependencias, además de memoria suficiente para compilar (el proyecto
configura un heap Gradle de hasta 4 GiB y otro Kotlin de hasta 3 GiB).
Como requisito mínimo, el servidor debe contar con al menos 2 GiB de RAM
y 10 GiB libres en disco duro antes de iniciar la instalación.

Preguntas del instalador:

- Rama o etiqueta que se instalará (predeterminada: `master`).
- Dominio público del backend, sin protocolo ni rutas (por ejemplo `api.tuempresa.com`).
- Correo para registrar la cuenta de Let’s Encrypt.
- Base PostgreSQL nueva (predeterminada: `bms`).
- Usuario PostgreSQL nuevo (predeterminado: `bms_app`).
- Contraseña de PostgreSQL y confirmación, sin mostrarla en pantalla.
- Confirmación del resumen antes de instalar paquetes.

La contraseña admite al menos 16 caracteres ASCII imprimibles. Se guarda
en `/etc/bms/server.env`, propiedad de root y con permisos `600`.
La aplicación corre con el usuario Linux `bms`, sin privilegios
administrativos. El instalador configura Nginx con HTTPS y un certificado
de Let’s Encrypt. Reutiliza Certbot si ya está instalado; si falta,
lo instala mediante APT después de validar el dominio. Instala los servicios en el Ubuntu donde se
ejecuta. Configura automáticamente Ktor en `127.0.0.1:8080` y su conexión
JDBC al PostgreSQL local en `127.0.0.1:5432`, usando la base, usuario y
contraseña introducidos. No solicita IP ni puerto. Si el puerto HTTP
8080 no está disponible, se detiene con un mensaje explicativo.
Para probar desde tu PC, utiliza `https://TU_DOMINIO/`. Nginx publica el
puerto 443 y redirige HTTP a HTTPS; Ktor no escucha conexiones externas.
Antes de ejecutar, crea los registros DNS A y, si utilizas IPv6, AAAA,
apuntando al servidor y permite entrada TCP 80/443 en el firewall y en
el proveedor de hosting/router. El script no modifica DNS ni firewall.
Consulta DNS público a través de `1.1.1.1` y comprueba cada dirección
publicada con un token HTTP temporal servido por este Nginx. Un AAAA
incorrecto también detiene la instalación. Esta comprobación desde el
servidor requiere NAT loopback si está detrás de NAT; la validación final
de accesibilidad desde Internet la realiza Let’s Encrypt al emitir el
certificado. Usa DNS directo para esta instalación, sin un proxy CDN.
La confirmación inicial incluye aceptar los términos enlazados de
Let’s Encrypt. El certificado no se solicita hasta confirmar.

El servidor verifica `SELECT 1` durante el arranque y luego habilita su
ruta `/`. El instalador comprueba PostgreSQL, la respuesta HTTPS local
con validación del certificado y una renovación de prueba con Certbot.
La comprobación HTTPS es obligatoria. Si solo falla la simulación de
renovación, el instalador muestra una advertencia y marca esa prueba como
pendiente; no declara la renovación verificada ni deshace la instalación.
The installer creates central authentication/control-plane tables. Tenant user
and permission tables are created when each company is provisioned.

```bash
sudo systemctl status bms --no-pager
sudo journalctl -u bms -f
sudoedit /etc/bms/server.env
sudo systemctl restart bms
curl --fail http://127.0.0.1:8080/
```

Cambiar `DB_PASSWORD` en el archivo no cambia la contraseña en PostgreSQL:
ambas deben coincidir. Las variables del archivo son leídas por systemd;
no ejecutes ese archivo como un script Bash.

Este instalador es **solo para una primera instalación**. Rechaza
directorios, servicio, cuenta Linux, base o usuario PostgreSQL existentes;
no sobrescribe sus datos ni cambia sus contraseñas. Si falla después de
crear recursos, los conserva para diagnóstico: no es una actualización
automática ni realiza rollback. Revisa el error y el estado antes de
reintentar; no borres una base de datos para forzar su ejecución.

Excepción de recuperación: si solo quedó el sitio Nginx temporal con
`return 503`, puedes ejecutar de nuevo con el mismo dominio. El instalador
exige que el archivo coincida exactamente con su plantilla, que el enlace
habilitado apunte a ese archivo y que todavía no existan los directorios,
cuenta ni servicio de la aplicación. Repite la comprobación HTTP y continúa
con Certbot. No adopta sitios HTTPS ni configuraciones personalizadas.

Para modificarlo, conserva sus bloques: validación y preguntas, paquetes,
compilación, PostgreSQL, configuración del servicio y comprobaciones.
Los valores predeterminados están junto a cada llamada a `ask`.
La dirección y el puerto HTTP están en las constantes `APP_HOST` y
`APP_PORT`; cualquier cambio posterior también debe reflejarse en el
proxy de Nginx. Mantén Ktor en loopback para que el acceso público use HTTPS.
Las rutas de instalación están declaradas al inicio y también aparecen
en el archivo de servicio: deben mantenerse sincronizadas.

La compilación del backend sin SDK Android utiliza:

```bash
./gradlew -PserverOnly=true :server:tasks --all
./gradlew -PserverOnly=true :server:test :server:installDist
```

`settings.gradle.kts` omite las aplicaciones y selecciona
`core/build-server.gradle.kts`, que compila las mismas fuentes comunes
de Core para JVM. El build normal conserva todos los targets.

BMS supports a self-hosted model in which an organization runs the
application on infrastructure it controls.

The intended deployment contains:

``` text
Users
  |
Reverse Proxy / TLS
  |
BMS Ktor Server
  |
  +-------------------+
  |                   |
Platform/Control DB   Tenant PostgreSQL DB(s)
                      |
                      +-- Core structures
                      +-- Installed module structures
```

KMP clients communicate with the Ktor server. They do not connect
directly to PostgreSQL.

------------------------------------------------------------------------

## 2. Prerequisites

Verify the exact requirements in the current repository before
installation.

Expected categories include:

-   Git
-   JDK compatible with the repository Gradle toolchain
-   PostgreSQL
-   Gradle Wrapper from the repository
-   Docker/Compose if the deployment uses containers
-   reverse proxy with TLS for internet-facing production deployments

Do not rely on versions copied from old documentation. Check:

``` bash
./gradlew --version
```

and inspect:

``` text
gradle/libs.versions.toml
gradle.properties
settings.gradle.kts
```

------------------------------------------------------------------------

## 3. Clone

``` bash
git clone <BMS_REPOSITORY_URL>
cd BMS
```

For a production deployment, use a tagged release when releases are
available instead of an arbitrary development commit.

------------------------------------------------------------------------

## 4. Inspect the Build

Before configuring production infrastructure:

``` bash
git status
git log --oneline -5
./gradlew tasks
```

Run the server tests available in the current repository.

Historically the server task has been:

``` bash
./gradlew :server:test
```

Verify that this task still exists before relying on it.

------------------------------------------------------------------------

## 5. PostgreSQL

BMS's approved architecture uses PostgreSQL.

The intended tenant model is:

-   one tenant/company = one PostgreSQL database;
-   every tenant DB receives Core migrations;
-   only installed modules receive their module migrations.

Example only:

``` text
bms_company_a
├── Core
├── Sales-like module
└── Hotel-like module

bms_company_b
├── Core
└── Access-like module
```

Exact database names and schemas are deployment choices.

### Production recommendations

-   use dedicated database roles;
-   use strong generated passwords;
-   restrict network access;
-   enable backups;
-   use TLS where database traffic crosses untrusted networks;
-   do not expose PostgreSQL directly to public clients.

### Roles de PostgreSQL (Ubuntu installer and updater)

The application never runs with the rights that create or own company databases. Three roles, created by
`install.sh` and set up on existing servers by `update.sh` (`scripts/ubuntu/db-roles.sh`):

| Role | Example | What it is |
| --- | --- | --- |
| runtime (`DB_USER`) | `bms_app` | The pooled, long-lived connections of the server. A plain login role: member of no role, `NOCREATEDB NOCREATEROLE`, owns nothing. It reaches company data only through grants: `SELECT, INSERT, UPDATE, DELETE` on the ordinary tables, `SELECT` on `schema_migrations`, `USAGE, SELECT` on sequences; `SELECT, INSERT` only on `activity_log` and `activity_log_chain` and `USAGE` only on `activity_log_id_seq`; in the control database its explicit grants, `SELECT, INSERT` only on `provisioning_audit_log`. |
| server owner | `server_owner` | The server owner's login (their password). `CREATEDB CREATEROLE`; owns every company database and everything in it. |
| provisioning (`DB_PROVISIONING_USER`) | `bms_provisioner` | Used only to create, migrate, duplicate and delete companies (and by `update.sh` for company migrations), on short-lived connections of its own, never from the pool. `NOINHERIT NOCREATEDB NOCREATEROLE`: it holds nothing by itself. It is a member of the server owner's role and of the runtime role with `SET` and without `INHERIT` (PostgreSQL 16+ grant options), so it acts as the owner only after `SET ROLE` (to create a database, a business owner role, or run migrations) and as the runtime role only to write the company activity row of a change made from the server. Its password is random, in `/etc/bms/server.env` (`DB_PROVISIONING_PASSWORD`). |

Why this design: company creation from the app needs `CREATE DATABASE` and `CREATE ROLE` at runtime, but the
connections that serve requests must not carry them. A separate login that the pool never uses keeps a SQL injection
or a hijacked pooled connection to the runtime role's grants: it cannot `SET ROLE` to the owner (not a member), drop or
disable the activity-log triggers, alter tables, or create databases and roles. `NOINHERIT` plus explicit `SET ROLE`
only in the provisioning code (`CompanyProvisioningService.adminConnection`) limits even the provisioning connection to
what each step needs. What it does not change: the provisioning password lives in the same service environment as the
runtime one, so code execution inside the server process (not just SQL) can still reach the owner's rights; and the
superuser, root and the server owner's own login keep full power. Without `DB_PROVISIONING_USER` (development, tests)
provisioning connects as the runtime role, as before.

Moving an existing server (`update.sh`, idempotent, while the service is stopped): the provisioning role is created; the
runtime role gets explicitly every DML privilege it had (until then partly through its membership in the owner's
role); objects in `public` of each company database owned by the superuser (an older `update.sh` created them as
`postgres`) or by the runtime role are given to the database owner — all objects of the activity log together, so its
integrity check (owners of its tables, sequence and functions must match) keeps passing; objects of extensions stay;
the activity-log grants are left exactly `SELECT, INSERT` / `USAGE`; then the runtime role leaves the owner's role.
Everything is decided before anything changes: every database registered in `companies` must exist with a name
`bms_<code>` (not the control database, not a template) and belong to a server owner's role. If a registered database
exists but breaks a rule, the move stops **before any change** and lists them (fix the owner with `ALTER DATABASE ...
OWNER TO <server owner role>` or the row, and run `update.sh` again); a registered database that does not exist is only
reported. After the move it proves, as the runtime role, that it can connect to and read `application_users` in the
control database and in every company database (and the activity log in each company), and that the provisioning
login can connect and `SET ROLE` to each database's owner (a real connection with its password); any failure rolls the
update back.

What a failed attempt leaves, and how the next one handles it. The rollback restores `server.env` and the runtime
role's membership in the server owner roles (with `INHERIT` and `SET`, as `install.sh` made it), using only the role
names step 0 validated and recorded before anything changed (nothing is queried during a rollback; every role is tried,
and the exact `GRANT` is printed for any that cannot be restored). It does not remove the provisioning role, the owners
already moved or the grants already given. So the next `update.sh` may find: the role `bms_provisioner` without its keys
in `server.env`: if it has no ownership, no privilege except `CONNECT` on databases, memberships only in the server owner
roles and the runtime role, no `SUPERUSER`/`CREATEDB`/`CREATEROLE`/`REPLICATION`/`BYPASSRLS`, no role settings, connection
limit or expiry, and nobody is a member of it, it is **adopted** (a new random password goes to `server.env`); anything
else stops the update at step 0, before changing anything, naming what is wrong. And company databases moved in part
(some objects already the owner's, some grants missing, some databases already done): the move only selects what is
still owned by a superuser or the runtime role and what is missing, so it finishes the work and a second run changes
nothing. Step 0 prints which databases are half moved and whether the provisioning role carries the marks of an attempt.

**Rehearsal (phase 1).** `sudo bash update.sh --rehearse --ref <branch, tag or commit>` (or the last block of
`preflight.sh`, which runs it) executes the PostgreSQL work of the real update on the live cluster and leaves nothing
behind. For the control database and for each company database it opens one transaction, runs the same SQL as the real
run (the role layout move, the membership revocation, the checks, then the migrations and grant blocks that would be
applied, as the owner role through `SET SESSION AUTHORIZATION` to the provisioning role and `SET ROLE` to the owner), runs
the access checks again over the result, prints what would change (roles, memberships, owners and ACLs, and how many
objects the migrations create) and **rolls back**. Roles are cluster-wide but `CREATE ROLE`, `ALTER ROLE`, `GRANT`,
`REVOKE` and `ALTER ... OWNER` are transactional, so each database's transaction creates (or alters) the provisioning role
itself and the rollback removes it: no throwaway role is needed. Before the first transaction and after the last one the
whole cluster is snapshotted (roles with a hash of their password verifier, memberships with their options, database
owners and ACLs, and in every connectable database the owners and ACLs of relations, columns, routines, types, schemas
and the default ACLs); if the two differ the rehearsal prints `FAIL ensayo sin cambios` with the difference. The service
keeps running, so every session has short limits (environment overrides): `lock_timeout` 500 ms
(`BMS_REHEARSE_LOCK_TIMEOUT`), `statement_timeout` 10 s (`BMS_REHEARSE_STATEMENT_TIMEOUT`), `idle_in_transaction_session_timeout`
5 s (`BMS_REHEARSE_IDLE_TIMEOUT`), `client_connection_check_interval` 1 s on 14+ (`BMS_REHEARSE_CONNECTION_CHECK`) and
`transaction_timeout` 15 s on 17+ (`BMS_REHEARSE_TRANSACTION_TIMEOUT`). A statement or transaction that hits its limit is a
`FAIL` naming the database (raise the limit for a heavy migration), never a pass. A database whose locks cannot be taken
after 3 tries is reported `WARN ... no ensayado por bloqueo`, and then the whole rehearsal is **incomplete**: exit code 2,
`preflight.sh` reports `FAIL ensayo de update.sh — incompleto` and never a PASS; repeat it at a quiet moment. Each database
reports how long its transaction (and so its locks) lasted, and the last line says what was not rehearsed.
The transaction cannot be ended early by the SQL it runs: it starts with a deferred foreign key violated on purpose (any
`COMMIT`, however written, fails on it), lines with transaction control or psql meta-commands other than `\set`, `\if`, `\else`,
`\endif` and `\echo` are refused, and at the end deferred constraints are checked (`SET CONSTRAINTS ALL IMMEDIATE`) as the
real `COMMIT` would.
Before any transaction it checks what the real update needs at its first login, which comes **after** the role move: the
`pg_hba.conf` rule for the provisioning role at the DB_URL host (first address libpq tries: `localhost` may be `::1`) and a
real TCP login, with the same host, port, passfile and `sslmode`, of a throwaway `bms_rh_*` role with a random password (the
real password is unknown); the role is dropped at once and by traps on any exit, Ctrl-C included, and the cluster snapshots
are taken without it. The real update repeats the login (with the new password) right after setting it and before the move
starts; if it fails, only that role's password and its `CONNECT` grants changed, and `server.env` is restored.
Limits: `ALTER ... OWNER` takes `ACCESS EXCLUSIVE` locks on the tables that would change owner, held until the rollback
(well under a second for the catalog work, and only on objects of an old layout; a company already moved takes none);
passwords, `pg_hba`/real TCP login, the new release's own startup and anything outside PostgreSQL are not rehearsed;
a statement that cannot run in a transaction (`CREATE INDEX CONCURRENTLY`, `VACUUM`) would also fail the real run (every
migration already runs inside `BEGIN`/`COMMIT`); somebody else changing permissions while it runs shows up as a
difference (the output says so). The only thing it writes outside PostgreSQL is the root mirror of the repository.
The superuser only runs catalog queries, `GRANT`/`REVOKE` and `ALTER ... OWNER` there, with `search_path` pinned to
`pg_catalog`, never code the company database defines. If the update is rolled back, the runtime role becomes a member
of the owner's role again (the previous release needs it); the next update moves it again.

Not changed on purpose: `REVOKE CONNECT ON DATABASE postgres, template1 FROM PUBLIC` is **not** applied (psql, pg_dump
and other tools expect to connect there). Company databases and the control database do revoke `CONNECT` from
`PUBLIC`; the roles that can connect to `postgres` gain nothing there.

------------------------------------------------------------------------

## 6. Control Plane

Managed/multi-tenant deployments are intended to have platform metadata
logically separate from tenant business data.

The exact control-plane provisioning command/schema is **not yet defined
by this guide**.

Do not manually invent control-plane tables from architectural examples.

Once provisioning tooling exists, document the exact tested command
here.

------------------------------------------------------------------------

## 7. Environment Configuration

Secrets must be supplied server-side.

Likely configuration categories include:

``` text
server host/port
database host/port
platform/control database credentials
tenant database credentials or resolver configuration
JWT/authentication secrets
integration credentials
logging configuration
module configuration
```

The exact environment-variable names must come from the current server
implementation.

### Important

Do not copy historical/example variable names into production unless
they are verified in current code.

To find current configuration, inspect the server for environment/config
access, for example:

``` bash
grep -R "System.getenv\|environment.config\|application.conf" -n server
```

Document verified variables in a table here as they are implemented:

| Variable | Required | Purpose / default |
| --- | --- | --- |
| `DB_PASSWORD` | Yes | PostgreSQL password; no fallback |
| `DB_URL` | No | JDBC URL; `jdbc:postgresql://localhost:5432/bms` |
| `DB_USER` | No | PostgreSQL role; `bms` (installer sets `bms_app`) |
| `DB_POOL_SIZE` | No | Hikari pool maximum; `10` |
| `HOST` | No | HTTP bind address; `0.0.0.0` (installer sets `127.0.0.1` behind Nginx) |
| `PORT` | No | HTTP port; `8080` |
| `PROVISIONING_DB_URL` | No | PostgreSQL administration database URL used before `SET ROLE server_owner`; defaults to `DB_URL`. |
| `TENANT_JDBC_URL_PREFIX` | No | Prefix used for newly created tenant JDBC URLs; defaults to the central DB URL up to the last `/`. |
| `MIGRATIONS_ROOT` | No | Repository/source root containing `database/...` migrations; defaults to the server working directory. |
| `COMPANY_BACKUPS_ROOT` | No | Server-side directory for company ZIP files created from the Empresa business-owner screen; defaults to `build/company-backups`. |
| `COMPANY_FILES_ROOT` | No | Server-side directory for the files modules store per company (CRM attachments...), as `{company}/{module}/{yyyy}/{mm}/{id}`; the databases keep only the references. Installer default: `/opt/bms/company-files`; defaults to `build/company-files`. Back it up together with PostgreSQL: the app's company ZIPs do not include it. Deleting a company deletes its folder. |
| `MODULE_CATALOG_URL` | No | URL of the remote module repository directory. Installer default: `https://github.com/alexcalbri/BMS/tree/master/modules`. GitHub tree URLs and Contents API URLs are accepted. Empty means no remote modules are shown for new installs. |
| `MODULE_GITHUB_TOKEN` | No | GitHub token for the module catalog and module downloads. Without it GitHub allows 60 API requests per hour per IP, which listing the catalog can use up; with it, 5000. Use a fine-grained token with read-only access to public repositories (or to the private catalog repository). It is sent only to GitHub over HTTPS and is set only here, never in the app. Restart `bms` after changing it. |
| `MODULE_SIGNATURE_ENFORCED` | No | Not set (default): `true` when a package key is installed in `/usr/local/lib/bms/package-keys/` (SIGNING.md), else `false`. `false`: optional modules may be installed by the server owner without an Ed25519 signature, with a warning in the log (dev/staging); a server administrator always gets the enforced gate. `true` (recommended in production): an unsigned module is rejected unless the server owner authorizes its tree hash with `POST /server/modules/{id}/authorize-unsigned`, same as extensions. Restart `bms` after changing it. |
| `MODULE_PACKAGES_ROOT` | No | Server-side directory where downloaded module packages are stored; defaults to `build/server-modules`. |
| `VERSION_CATALOG_URL` | No | URL of the remote JSON version catalog used by `/version`. Official URL: `https://raw.githubusercontent.com/alexcalbri/BMS/master/version.json`. |
| `DB_PROVISIONING_USER`, `DB_PROVISIONING_PASSWORD` | Yes (installer and `update.sh` write them) | Login used only to create, migrate, duplicate and delete companies, on short-lived connections that `SET ROLE` to the server owner's role (section 5, Roles de PostgreSQL). Must differ from `DB_USER`; the password is at least 16 printable ASCII characters without `:` or `\`. Without it (development, tests) provisioning connects as `DB_USER`. |
| `MAX_REQUEST_BODY_BYTES` | No | Core's default limit of a request body, behind Nginx's 2 MB; `4194304` (4 MB). Upload routes keep their own limits (module files: quota and per-module maximum; backup import 512 MB; extension ZIP 50 MB) and `POST /modules/crm/ui/import` allows 25 MB (`RequestBodyLimits.kt`); `scripts/ubuntu/nginx-site.sh` gives each of them at least as much. |
| `COMPANY_FILES_MIN_FREE_BYTES` | No | Free-disk floor of `COMPANY_FILES_ROOT`: uploads are refused (507 `STORAGE_DISK_FULL`) under it; `2147483648` (2 GiB). STORAGE_QUOTA.md. |
| `COMPANY_FILES_MAX_CONCURRENT_UPLOADS` | No | Uploads in progress at the same time per company (429 `STORAGE_BUSY` above it); `4`. The storage quota per company is set by the server owner in the app (default 100 GiB). |
| `BMS_PUBLIC_BASE_URL` | No (installer and `update.sh` write it) | Public origin (`https://bms.example.com`, no path) used for links in emails (cancel link of the delayed two-step recovery, TWO_FACTOR.md §7) and for the OAuth metadata of Agentes. Written from the domain of the BMS Nginx site when absent; without it the request's validated `Host` is used. Change it if the public name changes. |
| `BMS_BUILD_USER` | No | Read by `update.sh` only: the user that compiles the release (default `bms-build`, separate from the service user). `bms` goes back to compiling as the service user, only as a way out (section 15). |
| `BMS_SYSCALL_FILTER` | No | Read by `update.sh` only: `true` adds `SystemCallFilter=@system-service` to `bms.service` (opt-in; section 16). |
| `CORE_UPDATE_REQUIRE_APPROVED_COMMIT` | No | `true` unless set to `false`: `POST /server/core-update` must carry the commit the owner reviewed (`targetCommit`, app 0.11.0 and later); an older app gets 400 `CORE_COMMIT_INVALID`. `false` lets the server pin the commit it reads itself (SIGNING.md §1). |
| `BMS_MEMORY_MAX` | No | Read by `update.sh` only: `MemoryMax` of `bms.service` (for example `3G` or `80%`; `none` for no cap). Default: 80% on hosts with at least 8 GiB of RAM, no cap on smaller ones (`units.sh`). |
| `BMS_NGINX_REPLACE_SITE` | No | Environment of `update.sh` (not `server.env`): `yes` replaces a BMS Nginx site that has lines no installer wrote (move them to `/etc/nginx/bms.d/` first). |

| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_FROM`, `SMTP_SECURITY` | No | Server-wide SMTP for password reset codes ("¿Olvidaste tu contraseña?" in the login; without it users cannot recover their password by email) and for modules that send email (hotspot codes). `SMTP_SECURITY` is `starttls` (default), `ssl` or `none`. Empty `SMTP_HOST` disables it; companies can still use their own SMTP. |
| `PUBLIC_SCHEME`, `ACME_EMAIL` | No | Read only by the root helper `bms-domains-sync`, which publishes module domains (hotspot portals) in Nginx with Let's Encrypt (`https`) or plain HTTP (`http`). |
The root `version.json` file exposes:

```json
{
  "latestCoreVersion": "0.2.1",
  "latestAppVersion": "0.2.1"
}
```

Module versions are not global. Each installed module is compared
independently against the module version published in that module's
`module.json`.

The official remote module source is
`https://github.com/alexcalbri/BMS/tree/master/modules`. It is a repository
directory containing one folder per module. Each folder becomes installable
only when it contains a valid `module.json`. This lets server owners switch to
non-official repositories without editing a central registry file.

Example repository layout:

```text
modules/
├── example/
│   └── module.json
└── hotel/
    └── module.json
```

Example module manifest:

```json
{
  "id": "example",
  "displayName": "Example",
  "description": "Gestion comercial",
  "version": "0.1.0",
  "packageUrl": "https://example.com/modules/example.zip",
  "packageSha256": "64_hex_characters_when_available",
  "views": [],
  "permissions": []
}
```

Only module folders currently present with a valid manifest are shown as
available for new installation. Modules already installed on a server remain
registered locally in `server_modules` even if the remote repository later
removes them.

`EngineMain` loads `application.conf` and starts
`com.ideasdeveloper.bms.server.app.ApplicationKt.module`. `DatabaseFactory`
connects and checks PostgreSQL at startup, and closes the pool on
application stop. These variables must also be provided when running the
backend locally.

Never commit `.env` files containing real secrets.

Provide `.env.example` with placeholders when the project adopts
environment-based configuration.

------------------------------------------------------------------------

## 8. Database Migrations

Target architecture:

``` text
Core migrations
      +
Installed module migrations
      =
Tenant database schema
```

The exact migration tool/commands must be documented after they are
implemented and tested.

A future release procedure should support:

1.  backup;
2.  verify target version;
3.  run Core migrations;
4.  run tenant Core migrations for company databases, including shared
    Customer/Prospect and tenant module registry migrations;
5.  run migrations only for installed optional modules;
6.  verify migration status;
7.  start/upgrade server;
8.  health check;
9.  rollback/recovery procedure if needed.

Never tell operators to run unverified SQL copied from architectural
examples.

------------------------------------------------------------------------

## 9. Module Installation

The intended lifecycle is:

``` text
Available → Entitled (when applicable) → Installed → Enabled
```

For self-managed open-source deployments, commercial entitlement may not
apply.

Installing a module should eventually:

1.  validate dependencies;
2.  apply the module's migrations to the tenant DB;
3.  register installed version/state;
4.  enable server functionality;
5.  expose client/UI functionality as appropriate.

Exact CLI/API/UI installation steps must be added here when implemented.

### Installing several packages (Core 0.9.2)

Modules and extension packages are compiled on the server and loaded by restarting it. Since Core
0.9.2 the owner can ask for several at once:

-   the server restarts **once**, when nothing else is being built, not after each package;
-   what is pending is kept in `pending-installs.json` next to the module packages
    (`MODULE_PACKAGES_ROOT`) and starts again after any restart, so nothing asked for is lost;
-   **an extension is not installed until its module is fully installed** (loaded after the
    restart): asked for while the module is still installing, it shows "En espera" and is built
    after that restart; asked for without the module, it is refused with `MODULE_NOT_INSTALLED`;
-   a package that fails to build does not stop the others, and one that keeps taking the server
    down while building is dropped after three tries. A ZIP or URL install is not resumed.

### Languages (Core 0.10.0)

The server answers in Spanish or English (`I18N.md`). `update.sh` applies the new migrations before
the new runtime starts: core `V006` (users' `language`, central and every company), control `V014`
(`companies.default_language`, default `es`) and tenant `V010` (`text_translations`, the cache of
translated stage names and notes); the server also brings companies up to date at startup. Nothing
changes for a company until a user or the company picks English.

Disabling a module should preserve data by default.

Uninstalling may be destructive and must require explicit administrative
intent.

------------------------------------------------------------------------

## 10. External Integrations

Integration credentials remain server-side.

Example: a Hotel module may use a Cloudbeds adapter implementing a
provider-neutral reservation capability.

Do not put provider secrets in:

-   KMP source;
-   browser JavaScript;
-   mobile app resources;
-   Git;
-   screenshots/logs.

For every implemented provider, document:

-   required credentials;
-   callback/webhook requirements;
-   redirect URLs if applicable;
-   required network access;
-   synchronization behavior;
-   retry behavior;
-   provider-specific setup.

------------------------------------------------------------------------

## 11. Running the Server

Historically the repository has used:

``` bash
./gradlew :server:run
```

Verify this against the current repository.

Before calling a deployment production-ready, confirm:

-   server starts successfully;
-   database connectivity works;
-   migrations are current;
-   authentication works;
-   tenant isolation works;
-   enabled/disabled module enforcement works;
-   health endpoint works if implemented;
-   logs contain no secrets.

------------------------------------------------------------------------

## 12. Docker

Containerized self-hosting is an intended deployment option.

Do not publish a fictional `docker run` command before the repository
contains a tested image/Dockerfile.

When Docker support is implemented, this section should include:

-   image name/tag;
-   required environment variables;
-   ports;
-   volumes;
-   network configuration;
-   database connectivity;
-   healthcheck;
-   upgrade steps;
-   backup requirements.

A `compose.yaml` is recommended for a reproducible self-hosted stack
once the server, database and provisioning workflow are stable.

------------------------------------------------------------------------

## 13. Reverse Proxy and TLS

The Ubuntu installer can run in two installation modes. The default mode refuses
to overwrite an existing BMS installation. The clean mode must be confirmed
by typing `LIMPIAR`; it removes the BMS systemd service, Linux user, app
files, root-only config directory, Nginx site and selected PostgreSQL roles or
databases before reinstalling. It can also remove tenant databases named
`bms_*` when explicitly requested. When tenant databases are removed, the
installer reads their `application_users.postgres_role` values before dropping
the databases, lists the detected tenant PostgreSQL users and requires typing
`BORRAR_USUARIOS_POSTGRES` before deleting those roles with `DROP ROLE`.
PostgreSQL will block any role deletion that still has dependencies.

The installer asks for the public mode: `https` or `http`. `https` uses Let's
Encrypt. `http` skips certificate issuance and serves the web app over plain
HTTP while proxying `/auth/`, `/companies`, `/server`, `/modules`, `/notifications`, `/version` and `/.well-known/oauth-` to Ktor. Choosing
`http` prints a credential/token warning and requires typing `HTTP` to confirm.
Use HTTP only as a temporary recovery/testing mode; enable HTTPS before
production use.

The Ubuntu installer normally creates `/etc/nginx/sites-available/bms`
and enables it without replacing other sites. It rejects an existing
BMS site or an existing Nginx configuration mentioning the supplied
domain. It preserves other Certbot certificates and uses the certificate
name `bms-DOMAIN` under `/etc/letsencrypt/live/`.

Nginx terminates TLS 1.2/1.3 on port 443, serves the browser application,
and proxies `/auth/`, `/companies`, `/server`, `/modules` and `/.well-known/oauth-` to loopback port 8080.
Port 80 redirects to HTTPS except for the ACME webroot challenge path,
which must stay accessible for renewals. The backend is not publicly
proxied until a certificate is obtained and the database check succeeds.
If issuance fails, the temporary site only serves challenges and a 503
response; no plaintext backend fallback is enabled.

`bms-certbot.timer` checks renewal twice daily with randomized delay.
The certificate's deploy hook reloads Nginx after renewal. Existing
Certbot installation methods and renewal timers are left in place.

```bash
sudo systemctl status bms-certbot.timer
sudo journalctl -u bms-certbot.service
sudo nginx -t
```

End-to-end issuance and renewal against a real domain remain untested in
this workspace. Failed installs preserve their files and certificates;
inspect the failing step before recovery instead of rerunning blindly.

### Nginx configuration of the Ubuntu scripts

`install.sh` writes and `update.sh` rewrites (on every update, keeping every `server_name`, the scheme and the
certificate of the live site) the configuration generated by `scripts/ubuntu/nginx-site.sh`; the old site is kept in
`/var/lib/bms/nginx-backups/` and restored automatically when `nginx -t` fails or any served name no longer serves the
app. Local additions go in `/etc/nginx/bms.d/*.conf` (included in the server block), never in the site file. A live site
with lines no BMS installer or updater ever wrote (a hand-made location, include, rewrite...) is **not** replaced:
`update.sh` prints those lines and the diff with the proposed site (kept in the backup directory), only adds the limits
file, and carries on; move the local lines to `/etc/nginx/bms.d/` and run it with `BMS_NGINX_REPLACE_SITE=yes` to
apply the new site.

- **Streamed answers of the AI assistant (Core 0.14.0, GLOBAL_AGENT.md):** `location /companies/me/assistant/` turns
  response buffering off (`proxy_buffering off`, `proxy_cache off`; the backend also sends `X-Accel-Buffering: no`) and
  waits up to 900 s between bytes (`proxy_read_timeout`, `proxy_send_timeout`), so the Server-Sent Events of a turn
  reach the app frame by frame. A turn lasts at most 10 minutes and the backend sends a ping every 15 s. The CSP needs
  no change (same-origin request). The assistant calls `api.anthropic.com` over HTTPS from the server: a firewall that
  limits outgoing traffic must allow it once the server owner turns the assistant on.
- **Request bodies:** 2 MB everywhere (`client_max_body_size 2m`), so nobody can fill memory or disk before signing in.
  Larger only on the authenticated upload routes found in the code: `POST /modules/<module>/ui/files` (module
  attachments of CRM, Compras, Tareas, Propietarios, Mantenimiento and any module with that route) without an Nginx
  limit and with `proxy_request_buffering off` (streamed to the module, no temporary copy); `POST /modules/crm/ui/import`
  25 MB (CRM import of pasted CSV text; the Core allows the same on that path); `POST /companies/me/backups/import`
  520 MB, streamed (512 MB ZIP in the Core); `POST /server/extensions/upload` 60 MB, streamed (50 MB ZIP). Each is at
  least the Core's limit for that route (a test compares `nginx-site.sh` with `RequestBodyLimits.kt`). Module domains of `bms-domains-sync` (hotspot portals) take 2 MB.
- **Limits per client address** (`/etc/nginx/conf.d/bms-limits.conf`): 50 requests/s (burst 400), `/auth/` also 10/s
  (burst 40), 100 connections; over them Nginx answers 429. Generous for an office behind one address; the backend
  keeps its own sign-in limits.
- **Unknown host names** (`/etc/nginx/sites-available/bms-default`): `return 444` on port 80 and
  `ssl_reject_handshake on` on 443 (no certificate is shown for a name this server does not serve). Only on ports where
  no other site is already `default_server`; Ubuntu's stock `/etc/nginx/sites-enabled/default` takes port 80 — if
  you do not use it, `sudo rm /etc/nginx/sites-enabled/default` and run `update.sh` again.
- **Headers:** `X-Content-Type-Options`, `X-Frame-Options DENY` (`SAMEORIGIN` under `/modules`, whose web screens the
  app shows in a same-origin iframe), `Referrer-Policy`, HSTS and `Alt-Svc` only over HTTPS (never over plain HTTP),
  `X-Forwarded-Host` in every proxied location, and for the web app the Content-Security-Policy
  `default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data:
  blob: https:; font-src 'self' data:; connect-src 'self' https: wss:; media-src 'self' blob: data:; worker-src 'self'
  blob:; frame-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'self'; form-action 'self'` (plus
  `http:`/`ws:` in HTTP mode). Compose for Web needs `'wasm-unsafe-eval'` for its Skia WebAssembly; images and requests
  may go to other HTTPS hosts (company logos, a server URL set in the app). Checked with the production bundle of the CI
  in headless Chrome (`scripts/ubuntu/tests/nginx_test.sh` with `BMS_WEB_DIST`): the app starts, loads its WebAssembly
  and calls the backend with no CSP violation, and the same check without `'wasm-unsafe-eval'` reports one.
- `http2 on` with Nginx 1.25.1 or later, `listen ... http2` before; QUIC/HTTP/3 listeners only when Nginx has the module.

------------------------------------------------------------------------

## 14. Backups

A production self-host must back up:

1.  platform/control metadata, if used;
2.  every tenant PostgreSQL database;
3.  deployment configuration/secrets through an appropriate secure
    mechanism;
4.  any persistent files not stored in PostgreSQL.

Module installation/uninstallation and upgrades should be preceded by an
appropriate backup when they can modify persistent data.

A restore procedure is as important as a backup procedure. Test
restores.

### Daily backup of the Ubuntu install (`bms-backup`)

`install.sh` and `update.sh` install `/usr/local/sbin/bms-backup` and `bms-backup.timer` (daily, around 02:37 with
up to 30 minutes of random delay). As root it writes `/var/lib/bms/db-backups/<UTC time>/` (directory and files
root-only): `db/<database>.dump` (`pg_dump -Fc`) for the control database and every `bms_*` database, `db/roles.sql`
(`pg_dumpall --roles-only`, with password verifiers), `company-files.tar.gz`, `etc-bms-secret.tar.gz` (all of
`/etc/bms`, **with `BMS_MASTER_KEY` and the database passwords**), `etc-bms.tar.gz` and `server.env.redactado`
(without secrets), `nginx.tar.gz`, and `SHA256SUMS`. It keeps 7 copies and skips a run (with an error in
`/var/log/bms-backup.log`) when the disk would keep less than 2 GiB free, because a full disk stops PostgreSQL.

```bash
sudo systemctl start bms-backup.service        # one now
sudo journalctl -u bms-backup.service; sudo cat /var/log/bms-backup.log
sudo ls /var/lib/bms/db-backups/
```

Optional settings in `/etc/bms/backup.conf` (root, mode 600, `KEY=value`, read but never executed):
`BACKUP_KEEP=7`; an off-box copy with `BACKUP_RCLONE_REMOTE=remote:bms` (configure the remote as root with
`rclone config`) or `BACKUP_RSYNC_TARGET=user@host:/dir` with `BACKUP_RSYNC_SSH_KEY=/root/.ssh/bms-backup` (a key
used only for this, the host key already in root's `known_hosts`). The off-box copy leaves out
`etc-bms-secret.tar.gz` and `db/roles.sql` (password verifiers of the PostgreSQL roles) unless
`BACKUP_OFFSITE_INCLUDE_SECRETS=true`: a copy that carries the data and the key that decrypts its secrets together
protects nothing. The daily job waits (up to an hour) while an install or update holds the maintenance lock.

**Master key escrow.** `BMS_MASTER_KEY` (in `/etc/bms/server.env`) wraps every company's data key; without it the
stored secrets (integration tokens, SMTP passwords...) cannot be read, and it is never regenerated. Right after
installing, copy it to a place outside the server and outside the backups: a password manager with two people able to
open it, or a sealed printed copy. Check it once a year against `server.env`. Restoring a server needs the database
dumps, `company-files` and that key; whoever holds the key and a backup can read every stored secret.

Restore of one company database from a daily or pre-update copy (`--create` recreates it with its owner and its
`CONNECT` grants; the roles must exist — `db/roles.sql` of the same copy has them all):

```bash
sudo systemctl stop bms
sudo -u postgres dropdb --force bms_<code>
sudo -u postgres pg_restore --create --exit-on-error -d postgres /var/lib/bms/db-backups/<time>/db/bms_<code>.dump
sudo systemctl start bms
```

A pre-update copy is from before the role move of section 5: after restoring it, fix what made the update fail and run
`update.sh` again (it moves the roles and migrates again).

### Empresa module ZIPs

**Implemented for the first production core:** a signed-in `business_owner`
can use the Empresa screen to create, list, download, upload and delete ZIP
files stored under `COMPANY_BACKUPS_ROOT`. The current ZIP contains company
profile metadata. Uploading a ZIP stores it for safekeeping after validating it
is readable; it does not restore or overwrite PostgreSQL data. Neither the ZIP
nor PostgreSQL holds module files (attachments): back up `COMPANY_FILES_ROOT`
too, at the same time as the databases.

Full PostgreSQL backup and restore are operational responsibilities for the
self-hosted server. For production, configure external `pg_dump`/`pg_restore`
or managed PostgreSQL backups for the central database and every tenant
database. The app deliberately does not expose a destructive database restore
button in the first core release.

### Activity log integrity

What is prevented: nobody can edit or delete activity rows through the
application (no route, tool, screen or job does it), and the triggers refuse
`UPDATE`, `DELETE` and `TRUNCATE` even to the table owner. What is detected:
every committed row is sealed into a hash chain (`activity_log_chain`); the
server recomputes it every hour, compares it with the anchors it keeps in
`provisioning_audit_log` (owned by `postgres`, the service user may only read
and append), and reports any altered or deleted row covered by an anchor,
structural tampering (disabled, extra or replaced triggers and functions,
rules, row security, new columns or types...), a missing table and unsealed
rows, as `activity.integrity_failed` plus a notification to the server owners.
What is **not** guaranteed: anyone with direct database access as the service
user or the server owner's role, root, the PostgreSQL superuser or the hosting
provider can alter the data; changes made after the last hourly anchor can be
hidden by recomputing the chain; a compromised server can stop the verification
(visible as anchors that stop appearing). The log is not proof against the
server operator.

Run the verification at any time as the server owner:

``` bash
curl -fsS -H "Authorization: Bearer <owner session token>" \
  "https://<domain>/server/activity/verify?company=<code>"   # without company: every company
```

`ok`, `problems` and `firstBadId` say what was found. Back up and restore
company databases as usual (§14): the chain travels with the database, and a
restore of a copy older than the last anchor is reported as missing rows (it
is: they are lost), which is the record of that restore.

## 15. Upgrade Procedure

Until automated tooling exists, treat upgrades conservatively.

Target release process:

``` text
Backup
  ↓
Read release notes
  ↓
Stop/coordinate writes if required
  ↓
Update code/image
  ↓
Core migrations
  ↓
Installed-module migrations
  ↓
Start server
  ↓
Health checks
  ↓
Functional smoke tests
```

Never assume every tenant has the same installed modules.

Migration orchestration must account for each tenant's installed module
set and versions.

### Ubuntu updater

`scripts/ubuntu/update.sh` updates an existing installation created by the Ubuntu installer. Order:

1. checks `/opt/bms/source`, `/opt/bms/app`, `/etc/bms/server.env` and `bms.service`; asks for the branch, tag or
   commit and syncs the root-owned mirror `/var/lib/bms/mirror` to it (with the signature check of `mirror.sh` when it
   has one, SIGNING.md); every script and SQL file root runs comes from that mirror;
2. **checks everything it can without changing anything**, before the build: the tools it needs from the backup on
   (`openssl`, `pg_dump`, `psql`, `curl`...), the values `server.env` must hold afterwards (the provisioning login, its
   password — generated here when missing — and `BMS_MASTER_KEY`, validated now and written only after the stop), the
   shape of `DB_URL`, the migration files of the release, the company list, the role layout rules and the free space for
   the database copy. A failure here stops the update with the server untouched and running (`No se cambio nada`);
3. **builds and tests**: `git archive` of the mirror commit into `/opt/bms/build-tree`, owned by the build user
   `bms-build` (not the service user `bms`: build code — Gradle plugins, dependencies, Node — cannot read the running
   server's environment or files), `:server:test :server:installDist` and the web app, with a clean environment, no
   local build cache and no user-level Gradle/Maven/npm settings (removed before each build). Every process of the
   build user is killed before root reads anything the build produced; root copies build output only through a
   root-owned archive it checks first (no links, no special files, no path outside). If this fails nothing on the
   server has changed;
4. **backs up**: `pg_dump` of the control and every company database (`/var/lib/bms/backups/<time>/db`, root-only;
   refused when the disk would keep less than 2 GiB free), the current app, web app, `server.env` and the unit drop-ins;
5. stops `bms.service`, completes `server.env` (new non-secret keys and the `DB_PROVISIONING_*` planned in step 2; copies
   `server.env.update-backup.*`, mode 600, last three kept), creates or updates the provisioning role, moves the
   PostgreSQL roles to the layout of section 5 (Roles de PostgreSQL), applies the control migrations as the superuser
   (every `database/control/migrations/V*.sql` in order) and the company migrations **as the database owner's role
   through the provisioning login** (`SET ROLE`, `search_path=public`, only on validated company databases — never as
   the superuser), installs the root helpers (`bms-domains-sync`, `bms-core-update`, `bms-backup`) and the systemd
   hardening drop-in;
6. replaces `/opt/bms/app` (with the release's company migrations in `/opt/bms/app/migrations`, root-owned:
   `MIGRATIONS_ROOT` points there, so the service user can no longer change SQL that later runs as the owner's role)
   and `/opt/bms/web`, moves `/opt/bms/source` to the same commit (as `bms`, a reference copy only), starts the service
   and checks it: HTTP answers, the log since the start says `PostgreSQL connection verified` and has no PostgreSQL
   permission or authentication error, and the runtime role and the provisioning login still reach every database.
   Then it writes the deployed commit to `/var/lib/bms/deployed-commit`;
7. **automatic rollback** if anything in steps 5–6 fails or the check fails: previous binaries and web app,
   `server.env`, unit drop-ins, root helpers and their units (`bms-core-update`, `bms-domains-sync`, `bms-backup`,
   `mirror.sh`), the runtime role's membership in the owner's role (the previous release needs it), restart, check. The databases are **not** restored automatically: the pre-update dumps stay and the commands are
   printed (see "Restore" in section 14). Migrations are transactional per file, so a failed one leaves its database as
   it was;
8. rewrites the Nginx configuration (section 13), restoring the previous one if it fails, and prunes old backups
   (last 5 updates, database dumps of the last 3), logs and Nginx copies. Nothing after the deploy can mark the update
   failed.

`BMS_BUILD_USER=bms` in `server.env` goes back to building as the service user, only as a way out if the separate
build user ever fails on a server. `/opt/bms/build-home` starts empty (nothing is taken from the service user's home,
where a planted Gradle or npm setting, init script, cache entry or JDK would run in every build; the build-only caches
there are deleted), so the first update downloads Gradle and the dependencies again. `BMS_UPDATE_SKIP_DB_DUMP=true` in the environment of a manual run skips the
pre-update dump (only when the disk cannot hold it and a daily backup is recent). `--harden-host` runs
`harden-host.sh` at the end (section 16).

Download `update.sh` and `mirror.sh` into a root-owned directory and run it from there. Before the first update with
this layout, run the read-only `preflight.sh` (with `db-roles.sh` and `nginx-site.sh` next to it; the rehearsal block needs `update.sh` and `mirror.sh` there too): it checks versions
(Nginx, one PostgreSQL cluster and a matching `pg_dump`, Java 21), memory, `fs.protected_*`, `server.env` keys (never
printing values), the server owner roles, every company database (name, owner, owners of its objects, extra schemas,
the owner's `CREATE` on `public`), the runtime role, `pg_hba` for 127.0.0.1, pending update requests, free disk, the
Nginx site (names and local edits) and the files; it prints PASS/WARN/INFO/FAIL per check and exits 1 on any FAIL.

```bash
sudo mkdir -p /root/bms-update && cd /root/bms-update
for f in update.sh mirror.sh preflight.sh db-roles.sh nginx-site.sh; do
  sudo curl -fsSL -o "$f" "https://raw.githubusercontent.com/alexcalbri/BMS/master/scripts/ubuntu/$f"
done
sudo bash /root/bms-update/preflight.sh
sudo bash /root/bms-update/update.sh
```

Phase 1 (nothing changes) is `preflight.sh`, which ends by running `update.sh --rehearse` against `CORE_UPDATE_REF`
(`master` by default; `preflight.sh --ref <ref>` or `BMS_PREFLIGHT_REF` change it). Run it alone with
`sudo bash /root/bms-update/update.sh --rehearse --ref master`. Every line is PASS, WARN, INFO or FAIL; do not start
phase 2 (`update.sh`) until there is no FAIL and every WARN is understood. A `WARN ... no ensayado por bloqueo` means the
service was using those tables: repeat the rehearsal, or accept that database is untested.

**Locale.** Every script under `scripts/ubuntu` runs with `LC_ALL=C.UTF-8` (plain `C` when that locale is missing),
whatever the server's `LANG` is, and each exact ASCII check (role names, commits, passwords, domains) also sets
`LC_ALL=C` for that one test (`bms_ascii_match`): with `LANG=en_US.UTF-8` a bracket range such as `[!-~]` does not mean
printable ASCII (an update was refused that way with "DB_PROVISIONING_PASSWORD debe tener al menos 16 caracteres ASCII
imprimibles"), and `[a-z]` lets accented letters through. The build keeps `LANG=C.UTF-8`. `sudo bash update.sh
--self-test` runs the validators in the current locale and changes nothing; `preflight.sh` shows the system locale
and runs that self-test with it.

If `update.sh` (or `mirror.sh`) is in a path that is not entirely root-owned (every directory up to `/`, no
group/other write, no symlinks), for example `/opt/bms/source/scripts/ubuntu/update.sh`, it refuses to run. It also
refuses a target reference older than itself (one without `scripts/ubuntu/db-roles.sh` and the other libraries): use
the `update.sh` of the same reference.

**After the first update with this layout**, check: `GET /server/activity/verify` reports every company `ok`; creating
a test company from the app works (then delete it); `sudo -u postgres psql -c "\du"` shows the runtime role
(`bms_app`) with no `Member of` and `bms_provisioner` with `No inheritance`.

The updater runs Gradle with one worker, disables configuration cache for the deployment build and sets Kotlin
compiler execution through `-Pkotlin.compiler.execution.strategy=in-process`. If the host has less than 2 GiB
available memory and less than 1 GiB swap, it creates a temporary 4 GiB swap file at `/var/lib/bms/update.swap` for
the build and removes it afterward.

### Webapp compilada por el CI (opcional)

En servidores de 4 GB, webpack puede quedarse sin memoria. El workflow
`.github/workflows/build.yml` compila la webapp de produccion en GitHub y, en
cada push a `develop`, `main` o `master`, sube `web-<sha>.tar.gz` y su
`.sha256` al release rodante `web-dist` (se conservan los 10 mas recientes). Para
que `update.sh` los use, agrega a `/etc/bms/server.env`:

```
WEB_DIST_FROM_CI=true
```

`update.sh` descarga el paquete del mismo commit que va a instalar
(`https://github.com/alexcalbri/BMS/releases/download/web-dist/web-<sha>.tar.gz`,
cambiable con `WEB_DIST_BASE_URL`) como el usuario de compilacion. Si existe la llave publica de root
`/usr/local/lib/bms/web_dist_public.pem`, la firma Ed25519 `web-<sha>.tar.gz.sha256.sig` es obligatoria: root la
verifica (`openssl pkeyutl -verify -rawin`) sobre su propia copia del `.sha256` y compara el hash del paquete con esa
copia verificada; sin la llave se usa solo el checksum, con un aviso. Despues verifica que existan
`index.html` y `webApp.js`, y lo despliega igual que una compilacion local. No
necesita token mientras el repositorio sea publico. Si el CI aun no publico ese
commit, o algo falla, compila en el servidor como siempre. Sin
`WEB_DIST_FROM_CI=true` el comportamiento no cambia.

### Actualizar desde la app

El server owner puede actualizar el Core desde la app (dashboard, "Actualizar
servidor"). El backend solo registra la solicitud en `core_update_requests`
(`GET`/`POST /server/core-update`); la ejecuta un ayudante root,
`bms-core-update`, que un timer de systemd revisa cada minuto. El
ayudante usa su propio clon espejo de `origin` en `/var/lib/bms/mirror`
(rama `CORE_UPDATE_REF` de `server.env`, por defecto `master`), verifica que la
version del commit coincida con la pedida y que sea **posterior** a la instalada (el commit de
`/var/lib/bms/deployed-commit` debe ser antecesor del pedido y la version pedida mayor; desde la app nunca se vuelve
atras, ni siquiera con una fila falsificada). Sin `deployed-commit` (un servidor que siempre se actualizo por SSH) la
app no puede actualizar hasta la primera actualizacion por SSH, que lo escribe. Toma el lock de mantenimiento antes de
sincronizar el espejo y corre `update.sh --non-interactive`
con los scripts del espejo, nunca los de `/opt/bms/source`. El instalador
lo deja instalado; en un servidor anterior hay que correr `update.sh` una vez
por SSH. Si la solicitud sigue `pending` mas de 3 minutos, la app avisa de que
falta el ayudante. La app queda sin servicio solo durante el reinicio.

------------------------------------------------------------------------

## 16. Security Checklist

Before exposing a self-hosted installation:

-   [ ] no default passwords;
-   [ ] no secrets committed to Git;
-   [ ] PostgreSQL not publicly exposed unnecessarily;
-   [ ] HTTPS enabled;
-   [ ] tenant isolation tested;
-   [ ] server-side authorization tested;
-   [ ] disabled modules blocked server-side;
-   [ ] backups configured;
-   [ ] restore tested;
-   [ ] integration secrets stored server-side;
-   [ ] logs reviewed for secret leakage;
-   [ ] dependency/security updates reviewed.
-   [ ] `BMS_MASTER_KEY` escrowed outside the server and outside the backups (section 14);
-   [ ] SSH: root password login off, key-only login.

### Hardening the Ubuntu scripts apply

- `bms.service` drop-in `/etc/systemd/system/bms.service.d/hardening.conf` (`scripts/ubuntu/units.sh`):
  `ProtectProc=invisible`, empty `CapabilityBoundingSet`, `PrivateDevices`, `ProtectKernelTunables/Modules/Logs`,
  `ProtectControlGroups`, `ProtectClock`, `ProtectHostname`, `RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6
  AF_NETLINK`, `RestrictNamespaces`, `RestrictRealtime`, `RestrictSUIDSGID`, `LockPersonality`, `RemoveIPC`,
  `SystemCallArchitectures=native`, `TasksMax=4096`, on top of the unit's `NoNewPrivileges`,
  `ProtectSystem=strict`, `ProtectHome`, `PrivateTmp`. `MemoryMax`: `BMS_MEMORY_MAX` in `server.env` (for example
  `80%` or `3G`, `none` for no cap); without it, `80%` with at least 8 GiB of RAM and no cap below (on a small machine
  the cap would kill the server, with its module compiler, before the kernel would). `SystemCallFilter=@system-service` (with `EPERM` instead of
  killing the JVM) is opt-in: `BMS_SYSCALL_FILTER=true` in `server.env`, then `update.sh`; if the service then fails,
  `journalctl -u bms` shows it and removing the line plus another `update.sh` (or the automatic rollback) undoes it.
- Root helpers (`bms-domains`, `bms-domains-renew`, `bms-certbot`, `bms-backup`): `NoNewPrivileges`, `PrivateTmp`,
  `PrivateDevices`, `ProtectKernel*`, `ProtectControlGroups`, `ProtectClock`, `ProtectHostname`, `RestrictNamespaces`,
  `RestrictRealtime`, `RestrictSUIDSGID`, `LockPersonality`. No file-system or capability limits: they run `nginx -t`,
  `certbot`, `runuser` and `psql` as root. `bms-core-update` (it runs the whole `update.sh`) is not sandboxed.
- The build runs as `bms-build`, separate from the service user `bms` (section 15). `server.env` stays root-only
  (`600`), read by systemd; the server keeps reading its settings from the environment.
- `/var/log/bms-domains.log` is mode 640. Certificate orders of module domains are throttled: after a failure each new
  attempt for that domain waits twice as long (5 minutes up to a day) and at most 5 new orders per hour in total; a
  domain waiting stays `pending` and is retried after the wait.
- Optional, off by default: `sudo bash /var/lib/bms/mirror/scripts/ubuntu/harden-host.sh` (or `install.sh` /
  `update.sh --harden-host`) installs ufw (only SSH — every port sshd listens on and the port of the current session,
  allowed before ufw is enabled — 80/tcp, 443/tcp, 443/udp), fail2ban (sshd jail) and unattended-upgrades, asking
  before each.

------------------------------------------------------------------------

## 17. Troubleshooting

Si la instalación anterior terminó en `certbot renew --dry-run` con
`rateLimited` / `Service busy; retry later`, no ejecutes de nuevo todo el
instalador. El certificado real puede estar emitido y el backend activo.
Comprueba `systemctl status bms --no-pager` y
`curl --fail https://TU_DOMINIO/`. Revisa el temporizador con
`systemctl status bms-certbot.timer --no-pager`. La simulación usa
normalmente el entorno staging de Let’s Encrypt; no sustituye el certificado
real. Revisa el log de Certbot y respeta cualquier indicación `Retry-After`.
Después repite solamente `certbot renew --cert-name bms-TU_DOMINIO --dry-run`.

Si una versión anterior termina con `No se pudo completar la instalación
de dnsutils` después de que APT seleccione `bind9-dnsutils`, es un falso
negativo del instalador: comprobaba el alias en vez del paquete instalado.
La versión corregida utiliza `bind9-dnsutils` y comprueba que exista `dig`.
En este fallo concreto todavía no se ha creado la configuración de
BMS; descarga el script corregido y ejecútalo de nuevo. Los paquetes
ya instalados se detectan y se conservan, sin borrar bases ni certificados.

Si aparece `ERR: bad trap`, el intérprete utilizado puede no admitir el
trap `ERR` de Bash. Ejecuta el archivo descargado explícitamente con:

```bash
sudo bash install-bms.sh
```

La versión actual detecta `sh install.sh` y se relanza con Bash antes de
usar opciones o traps específicos de Bash. Si se recibe por una tubería
en otro intérprete, solicita descargarlo y ejecutarlo con Bash. Evita
`source install.sh`: el instalador debe correr como un proceso separado.

When deployment fails, collect:

``` bash
git rev-parse HEAD
git status
java -version
./gradlew --version
```

Then inspect:

-   server logs;
-   PostgreSQL connectivity;
-   migration state;
-   environment configuration;
-   installed module state;
-   reverse proxy logs.

Do not post secrets publicly when requesting support.

------------------------------------------------------------------------

### Supply chain: Gradle wrapper, daemon JVM, CI and dependency verification

**Gradle wrapper.** `gradle/wrapper/gradle-wrapper.properties` pins `distributionSha256Sum` for the exact
distribution in `distributionUrl`. When bumping Gradle, change both together; the checksum is the "Binary-only
(-bin) ZIP" value published at <https://gradle.org/release-checksums/>.

**Daemon JVM (no automatic JDK download).** `gradle/gradle-daemon-jvm.properties` only asks for JDK 21: no
`toolchainVendor` and no `toolchainUrl.*` lines, so any JDK 21 works (the Ubuntu OpenJDK 21 from apt on the
server, Liberica in CI) and Gradle has no URL to download one from. If no JDK 21 is installed, Gradle fails with a
message instead of fetching a binary from a third-party service. Do not regenerate the file with
`./gradlew updateDaemonJvm` (it would add the vendor and the download URLs again).

**Dependency verification (not enabled).** There is no `gradle/verification-metadata.xml` yet. To adopt it:

1. GitHub, Actions, "Verification metadata", Run workflow (on the branch to adopt). It resolves the full,
   `serverOnly` and `webOnly` profiles and uploads `verification-metadata.xml` as an artifact.
2. Download it, review the diff (new artifacts, `origin` comments, anything unexpected) and commit it to
   `gradle/verification-metadata.xml` on a branch.
3. Check that the three builds still pass in CI (a missing or changed checksum fails the build on purpose).
4. Regenerate with the same workflow whenever dependencies change; Dependabot PRs will fail until you do.

Only sha256 checksums are generated (no PGP keyring is trusted automatically).

**CI workflows.** Every action is pinned by commit SHA (tag in a comment; Dependabot updates both), jobs declare
the minimum `permissions:` and checkouts do not persist the token. Only a push to `master` publishes the
`web-dist` release (the compile job has read-only access and no secrets; a separate job signs and uploads).

**Signed web distribution.** The release assets are `web-<sha>.tar.gz`, `web-<sha>.tar.gz.sha256` and
`web-<sha>.tar.gz.sha256.sig`: an Ed25519 signature (OpenSSL, raw) over the `.sha256` file, which names the
package with the commit sha and its hash. One-time setup by the repository owner:

1. On your own computer: `openssl genpkey -algorithm ed25519 -out web-dist-signing.pem` and
   `openssl pkey -in web-dist-signing.pem -pubout -out web_dist_public.pem`.
2. GitHub, Settings, Secrets and variables, Actions: create the secret `WEB_DIST_SIGNING_KEY` with the full
   contents of `web-dist-signing.pem`, then delete the local private key (or keep it offline). Optionally put the
   secret in an Environment restricted to the `master` branch.
3. On the server, as root: `install -d -m 755 -o root -g root /usr/local/lib/bms` and
   `install -m 644 -o root -g root web_dist_public.pem /usr/local/lib/bms/web_dist_public.pem`.

While that file does not exist, the updater behaves as before (checksum only). Once it exists, `update.sh`
refuses any CI package without a valid signature and compiles locally instead. The public key is not secret; the
private key lives only in GitHub.

## 18. Documentation Maintenance

This guide must change with the code.

Whenever a change affects:

-   prerequisites;
-   environment variables;
-   ports;
-   database setup;
-   tenant provisioning;
-   migration commands;
-   Docker;
-   module installation;
-   integration setup;
-   backup/upgrade procedures;

update this document in the same change.

Only document commands that are implemented and tested.

Use labels such as **Implemented**, **Planned**, or **Example** when
necessary to prevent architectural intentions from being mistaken for
working deployment instructions.
