# Company activity log — specification

Branch `feature/activity-log`. Core 0.12.0, app 0.10.0, every module and extension that gains records
gets a minor bump with `minCoreVersion` 0.12.0 (extensions: `minModuleVersion` of their module).

## 1. Goal

Every change made by anyone is recorded with **who, when, where and what**, so that:

- a company's business owner (and users granted the permission) can see everything that happened in
  the company: by user, module, record, kind of action and date;
- the server owner (and server administrators with `server.audit.view`, within their company scope)
  can see the same for every company, next to the existing server audit;
- their **AI agents** can read the same logs to help audit (§7).

Rules:

1. **One log per company**, in the company database: table `activity_log`. It does not replace the
   per-record histories of the modules (lead activity, task activity…), which stay as they are.
2. **Written in the same transaction as the change.** If the log row cannot be written, the change
   fails. Never swallow the error.
3. **Append-only.** The runtime database user gets only `SELECT, INSERT` on it and a trigger refuses
   `UPDATE`/`DELETE`/`TRUNCATE`. No screen, route, tool or job edits or deletes rows. No retention
   purge.
4. **The real person.** The actor is the human behind the action even when they act through
   something else:
   - through an **AI agent**: the row keeps the user as actor plus the agent's name, and every
     display says **"Agente de <usuario>"** (stored in `actor_display`);
   - a **server owner or server administrator inside a company** (switcher session): the row keeps
     the business-owner user the session belongs to *and* the real person (`real_actor_*`), and the
     display is "<nombre real> (desde el servidor)";
   - automations, scheduled jobs, imports and inbound integrations: `actor_kind` says which, the
     display names it ("Automatización <nombre>", "Importación", "Cloudbeds", "Sitio web"…), and when
     a person started it (an import, a manual automation button) that person is stored too.
5. **One language.** Everything stored (`summary`, field labels, `actor_display`) is written in
   **Spanish**, the source language, like the existing histories — build the texts under
   `I18n.withLanguage(I18n.Source)` or with plain Spanish literals; do not call `tr` with the request
   language for stored text. Screens translate only their own chrome (titles, filters, column names).
6. **No secrets, no bulk.** Never store passwords, tokens, API keys, codes or file contents; for a
   secret setting store only that it changed. Long texts are cut to 500 characters per value. Reads
   are not logged (except the cases in §4).

## 2. Data (tenant migration `V011__create_activity_log.sql`)

```
activity_log(
  id BIGSERIAL PK, occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  actor_kind TEXT NOT NULL,          -- user | business_owner | server_owner | server_admin | automation | job | import | integration | system
  actor_user_id UUID NULL,           -- tenant user (or the business owner the session belongs to)
  actor_name TEXT NOT NULL,          -- that user's name at the time, or the automation/integration name
  actor_display TEXT NOT NULL,       -- what screens show: "Ana Ruiz", "Agente de Ana Ruiz", "Luis Mora (desde el servidor)", "Importación (Ana Ruiz)"…
  agent_name TEXT NULL,              -- AI agent / connection name when the action came through one
  real_actor_kind TEXT NULL, real_actor_user_id UUID NULL, real_actor_name TEXT NULL,  -- server person behind a switcher session
  module_id TEXT NOT NULL,           -- 'core' for company administration
  entity_type TEXT NOT NULL, entity_id TEXT NULL, entity_label TEXT NOT NULL DEFAULT '',
  action TEXT NOT NULL,              -- created | updated | deleted | archived | restored | status_changed | assigned | commented | attachment_added | attachment_removed | settings_changed | imported | signed_in | sign_in_failed | signed_out | permission_changed | …
  summary TEXT NOT NULL,             -- one Spanish sentence
  changes JSONB NOT NULL DEFAULT '[]', -- [{"field":"email","label":"Correo","old":"a@x","new":"b@x"}]
  origin TEXT NULL                   -- app | agent | api | automation | import | webhook
)
```
Indexes: `(occurred_at DESC)`, `(actor_user_id, occurred_at DESC)`, `(module_id, occurred_at DESC)`,
`(entity_type, entity_id)`. Applied to existing companies by `update.sh` and the startup catch-up,
with the restricted grants.

## 3. Core API

- `ActivityActor` — resolved once by Core from the request/session/agent context (user session,
  business owner, switcher session with its opener, agent token/OAuth connection with its name,
  group-agent calls acting in another company). Modules obtain it from what they already receive
  (`ModulePermissions` / the MCP call context / the access object) and never build identity by hand.
  Non-human actors are built with explicit factories: `ActivityActor.automation(name, startedBy?)`,
  `.job(name)`, `.import(startedBy)`, `.integration(name)`, `.system()`.
- `ActivityLog.record(connection, actor, ActivityEntry(moduleId, entityType, entityId, entityLabel,
  action, summary, changes, origin))` — same connection/transaction as the change. Helper
  `ActivityChanges.diff(labels…)` builds the old → new list and applies the secret/length rules.
- Core records by itself: sign-in, failed sign-in (login name tried, never the password; unknown
  logins too, with `actor_kind = system`), sign-out, password change/reset/recovery, user
  create/edit/activate/deactivate, permission grants and removals (per permission), company profile,
  mail settings, backups create/import/delete/download, module enable/disable for the company,
  extension install/configure/enable/uninstall in the company, default language, business-owner data
  changes made from the server, switching companies. Server-level sign-ins (owner/administrators)
  and failed ones go to `provisioning_audit_log`.
- Reading: `GET /companies/me/activity` (business owner, or the new permission
  `empresa.activity.view` "Ver el registro de actividad") and `GET /server/activity?company=…`
  (owner or `server.audit.view` + company scope; without `company` it merges the newest rows of the
  companies in scope). Filters: user, module, entity type/id, action, text, from/to; newest first;
  page size ≤ 200. Response rows carry everything in §2. Export as CSV from the same filters.
