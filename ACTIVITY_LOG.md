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
3. **Append-only and tamper-evident.** Prevented: nobody can edit or delete rows through the
   application — no route, tool, screen or job does it, and there is no retention purge; the runtime
   database user is granted only `SELECT, INSERT` and triggers refuse `UPDATE`/`DELETE`/`TRUNCATE`
   for everyone, the table owner included (defence in depth: the owner can disable them). Detected:
   every committed row is sealed into a hash chain that the server checks hourly against anchors kept
   in the server audit, so any alteration of sealed rows covered by an anchor, structural tampering,
   a missing table or disabled triggers are reported. Not guaranteed: someone with direct database
   access as the service or owner role, root, the PostgreSQL superuser or the hosting provider can
   alter data; alterations after the last hourly anchor can be hidden by recomputing the chain; a
   compromised server can stop the verification (visible as missing anchors). The log is not proof
   against the server operator. §9 "Integrity" has the details.
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
activity_log_chain(chain_seq BIGINT PK, id BIGINT UNIQUE, prev_hash, row_hash)   -- hash chain, one row per sealed row, written at commit (§9); its last row is the head
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
- Core records by itself: sign-in, failed sign-in (the account's login when it exists, never the
  password; an unknown login as "(usuario desconocido)", without the typed text, with
  `actor_kind = system`), sign-out, password change/reset/recovery, user
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

- Code: `server/.../server/activity/` (`ActivityLog`, `ActivityActor`, `ActivityChanges`, `ActivityNames`, `ActivityDisplay`,
  `ActivityLogReader`, `ServerActivity`, `ServerAuditMcpTools`, `CoreActivity`, `ActivityRoutes`, `ActivityIntegrity`);
  contract `core/.../bms/ActivityLog.kt`. Module adoption: `ACTIVITY_LOG_MODULE_GUIDE.md`; test kit `ActivityLogTesting`.
- Routes: `GET /companies/me/activity` and `GET /server/activity?company=` (+ `/export`, CSV), query `user` (id or
  text: name, display or username), `module`, `entityType`, `entityId`, `action`, `q`, `from`, `to` (YYYY-MM-DD),
  `page` (0–1000; deeper pages need narrower dates), `pageSize` (≤ 200) → `{items, page, pageSize, hasMore,
  unavailableCompanies}`; rows as §2 plus `moduleName`, `actionLabel`, `companyCode` (server route), `viaAgent`,
  `fromServer`, `actorKindLabel`; `changes[].secret` marks a masked secret. Refusals `{code, message}`: 401 session,
  403 `ACTIVITY_PERMISSION_REQUIRED` / `SERVER_PERMISSION_REQUIRED`, 400 `INVALID_FILTER`, 404 `COMPANY_NOT_FOUND`,
  503 `ACTIVITY_UNAVAILABLE`. Reading is not logged; exports are (company row `exported`, `server_activity.exported`).
- Who a row shows: `actorDisplay` is rebuilt when the row is read from `actor_kind`, `actor_name`, `agent_name` and
  `real_actor_*` (`ActivityDisplay`), never from the stored `actor_display`; the CSV adds "Tipo de actor" and the app
  shows a badge per row (Agente IA, Desde el servidor, Importación, Automatización, Tarea programada, Integración,
  Sistema) from those fields. A name containing a marker the log adds itself ("Agente de", "desde el servidor",
  "Importacion (", "Automatizacion", "Tarea programada"; accents, width, invisible characters and Latin look-alikes
  from Cyrillic and Greek ignored — "Аgente de Ana" with a Cyrillic А is caught), or that is exactly the name of one of
  the log's own actors ("Sistema", "Importacion"/"Importación", "Automatizacion"/"Automatización", "Tarea programada",
  "Cloudbeds", "Sitio web", "Portal WiFi", "monday"), is refused for users, business owners, administrators and agent
  tokens/OAuth clients (`ActivityNames.hasMarker`), and shown in « » wherever it still appears (rows written before;
  automation, integration and board names only for markers). A stored actor (`toStoredJson`) is only used as an
  import: `restoreImport` / `restoreImportOrNull` (kinds and origins validated, texts cut to 200, display rebuilt);
  the deprecated `restore` now returns the same import (never the stored person), kept only for `modules/crm`
  (`CrmImport.startedBy`), the one caller left.
- Reads are bounded: the company route and the agent tools run in a read-only transaction with
  `SET LOCAL statement_timeout = 15s`; the merged server read takes every company in parallel on the pooled tenant
  connections in batches of ceil(rows / companies) — more batches only from the companies whose rows are still the
  newest (exact merge, about 2 × rows in memory) — within 20 s overall, at most 1,000 rows deep; exports read at most
  10,000 rows in total. Rate limits: `/companies/me/activity*` and `/server/activity*` 30 requests per minute per
  address (RateLimit "activity", besides "admin"); the agent tools 30 calls per minute per user and company.
