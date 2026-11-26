# Server administrators — specification

Branch `feature/server-admins`. Core 0.11.0, app 0.9.0. All new user-visible text goes through `tr`
(I18N.md) with catalog entries; app strings in `Strings_en.kt`.

## 1. What it is

Today the only server-level user is the **server owner** (row in `server_owners`, a real PostgreSQL
role, all power). This adds **server administrators**: server-level users created by the owner, each
with an explicit set of server permissions and an optional list of companies they are limited to.

Principles (security requirements, not suggestions):

1. **Deny by default.** An administrator can do only what a granted permission names. Unknown or new
   routes are owner-only until a permission is added for them on purpose.
2. **Only the owner manages administrators.** No permission lets an administrator create, edit,
   grant, scope, reset or delete an administrator (including themself), nor touch `server_owners`.
3. **Checked live.** Permissions, company scope and active state are read from the database on every
   request (and on every use of a company session opened through the switcher). Removing a
   permission or deactivating an administrator takes effect on their next request; deactivating also
   revokes their sessions and the company sessions they opened.
4. **No database power of their own.** Administrators have **no PostgreSQL role**. Privileged work
   keeps running as today (service connection + `SET ROLE` to the owner's role), so object ownership
   does not change. The administrator is the *actor*; the owner's role is only the *executor*.
5. **Everything is attributed.** Every audited action records the administrator as actor
   (`actor_role` = their login, plus `actor_user_id` and `actor_kind`), never the owner whose role
   executed it. Actions that were not audited before and are now delegable get an audit row.
6. **Never widen through reuse.** No existing "is server owner" check may start returning true for an
   administrator. Each gate is converted explicitly to "owner, or administrator with permission X
   (and company in scope)". The four raw-SQL owner checks stay owner-only unless listed in §4.

## 2. Data (control database, new migration `V015__create_server_admins.sql`)

- `server_admins(user_id PK → application_users ON DELETE CASCADE, is_active, all_companies BOOLEAN
  NOT NULL DEFAULT FALSE, password_hash, password_updated_at, must_change_password BOOLEAN,
  created_by UUID, created_at, updated_at)`. A user cannot be in both `server_owners` and
  `server_admins` (enforce in code and with a trigger or check on insert).
- `server_admin_permissions(user_id → server_admins ON DELETE CASCADE, permission TEXT, PRIMARY KEY
  (user_id, permission))`; `permission` must be one of §3 (validated in code; unknown stored values
  are ignored when read).
- `server_admin_companies(user_id → server_admins ON DELETE CASCADE, company_code, PRIMARY KEY (…))`:
  companies the administrator may act on when `all_companies` is false. Empty + not all = none.
- `provisioning_audit_log` gains `actor_user_id UUID NULL` and `actor_kind TEXT NULL`
  (`server_owner`, `server_admin`, `business_owner`, `system`).
- The `application_users` row of an administrator uses its login in `postgres_role` (the column is
  only a unique login name for non-owners; no PostgreSQL role is created). Login names must not
  collide with existing PostgreSQL roles or users.
- Passwords: same hashing as tenant application credentials; minimum 16 characters like the owner.
  The owner sets an initial password (shown once / typed by the owner) with `must_change_password`;
  the administrator must change it on first sign-in before anything else works. Recovery by email
  code works like other users and revokes sessions.
- Registered in `install.sh`, `update.sh` (with grants to the service user) and the startup catch-up,
  like the other control migrations.

## 3. Permissions

| Permission | Allows | Company scope applies |
|---|---|---|
| `server.companies.view` | List companies (code, name, status, modules) | yes (sees only scoped) |
| `server.companies.create` | Create a company; duplicate a company in scope | duplicate source: yes |
| `server.companies.edit` | Edit company profile; view and edit its business owner's name, user and email | yes |
| `server.companies.status` | Activate / deactivate a company | yes |
| `server.companies.modules` | Enable / disable modules for a company | yes |
| `server.companies.extensions` | Manage extensions inside a company (install, settings, enable, uninstall) | yes |
| `server.companies.owner_password` | Reset a business owner's password. **High risk: equals taking over the company.** | yes |
| `server.companies.enter` | Enter a company from the switcher and work there as its business owner. **High risk: full access to that company's data.** | yes |
| `server.modules.view` | See installed and available modules and their status | – |
| `server.modules.install` | Install / update / inspect modules from the configured catalog | – |
| `server.modules.remove` | Remove a module from the server | – |
| `server.modules.authorize_unsigned` | Authorize an unsigned module package. **High risk: runs code on the server.** | – |
| `server.extensions.install` | Browse the catalog; install / update / remove / inspect extension packages | – |
| `server.extensions.authorize_unsigned` | Authorize an unsigned extension package. **High risk: runs code on the server.** | – |
| `server.core.update` | Request a Core update and see its status | – |
| `server.logs.view` | See recent server errors | – |
| `server.audit.view` | See the audit log (new screen and route, §5) | yes (rows of scoped companies + own rows; server-wide rows only with all companies) |
| `server.settings` | Rename the server | – |
| `server.workspace` | Create the server workspace, turn its modules on/off, manage its profile, mail settings and staff users | – |

A company created by a scoped administrator is added to that administrator's scope.

**Owner only (no permission exists):** managing administrators; deleting a company; linking /
unlinking companies and owner groups (`owner_group` is trusted elsewhere as "same person");
changing the module catalog URL; uploading or URL-installing packages; anything about
`server_owners`; the server MCP tools and connecting AI agents at server level; using workspace
modules with implicit full permissions. `permissions.serverOwner` / `UserModuleAccess.serverOwner`
stay false for administrators everywhere.

The high-risk permissions are marked as such in the contract (`risk: "high"`) so the app can warn.

## 4. Server design

- New `ServerActor` (userId, login, kind owner|admin, permissions, allCompanies, companies,
  executorRole = the owner's PostgreSQL role used for `SET ROLE`). One resolver from the session
  token; `actor.require(permission)` and `actor.requireCompany(permission, code)` throw a
  `FORBIDDEN` with code `SERVER_PERMISSION_REQUIRED` (owner passes everything). A request from an
  administrator with `must_change_password` gets `PASSWORD_CHANGE_REQUIRED` on everything except the
  password change and logout.
- Convert each gate in `CompanyRoutes`, `ExtensionRoutes`/`ExtensionActors`,
  `ExtensionPackageRoutes`, `CoreUpdateService` callers and the workspace paths of
  `CompanySelfService` to the permission in §3. Services keep receiving the executor role for
  `SET ROLE` and additionally the actor for audit — do not derive the audit actor from the role.
  Background paths keep using `firstServerOwnerRole()` with `actor_kind = system`.
- Login (central scope): owner path unchanged (PostgreSQL role login). If the user is an active
  administrator, verify against `server_admins.password_hash` in constant time; the response has
  `role = "server_admin"`, `serverPermissions` (list), `serverCompanies` (null = all) and
  `mustChangePassword`. Same rate limits as other logins; identical error for unknown user, wrong
  password and inactive administrator.
- Switcher: `server_owner_company_sessions` keeps its name and now also holds administrators' rows
  (the column already references `application_users`). Opening requires `server.companies.enter` +
  scope; audit `server_admin.entered_company`. Every validation of such a session re-checks that the
  opener is an active owner, or an active administrator who still has the permission and scope;
  otherwise the session is closed. The session it opens is the company's business owner session as
  today (`serverOwnerAccess = true` stays meaning "came from the server", not "is owner").
- Administrator management routes (owner only, all audited): `GET/POST /server/admins`,
  `GET/PUT/DELETE /server/admins/{id}`, `PUT /server/admins/{id}/permissions`,
  `PUT /server/admins/{id}/password`, `PUT /server/admins/{id}/status`;
  `GET /server/admin-permissions` returns the catalog (id, title, description, risk, scoped).
- `GET /server/audit` (owner or `server.audit.view`): newest first, filters by company, action,
  actor and dates, paginated (max 200 per page); `details` returned as stored.
- `GET /server/me` returns the caller's kind, permissions and scope (the app refreshes with it).
- Tests: a matrix test proving, for every server-level route, that (a) an administrator without the
  permission gets 403, (b) with it gets through, (c) out-of-scope company gets 403, (d) owner-only
  routes refuse an administrator holding every permission, (e) a deactivated administrator's session
  and company sessions stop working at once, (f) audit rows carry the administrator. Existing tests
  unchanged.

## 5. App

- Session: handle `role == "server_admin"` with `serverPermissions` / `serverCompanies`; each server
  card and action is shown only when permitted (owner sees everything as today). Forced password
  change screen when `mustChangePassword`.
- Owner only: screen **Administradores del servidor** (list; create with login, name, email and
  initial password; activate/deactivate; reset password; delete) with a permissions editor grouped
  as Empresas / Módulos / Extensiones / Servidor, a visible warning on high-risk permissions, and the
  company scope (all companies or a chosen list).
- **Registro de auditoría** screen (owner or permission) with filters.
- Servers older than Core 0.11.0: none of this is shown (`isCoreAtLeast`).

## 6. Working rules

Tests with `/home/user/tools/bms-test.sh <worktree> ["*Filter*"]`; never commit `build.gradle.kts` or
`gradle/gradle-daemon-jvm.properties`. Commits: author/committer `alexcalbri
<164930758+alexcalbri@users.noreply.github.com>`, Spanish conventional message, no co-author or
session trailers, no push. Never edit an existing migration.
