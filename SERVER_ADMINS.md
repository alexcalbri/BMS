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
- `server_workspace_staff(user_id PK, created_by → application_users, created_by_kind, created_at,
  updated_at)`: workspace staff accounts whose credentials a server user knows (created them or set
  their password or email last). `user_id` is the account in the workspace database (no foreign key; no
  tenant migration was needed).
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
| `server.companies.edit` | Edit company profile; view its business owner and change the owner's display name (user, email and password need `owner_password` too) | yes |
| `server.companies.status` | Activate / deactivate a company | yes |
| `server.companies.modules` | Enable / disable modules for a company | yes |
| `server.companies.extensions` | Manage extensions inside a company (install, settings, enable, uninstall) | yes |
| `server.companies.owner_password` | Reset a business owner's password and change their user or email (password recovery goes to the email). **High risk: equals taking over the company.** | yes |
| `server.companies.enter` | Enter a company from the switcher and work there as its business owner. **High risk: full access to that company's data.** | yes |
| `server.modules.view` | See installed and available modules and their status | – |
| `server.modules.install` | Install / update / inspect modules from the configured catalog (an unsigned package always needs its hash authorized, see §8) | – |
| `server.modules.remove` | Remove a module from the server | – |
| `server.modules.authorize_unsigned` | Authorize an unsigned module package. **High risk: runs code on the server.** | – |
| `server.extensions.install` | Browse the catalog; install / update / remove / inspect extension packages | – |
| `server.extensions.authorize_unsigned` | Authorize an unsigned extension package. **High risk: runs code on the server.** | – |
| `server.core.update` | Request a Core update and see its status | – |
| `server.logs.view` | See recent server errors | – |
| `server.audit.view` | See the audit log (new screen and route, §5) | yes (rows of scoped companies + own rows; server-wide rows only with all companies) |
| `server.settings` | Rename the server | – |
| `server.workspace` | Create the server workspace, turn its modules on/off, manage its profile, mail settings and staff users. **High risk: allows creating workspace staff accounts** (closed when the administrator is deactivated, deleted or loses the permission) | – |

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

## 7. Server implementation (Core 0.11.0)

- Code: `server/.../server/admins/` (`ServerPermission`, `ServerActor`, `ServerAdminStore`,
  `ServerAdminService`, `ServerRoutePolicies`, `ServerAudit`, `ServerAdminRoutes`); contract in
  `core/src/commonMain/.../bms/ServerAdmins.kt` (`ServerPermissionIds`, DTOs).
- **One table decides every server route**: `ServerRoutePolicies.all` maps `"METHOD /path"` to
  `OwnerOnly`, `AnyServerUser` or `Requires(permission, company = None|Path|Header, workspace)`.
  Handlers call `call.serverActor(resolver, message)`, which looks the matched route up by its Ktor
  routing node; an unlisted route is owner only. `ServerRouteMatrixTest` mounts every server router,
  enumerates the routing tree and fails for an unclassified route, then checks (a)–(d) and the
  password-change gate for each entry. `ServerAdminsPostgresTest` (`BMS_SERVER_ADMINS_PG_URL`)
  covers (e) and (f) on real databases.
- Login (central scope): `role` `server_owner` | `server_admin`, `serverPermissions` (all ids for
  the owner), `serverCompanies` (null = all), `mustChangePassword`. Administrators get
  `enabledModules = []`; `workspaceCompanyCode` only with `server.workspace`.
- Routes: `GET /server/me` → `{userId, login, kind, displayName, email, permissions, allCompanies,
  companies, mustChangePassword}` (answers normally while the password must change);
  `PUT /auth/password {currentPassword, newPassword}` → 204 (administrators; keeps the current
  session, revokes the others and the company sessions they opened); owner only:
  `GET /server/admin-permissions` (`[{id, group (translated heading), title, description, risk,
  scoped}]`), `GET/POST /server/admins`, `GET/PUT/DELETE /server/admins/{id}` (PUT `{displayName,
  email, allCompanies?, companies?}`), `PUT …/permissions {permissions, allCompanies?, companies?}`,
  `PUT …/password {password}` (204), `PUT …/status {active}`; `GET /server/audit?company=&action=
  &actor=&from=YYYY-MM-DD&to=YYYY-MM-DD&page=0&pageSize=50` → `{items, page, pageSize, hasMore}`.
  Errors are `{code, message}`; refusals `SERVER_OWNER_REQUIRED`, `SERVER_PERMISSION_REQUIRED`,
  `PASSWORD_CHANGE_REQUIRED` (403), `SERVER_OWNER_UNAVAILABLE` (503, no active owner to execute).
  `GET /server/admin-permissions` marks `server.workspace` as `risk: "high"` too.