- MCP: `empresa_list_activity`, `empresa_get_activity_entry` (one `viewed` row per call);
  `servidor_list_server_audit`, `servidor_list_company_activity` (one `server_audit.agent_read` audit row per call).
- Core's own rows tolerate a company whose table does not exist yet with a warning **only while its migration history
  does not list tenant V011**; once it does, a missing table (SQLSTATE 42P01) fails the change and raises
  `activity.integrity_failed`. Module rows never tolerate it. Failed sign-ins: first failure of a (login, address)
  per 5 minutes is written at once, the rest of the window becomes one row with the count; at most 20 rows per
  address and window. Changes made from the server (company data, modules, business owner) write their company row
  on the change's own tenant transaction, or first on an open tenant transaction committed only after the central
  commit (a failed central commit leaves no row; a failed tenant commit after it is audited as
  `activity.row_not_written`).
- Secrets: field names with password, passphrase, secret, token, api key, credential, private key, signature, webhook,
  clave, or the words pin, otp, psk, wpa, hash, salt, private, pwd, firma, cookie, dsn, bearer, mfa, or "key" alone or
  after api/secret/license/access/... are stored as changed; so are values that carry a credential (URL with
  user:password@ or ?token=…, private keys, known token formats). Extension settings show values only when the manifest
  marks them (`type` text/number/boolean/select, or `loggable: true`) and they are not `secret`; anything unmarked
  is stored as changed.

### Integrity (tamper evidence)

- **What is prevented.** Nobody can edit or delete rows through the application: no route, MCP tool, screen or
  job does it, and none is planned. The runtime role is granted only `SELECT, INSERT` on `activity_log` and
  `activity_log_chain` and `USAGE` on the id sequence (server catch-up and `update.sh`); BEFORE UPDATE/DELETE and
  BEFORE TRUNCATE triggers refuse those operations on both tables for everyone, the owner included. These triggers
  are defence in depth, not a barrier: the server owner's role owns the company database and can disable them;
  verification reports that. The runtime role is not a member of that role (SELF_HOSTING.md §5, Roles de PostgreSQL):
  only the separate provisioning login can `SET ROLE` to it, so the pooled connections cannot disable, alter or drop
  anything of the log. `update.sh` gives the objects an older version created as the PostgreSQL superuser to the
  database owner, all of the log's objects together (their owners stay one role).
- **Chain.** A BEFORE INSERT trigger gives each row its id from the sequence (explicit ids are refused) and the time of
  its transaction (no backdating), and refuses a transaction that is not READ COMMITTED (a REPEATABLE READ or
  SERIALIZABLE writer would not see the chain rows committed while it waited for the lock and would fail at commit:
  SQLSTATE 0A000 with a clear message at the insert instead). The first row of a transaction queues the deferred
  constraint trigger `activity_log_seal` (its `WHEN`); at commit it takes the advisory lock 735192402 (per company
  database) for the rest of that commit, reads the chain's last row (the head), and seals **every** unsealed row of
  the transaction in id order in one pass: one row each in `activity_log_chain` (`chain_seq = head + 1…`, `prev_hash`,
  `row_hash = sha256(prev_hash || '|' || canonical JSON of every stored field)`), one INSERT. No function is SECURITY
  DEFINER: the seal runs as the writer, so V011 means the same whichever role applied it (the server owner's role for
  companies the server creates, `postgres` for those `update.sh` migrated). `activity_log` is never updated and the
  chain is insert-only. Writers chain in commit order and hold the lock only while committing, never during their
  work. The seal function alone has `lock_timeout = 10s` (function-level `SET`): a commit that waits more than 10 s
  for the lock fails cleanly (SQLSTATE 55P03) instead of hanging, while the writer's own statements keep the
  transaction's setting (no spurious lock timeouts in ordinary business transactions). Measured on PostgreSQL 16
  (commit time of one transaction): 5,000 rows **0.05 s**, 10,000 **0.10 s**, 20,000 **0.20 s**, 200,000 **2.4 s**;
  16 writers × 50 commits give a contiguous verified chain of 800; 8 writers × 8 transactions with 60 ms of work each
  take under 1 s. No row cap is needed in the importers.
- **Anchors.** An hourly job (first run 10 minutes after start) checks every company and writes an anchor
  `activity.anchor` (company, database oid, last position, last id, last hash, row count) to `provisioning_audit_log`
  when anything of that changed; an anchor is also written when a company is deactivated, and a final one with a
  summary (rows, first and last date) before it is deleted — the rows themselves are not archived and
  `company.deleted` carries the count and last hash. `provisioning_audit_log` belongs to `postgres` (control
  migrations run as the superuser in `install.sh`/`update.sh`); the runtime role is granted only `SELECT, INSERT` and
  the server owner's role nothing, so the app can append anchors but not change or delete them (checked by the tests
  with the install grants). Anchors are matched **by chain content**, not by database oid: a database restored from a
  dump (new oid, same chain) verifies, with a note; one restored from a copy older than an anchor, or replaced, is
  reported.