- A test utility lists every write route/handler of a module so each module test can assert "this
  write produced an activity row" (§5).

## 4. What each module must record

Every operation that creates, changes or removes data, by any path (screen action, MCP tool, bulk
action, import, automation, webhook, extension):

- create / edit (with field-level old → new) / delete (the row is written **before** the record and
  its history disappear, with the record's label and key data) / archive / restore;
- status or stage changes, assignments, ownership transfers, approvals and rejections;
- notes and comments added, edited or deleted (the text itself cut to 500 characters);
- attachments added or removed (file name and size, never the content);
- checklist items, sub-items, payments, visits, shifts, registrations and similar child rows;
- **settings and configuration** of the module (stages, fields, automations, templates, departments,
  approvers, plans, networks, integrations…): what changed, secrets masked;
- imports (one row for the import with totals and who started it, plus the per-record rows the
  importer already produces, attributed to the import and the person);
- exports and downloads of bulk data (customer lists, CSV) — the one kind of read that is logged.

Existing per-record history rows keep being written exactly as today (their text is asserted by
existing tests). Where a module passes an author string like "Ana (agente X)", keep it; the activity
row uses the Core actor instead.

## 5. Tests

Per module: a test that walks its write operations (screen actions, MCP write tools, importer) and
asserts each leaves exactly the expected `activity_log` rows with the right actor, action and changes,
including: via agent → `actor_display` starts with "Agente de "; delete → row exists after the record
is gone; settings change → row without secret values. Core: append-only enforcement (UPDATE/DELETE
refused for the runtime role, on PostgreSQL), actor resolution for each session kind, route
permissions and scope of both read routes, CSV export, catch-up migration.

## 6. App

- Company: screen **Registro de actividad** (business owner or `empresa.activity.view`): filters by
  user, module, action, text and dates; row = time, actor display, module, record, summary; expanding
  a row shows the old → new changes; export CSV.
- Server: the audit screen gains a second tab **Actividad en empresas** with a company filter (owner
  or `server.audit.view`).
- Only with Core ≥ 0.12.0.

## 7. AI agents

- Company level: MCP tools `list_activity` (same filters) and `get_activity_entry`, usable by the
  agent of the business owner or of a user holding `empresa.activity.view`. Read-only.
- Server level: tools `list_server_audit` and `list_company_activity` for the server owner's agent.
- Server administrators: an administrator holding `server.audit.view` may connect an agent at server
  level, and that agent gets **only** these two read tools, limited to the administrator's company
  scope, re-checked on every call (permission removed, administrator deactivated or scope changed →
  the next call fails and the connection is revoked). No other server tool is ever offered to an
  administrator's agent. Connecting, each call and revocation are audited.
- Reading the logs through an agent is itself recorded (one row per call with the filters used).

## 8. Working rules

Tests: `/home/user/tools/bms-test.sh <worktree> ["*Filter*"]` (PostgreSQL-gated tests: export
`BMS_SERVER_ADMINS_PG_URL='jdbc:postgresql://127.0.0.1:54329/postgres?user=postgres'` and the
variable the new tests define). Never commit `build.gradle.kts` or
`gradle/gradle-daemon-jvm.properties`; never edit an applied migration (tenant V001–V010, module
migrations already released). Commits: author/committer `alexcalbri
<164930758+alexcalbri@users.noreply.github.com>`, Spanish conventional message, no co-author or
session trailers, no push. User-visible screen text goes through `tr` with catalog entries
(I18N.md); stored log text is Spanish (§1.5).

## 9. Core implementation (0.12.0)

- Code: `server/.../server/activity/` (`ActivityLog`, `ActivityActor`, `ActivityChanges`, `ActivityLogReader`,
  `ServerActivity`, `ServerAuditMcpTools`, `CoreActivity`, `ActivityRoutes`); contract `core/.../bms/ActivityLog.kt`.
  Module adoption: `ACTIVITY_LOG_MODULE_GUIDE.md`; test kit `ActivityLogTesting` (server tests).
- Routes: `GET /companies/me/activity` and `GET /server/activity?company=` (+ `/export`, CSV), query `user` (id or
  text: name, display or username), `module`, `entityType`, `entityId`, `action`, `q`, `from`, `to` (YYYY-MM-DD),
  `page`, `pageSize` (≤ 200) → `{items, page, pageSize, hasMore, unavailableCompanies}`; rows as §2 plus `moduleName`,
  `actionLabel`, `companyCode` (server route); `changes[].secret` marks a masked secret. Refusals `{code, message}`:
  401 session, 403 `ACTIVITY_PERMISSION_REQUIRED` / `SERVER_PERMISSION_REQUIRED`, 400 `INVALID_FILTER`, 404
  `COMPANY_NOT_FOUND`. Reading is not logged; exports are (company row `exported`, `server_activity.exported`).
  The merged server read reads at most 1,000 rows per company (deeper pages need `company`).
- MCP: `empresa_list_activity`, `empresa_get_activity_entry` (one `viewed` row per call);
  `servidor_list_server_audit`, `servidor_list_company_activity` (one `server_audit.agent_read` audit row per call).
- Core's own rows tolerate a company whose table does not exist yet (before V011 reaches it) with a warning;
  module rows never do. Failed sign-ins: first failure of a (login, address) per 5 minutes is written at once, the
  rest of the window becomes one row with the count; at most 20 rows per address and window.
- Residual: the runtime role is a member of the owner's role (installer), so it inherits the table owner's rights;
  the trigger is what refuses UPDATE/DELETE/TRUNCATE, and a role able to run `ALTER TABLE` as the owner could still
  disable it.