- Deleting an administrator keeps the `application_users` row (audit references stay valid, the
  login is never reused) and removes `server_admins`; it can no longer sign in anywhere.
- New audit rows: module install/update request, module removal, catalog URL change, workspace
  profile/mail/users changes, every administrator change, `server_admin.entered_company`,
  `server_workspace.staff_deactivated`, `module.install_dropped`,
  `extension.package.install_dropped`.

## 8. Threat notes

- **Granting themself / editing own row / managing other administrators.** All `/server/admins*`
  and `/server/admin-permissions` are `OwnerOnly` in the table and `ServerAdminService` calls
  `requireOwner()` again; no permission id mentions administrators or owners (tested).
  `server_owners` is read-only for the service user.
- **Owner ↔ administrator overlap.** Triggers on `server_admins` and `server_owners` refuse a user in
  both; the sign-in checks `server_owners` first and an owner never gets an administrator actor.
- **Login collisions.** A new administrator's login must not be a PostgreSQL role, a central user
  (owner or a removed administrator) or a user of any company (fail closed); a trigger refuses a
  login that is a PostgreSQL role. New business owners (create, duplicate, rename) may not take a
  central login either. Administrators never authenticate against PostgreSQL.
- **Timing / enumeration at login.** Every server sign-in path computes one PBKDF2 hash (dummy for
  unknown users and alongside the owner's role check); unknown user, wrong password and inactive
  administrator all return the same `LOGIN_REJECTED` under the existing login rate limit. This also
  removes the old central fallback to `application_user_credentials` (a table the central database
  does not have), which turned wrong server passwords into 503.
- **must_change_password bypass.** The resolver returns `PasswordChangeRequired`; the gate lets it
  through only for `GET /server/me` and `PUT /auth/password`; the switcher, `/auth/profile`,
  `/companies/current/*` and the workspace self-service refuse it.
- **Resetting the owner's or another admin's password.** Owner-only routes; recovery by email code
  needs that user's mailbox and revokes their sessions. `PUT /auth/password` changes only the
  caller's own hash and needs the current password (attempt limiter).
- **Owner-password / business-owner edit out of scope.** Both are scoped by the path company; in
  addition every company that shares the owner's PostgreSQL role (duplicates) and every company of
  its owner group must be in scope (fail closed if a company cannot be read); `GET` is scoped the
  same way, and the refusal does not name the related company. Changing the owner's user, password
  **or email** needs `server.companies.owner_password` too: with only `companies.edit` an
  administrator could otherwise point the email to a mailbox they control and take the company (and
  every company sharing that owner role) over through `/auth/password-reset/*`. Email comparison is
  `EmailChangeRule.isChange` (trim, ASCII case); the audit row `company.owner_updated` records
  `usernameChanged`, `emailChanged` and `passwordChanged`.
- **Duplicate / link tricks.** Linking and unlinking (companies and extension groups) are owner
  only. Duplicating needs the source in scope; the copy joins the creator's scope. Entering a
  company requires its whole owner group in scope, so a duplicate (or a group) reaching an
  out-of-scope company cannot be entered (the business owner inside could switch or let an agent
  act across the group). Extension group operations, retries and the group view are limited to the
  scope.
- **`X-Company-Code` on `/companies/current/*`.** A server session there goes through the same
  policy with the company taken from the header (permission + scope), never "server owner acts
  anywhere".
- **The server workspace is not a company.** `servidor` (any case) is never in an administrator's
  scope, not even with all companies (`ServerActor.mayActOn`, `ServerAdminScope.includes`): the gate
  refuses it for every per-company policy (path or header) unless the policy names
  `server.workspace` explicitly (today only the workspace module switch). The owner is unchanged.
- **Switcher session kept after revocation.** `LoginDatabases.companySessionAllowed` runs on every
  use of a company session (module access, notifications, self-service, `/auth/profile`, extension
  routes, switcher) and re-checks the opener live; when it fails the tenant session is revoked and
  the `server_owner_company_sessions` row is kept, so it keeps failing even if the revoke failed.
  Deactivating, deleting or resetting an administrator also revokes their central and company
  sessions first; if inserting the switcher row fails, the opened company session is revoked. A
  company session opened by an administrator cannot edit the business owner from inside.
- **Switcher row expiring before its session.** The row's `expires_at` is the session lifetime plus
  `SwitcherRowMarginSeconds` (1 h), and it is only a pruning hint: `openerOf` and
  `revokeCompanySessionsOpenedBy` do not filter by expiry, and rows are deleted only a day after
  `expires_at`. Logout and leaving a company no longer delete the row (with another company's
  `X-Company-Code`, logout could otherwise untrack a live session). A session minted by the business
  owner switcher (`switchCompany`) from a tracked session inherits the tracking: the opener is
  re-checked for both companies (scope and owner-group rule) and the new session gets its own row.
- **Unsigned modules.** `MODULE_SIGNATURE_ENFORCED=false` (the default) only relaxes the gate for the
  owner and background work. When an administrator installs or updates a module the build always
  uses the enforced gate (`ModulePackageBuilder.build(…, requireAuthorization = true)`): an unsigned
  package needs the hash-bound authorization of `POST /server/modules/{id}/authorize-unsigned`
  (`server.modules.authorize_unsigned`); without it they cannot run unsigned code.
- **Queued installs.** `PackageInstallQueue` entries keep the login of who asked (modules too now).
  A resumed install (after a restart, or an extension released when its module is ready, which
  `GET /server/extensions/{id}/status` can trigger) runs with that person's CURRENT rights
  (`ServerAdminStore.queuedInstallActor`: active owner, or active administrator without a pending
  password change who still holds the install permission), never the caller's; otherwise it is
  dropped and audited (`module.install_dropped`, `extension.package.install_dropped`, actor
  `system`). A resumed module install by an administrator again gets the enforced gate. Entries
  without a login (older ones) resume as the system, as before.
- **Workspace staff.** `server.workspace` is high risk: it can create staff accounts with any module
  permission. Accounts an administrator created, or gave a password or a new email (password
  recovery goes there), are recorded in
  `server_workspace_staff`; deactivating or deleting that administrator, or removing their
  `server.workspace`, deactivates those accounts and revokes their sessions (audited as
  `server_workspace.staff_deactivated` by the owner). Reactivating the administrator does not bring
  them back. Every workspace change (profile, mail, user create/update/permissions) is audited as
  the actor.
- **Audit integrity.** Details are built with kotlinx (`auditDetails`, `buildJsonObject`), never by
  hand; `ProvisioningAudit.insert` accepts only a JSON object and replaces NUL (refused by jsonb even
  escaped). Control characters are refused in server, company, workspace and user names. The
  delegable actions write their audit row in the same central transaction as the change, or (when
  the change is in another database) insert it first in an open central transaction committed right
  after the change, so a failed insert stops the change; only a failed final commit after a tenant
  commit can leave a change without its row. Audit failures of administrator actions are never
  swallowed (workspace self-service, extensions).
- **Password guessing at server sign-in.** Besides the per-IP login rate limit, each existing login
  (owner or administrator) gets at most 10 failures per 5-minute window; then it is refused with the
  same `LOGIN_REJECTED`, without checking the password (one dummy hash is still computed). It
  throttles and never locks: nothing is stored, attempts while throttled do not extend the window,
  and an attacker can at most keep the login throttled while the attack lasts.
- **Owner/administrator race.** Both V015 identity triggers take `pg_advisory_xact_lock` on the user
  id before checking, so two concurrent transactions cannot make one user both.
- **Owner-only power that stays owner only.** MCP tools (`serverOwnerSessionUser`), workspace modules
  with implicit rights, `UserModuleAccess.serverOwner`, agent sign-in at server level
  (`verifyCredentials` revokes and refuses administrator sessions), the four raw-SQL owner checks.
- **Residual risks (accepted).**
  - Accounts and agents created inside a company after `server.companies.enter` persist:
    `server.companies.enter` (and `owner_password`) equal full control of that company, and inside it
    the administrator, acting as the business owner, can create company users or connect AI agents.
    Those accounts and agents belong to the company, are not tracked per administrator and keep
    working after the administrator is deactivated or deleted; only the entry is audited
    (`server_admin.entered_company`). The owner must review the company's users and agents after
    revoking such an administrator.
  - Deleted administrator logins are not reusable: deleting keeps the `application_users` row so the
    audit keeps pointing to it; that login can never sign in again and can never be given to a new
    administrator, owner, business owner or company user.
  - `activeCompanyCount` in the module list counts every company. Changes recorded only by login
    (extension packages) are attributed by that login.