- **Verification** (`ActivityIntegrity.check`, `GET /server/activity/verify?company=` — owner only; without `company`,
  every company — and the hourly job). Detection only, as the runtime role, read-only, with `search_path =
  pg_catalog, pg_temp`, `row_security = off` and schema-qualified SQL, in short transactions and chunks of 5,000 chain
  positions, 60 s per statement, no lock that blocks writers. It never executes anything the company database
  defines: it first reads catalogs only (`pg_catalog.*`), and reads the rows of `activity_log`, `activity_log_chain`
  or `schema_migrations` only when they are plain heap tables without row security whose columns all have built-in
  types (oid below 16384), checked again under `ACCESS SHARE` (which writers do not wait for, and the DDL that could
  change that does) right before each read — otherwise it reports that and does not read that table. Then it recomputes every hash with
  built-ins only, checks the chain is contiguous and linked, every chain row has its log row, every anchor matches the
  chain at its position, no anchored position is missing, and reports rows without a seal older than 2 minutes
  (an honest row is sealed in the same commit). The **deviation query**
  (`server/src/main/resources/activity/activity-deviations.sql`) compares the objects with V011: extra, missing,
  changed or disabled triggers (`tgenabled` must be `O`), rules, row security and policies, inheritance or partitions
  either way, typed tables, reloptions outside storage/vacuum tuning, extended statistics objects, foreign keys
  pointing at the log, extra or changed columns (built-in type by oid, NOT NULL, default, collation, generated,
  identity), columns of non-built-in types and casts that are not PostgreSQL's from or to the columns' types,
  constraints (definition, NOT VALID), indexes (definition, invalid), UNLOGGED tables or sequence, the access method,
  the sequence parameters, the three functions (sha256 of their source per known migration version, overloads,
  SECURITY DEFINER, `proconfig`, language, volatility) and owners (tables, sequence and functions must all belong to
  the role that owns `activity_log`). The report (`ActivityIntegrityReport`: `ok`, `rows`, `lastSeq`, `lastId`,
  `lastHash`, `anchorsChecked`, `firstBadId`, `problems`, `notes`) names the first bad row. Every finding is written
  once per distinct finding (while the server runs) as `activity.integrity_failed` and sent as a notification to every
  active server owner.
- **Migrations.** Tenant V011 is applied like the earlier tenant migrations: by `update.sh` (as the `postgres` superuser
  in releases before the role split; now as the database owner's role) in every existing company (then its grants: `SELECT, INSERT` on `activity_log` and `activity_log_chain`, `USAGE` on
  `activity_log_id_seq`), and by the server (as the server owner's role) when it creates a company and in the startup
  catch-up. It works whoever owns the company's other tables (servers where an older `update.sh` created them as
  `postgres` included; tested). A later migration that changes the log's functions adds its function hashes as a new
  `ActivityIntegrity.versions` entry (V011's stay) and updates the deviation query's lists.
- **What is detected.** Any change to, or deletion of, a sealed row covered by an anchor; a gap anywhere in the chain;
  rows missing at its end up to the last anchor; unsealed rows; structural tampering (any object of the log not exactly
  V011's, as listed above); a missing table; disabled triggers; a replaced database or a restore from a copy older than
  an anchor. Reported by the next check with the first bad id.
- **What is not.**
  - Someone with direct database access as the service role or the server owner's role, root, the PostgreSQL
    superuser or the hosting provider can alter the data (rows, chain and, for root and the superuser, the anchors and
    the server audit too).
  - Alterations after the last hourly anchor can be hidden by recomputing the chain from the altered row on (the hash
    is not keyed): up to an hour of rows.
  - A compromised server can stop the verification or the anchors; that is visible as anchors that stop appearing.
  - The log is not proof against the server operator.
  - A compromised runtime role still writes the log: it can add false rows (sealed with their real time), stop writing,
    leave its own transaction's rows unsealed by forging the transaction-local settings (reported), append false rows
    to the server audit (a false anchor causes a false alarm), and drop the whole company database (only detected, by
    the anchors).
  - Backups: a restore of an older copy is reported (rows after it are missing), by design.

### Known limitations

- **Session advisory lock.** Any role with CONNECT on the company database (for example a business owner's login
  role) can hold the chain's advisory lock 735192402 at session level (`pg_advisory_lock`) and make writes to that
  company's log wait and fail after 10 s (SQLSTATE 55P03) until it lets go; a role that can insert into the chain
  (the runtime role, the owner's role) can also do it with a chain row ahead of the real one (unique violation). It is
  a denial of service, not a way to alter sealed rows. PostgreSQL offers no way to reserve an advisory lock key.
- **Migrations as superuser in owner-owned databases** (fixed, SECURITY_FIXES.md A2): `update.sh` runs company
  migrations as the database owner's role through the provisioning login, with a fixed `search_path`, only on
  databases named `bms_<code>` owned by a server owner's role. The superuser only runs catalog queries, `GRANT`,
  `REVOKE` and `ALTER ... OWNER` there (the one-time role layout move), never code the database defines.
