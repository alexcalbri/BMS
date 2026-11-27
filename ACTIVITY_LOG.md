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
3. **Append-only and tamper-evident.** The runtime database user is granted only `SELECT, INSERT` on
   it and triggers refuse `UPDATE`/`DELETE`/`TRUNCATE` for everyone. No screen, route, tool or job edits
   or deletes rows. No retention purge. Grants alone do not stop the runtime user: it is a member of the
   server owner's role, which owns every company database. So every committed row is sealed into a hash
   chain checked hourly against anchors kept in the server audit, and `scripts/ubuntu/activity-harden.sh`
   (root, hourly) gives the log to a role outside that membership after normalizing it to exactly what
   the migration creates. The guarantee starts at that first hardening (up to an hour after a company is
   created); before it, and for anything the PostgreSQL superuser does, there is only detection, or not
   even that. §9 "Guaranteed / Not guaranteed" says exactly what holds.
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
activity_log_chain(chain_seq BIGINT PK, id BIGINT UNIQUE, prev_hash, row_hash)   -- hash chain, one row per sealed row, written at commit (§9)
activity_log_chain_head(id = 1, last_seq, last_hash, hardened_at, hardened_seq)  -- head of the chain; when the guarantee started
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

### Integrity (tamper evidence and hardening)

- **Chain.** A BEFORE INSERT trigger gives each row its id from the sequence (explicit ids are refused) and the time of
  its transaction (no backdating), refuses a transaction that is not READ COMMITTED (a REPEATABLE READ or SERIALIZABLE
  writer would fail at commit as soon as another one committed first: SQLSTATE 0A000 with a clear message at the
  insert instead), and gives a writer without one a `lock_timeout` of 10 s for the rest of its transaction. The first
  row of a transaction queues the deferred constraint trigger `activity_log_seal` (its `WHEN`); at commit it takes
  the advisory lock 735192402 for the rest of that commit, reads the head, and seals **every** unsealed row of the
  transaction in id order in one pass: one row each in `activity_log_chain` (`chain_seq = head + 1…`,
  `prev_hash`, `row_hash = sha256(prev_hash || '|' || canonical JSON of every stored field)`), one INSERT, the head
  written once. `activity_log` itself is never updated (every UPDATE is refused), and the chain is insert-only.
  Writers chain in commit order and hold the lock only while committing, never during their work. Measured on
  PostgreSQL 16 (this container, commit time of one transaction): 5,000 rows **0.05 s**, 10,000 **0.10 s**,
  20,000 **0.20 s**, 200,000 **2.4 s** (before: 0.8 s / 2.5 s / 9.9 s / did not finish); 16 writers × 50 commits
  give a contiguous verified chain of 800; 8 writers × 8 transactions with 60 ms of work each take under 1 s. Other
  commits of the company wait while one seals; a commit that waits more than 10 s fails cleanly (SQLSTATE 55P03)
  instead of hanging. No row cap is needed in the importers.
- **Verification** (`ActivityIntegrity.check`, as the runtime role, read-only, in short transactions: catalogs and
  head first, then the chain in chunks of 5,000 positions up to the head read, then unsealed rows; no lock that blocks
  writers, 60 s per statement): recomputes every hash with PostgreSQL built-ins only (never the company's own
  functions), checks the chain is contiguous and linked, every chain row has its log row, nothing lies beyond the head
  and the head matches its end, and every unsealed row. The objects are compared with V011 by the **deviation query**
  (`server/src/main/resources/activity/activity-deviations.sql`, the same file activity-harden.sh runs): extra,
  missing, changed or disabled triggers (`tgenabled` must be `O`), any rule, row security (`relrowsecurity`,
  `relforcerowsecurity`) or policy, inheritance or partitions either way, foreign keys pointing at the log, extra or
  changed columns (type, NOT NULL, default, generated, identity), constraints (definition, NOT VALID), indexes
  (definition, invalid), UNLOGGED tables or sequence, the access method, the sequence parameters, the three functions
  (sha256 of their source per migration version, overloads, SECURITY DEFINER, `proconfig`, language, volatility...),
  and per-database settings (`ALTER DATABASE ... SET`) outside a small allow-list; and, for hardening, owners
  (tables, sequence, functions: `bms_audit`), every grant (only the runtime role: `SELECT, INSERT` on the log,
  `SELECT` on chain and head, `USAGE, SELECT` on the sequence; no column grants, no PUBLIC `EXECUTE`), the role
  `bms_audit` (no attributes, the runtime not a member, `USAGE` on `public`), the schema `bms_audit`, the guard
  function and both event triggers, and `hardened_at` in the head. It reports the first bad row id and
  **"Protegido desde <hardened_at>: las filas hasta la posicion <hardened_seq> ... no estan cubiertas"** (report fields
  `hardenedAt`, `hardenedSeq`, `coverage`). `GET /server/activity/verify?company=` (owner only; without `company`,
  every company) returns it (`ActivityIntegrityReport`). An hourly job (first run 10 minutes after start) checks every
  company and writes an anchor `activity.anchor` (company, database oid, last position, last id, last hash, row count,
  hardened, `hardenedAt`) to `provisioning_audit_log` when anything of that changed; an anchor is also written when a
  company is deactivated, and a final one with a summary (rows, first and last date) before it is deleted — the rows
  themselves are not archived and `company.deleted` carries the count and last hash. Anchors are matched **by chain
  content**, not by database oid: a database restored from a dump (new oid, same chain) verifies, with a note; one
  restored from a copy older than an anchor, or replaced, is reported. A change of `hardened_at` against an anchor
  (unhardened and hardened again) is reported. Every finding is written once per distinct finding (while the server
  runs) as `activity.integrity_failed` and sent as a notification to every active server owner.
- **Hardening** (`scripts/ubuntu/activity-harden.sh` + `activity-harden.sql`, run by install.sh and update.sh as root,
  then hourly by `bms-activity-harden.timer`; PostgreSQL 16 or later). Every superuser `psql` of the root scripts goes
  through `scripts/ubuntu/pg-safe.sh`: `PGOPTIONS` with `role=none`, a pinned `search_path` and read-write (they win
  over `ALTER DATABASE ... SET`), `-X`, `ON_ERROR_STOP`, and `SET ROLE NONE; SET search_path = pg_catalog, pg_temp`
  first; before a company database is touched, its per-database settings are read from the maintenance database and
  anything outside the allow-list (`role`, `search_path`, `default_transaction_read_only`, `statement_timeout`...) is
  logged, reset with `ALTER DATABASE ... RESET` and reported; nothing runs while one is still there. The harden SQL and
  V011 are fully schema-qualified (operators resolve only in `pg_catalog`: the path is pinned and `pg_temp` is never
  searched for them); every function it creates has `SET search_path = pg_catalog, pg_temp`. Per company:
  1. the deviation query (read-only). Nothing found and `hardened_at` set: the company is left alone (no lock at all);
  2. otherwise, under `lock_timeout = 3s` (a busy company is retried an hour later) and an ACCESS EXCLUSIVE lock on
     the three tables, checked to be plain tables before any row is read: it removes everything that is not exactly
     V011's — every trigger, rule and policy, row security, inheritance and partitions, foreign keys pointing in,
     extra columns, other constraints and indexes, function overloads, other objects in schema `bms_audit` — resets
     defaults, NOT NULL, identity, persistence and the sequence parameters, re-creates the functions, triggers and
     indexes **from V011 itself** (the canonical migrations installed with the script, spliced in as the superuser),
     gives everything to `bms_audit`, revokes every grant (column grants and PUBLIC `EXECUTE` included) and grants the
     runtime role `SELECT, INSERT` (+ sequence `USAGE, SELECT`, chain and head `SELECT`), and installs the event
     triggers `bms_activity_log_no_drop` (sql_drop) and `bms_activity_log_no_move` (ALTER SCHEMA) whose guard matches
     exact object identities (the log's tables, sequence, functions, indexes, the schemas `public`/`bms_audit`, and
     triggers/constraints/columns "on" those tables — other `public.activity_log*` objects of the owner are not
     blocked); needed because the server owner's role owns the database and its schema `public`;
  3. the first time (the server owner's role owned something until now), rows left unsealed are sealed and the head
     records `hardened_at` and `hardened_seq`: **the guarantee starts there**; later runs keep it;
  4. the deviation query again: anything left is an error.
  What it found, removed or reset is logged (`/var/log/bms-activity-harden.log`) and written to the server audit as
  `activity.integrity_failed` (`details.source = activity-harden`); a company database that does not exist is logged.
  Regression test against the server owner's role (catalog functions and operators shadowed in public, per-database
  `search_path`/`role`/read-only settings, triggers, rules, policies, defaults, columns, inheritance, constraints and
  indexes whose functions make that role superuser, unsealed rows, a trigger on `schema_migrations`, busy and idle
  companies, all control and tenant migrations through the helpers): `scripts/ubuntu/tests/activity-harden-test.sh`.
- **Migrations.** Company databases are migrated by update.sh as the server owner's role (the runtime login with
  `role=<database owner>`, `pg_owner`), never as the superuser: whatever that role planted there (a trigger on
  `schema_migrations`, a function in public) runs with its own rights only. The control database belongs to the
  superuser alone (checked before each use: owner, schema `public`, nobody else may create there, no object of another
  role in `public`) and is migrated as the superuser with `search_path = public, pg_catalog, pg_temp`. A later tenant
  migration that changes the log's own objects (a V012) cannot be applied by the server's migrator on a hardened
  company (it runs as the owner's role): the server skips the log's migrations when `activity_log` belongs to
  `bms_audit`. The supported path: write it fully schema-qualified and idempotent, add it to
  `CANONICAL_MIGRATIONS` in activity-harden.sh (after V011) and its function hashes as a new
  `ActivityIntegrity.versions` entry (V011's stay, so a release can verify companies on either), update the deviation
  query's lists; update.sh then runs the hardening, which applies it as the superuser on every hardened company as part
  of normalizing (step 2), and re-checks. For maintenance by hand, as the superuser in that company database:
  `DROP EVENT TRIGGER bms_activity_log_no_drop; DROP EVENT TRIGGER bms_activity_log_no_move;` (the guard already lets
  the superuser through; dropping them is only needed to let another role drop the objects), then run
  `bms-activity-harden --database <db>` to put everything back (SELF_HOSTING.md).
- **Server audit.** `provisioning_audit_log` belongs to the superuser (control migrations run as `postgres`); the
  runtime role is granted only `SELECT, INSERT` and the server owner's role nothing, so the app can append to it but
  not change or delete it (checked by the tests with the install grants).
- **Guaranteed**, on a company from its `hardened_at` on (the report says so), as long as the PostgreSQL superuser is
  not involved: the runtime role, directly or as the server owner's role, cannot update, delete or truncate log or
  chain rows, change the head, disable, drop or replace the triggers and functions, add triggers, rules, policies,
  columns, constraints or indexes, drop, rename or move the tables, their schema, the indexes or the sequence, change
  ownership or grants, or give itself `bms_audit` (all checked by tests); rows it writes are stored exactly as sent,
  with their real time, and sealed at commit; nothing it planted in the database or its settings runs as the
  superuser in the root scripts (regression test). Tampering installed before the first hardening does not survive it
  and is reported. On every company, hardened or not: a change to, or deletion of, any sealed row, a gap anywhere in
  the chain, rows missing at its end up to the last anchor, unsealed rows, any object of the log not exactly V011's, a
  per-database setting outside the allow-list, a missing table or a replaced database (or a restore from a copy older
  than an anchor) is detected by the next check and reported with the first bad id.
- **Not guaranteed.**
  - Rows up to `hardened_seq` (written while the server owner's role owned the log): a new company for up to an hour
    after it is created from the app (until the next timer run), every company of a server that has not run update.sh
    since this version, and a company whose hardening was undone. That role could have rewritten or deleted those rows
    and the chain with them consistently; only the anchors written before limit that (rows after the last anchor, up
    to an hour, can be rewritten with every later row and the head without detection — the hash is not keyed).
  - The PostgreSQL superuser (and root) can do anything, anchors and the server audit included.
  - A compromised runtime role (or the owner's role through it) still writes the log: it can add false rows (sealed
    with their real time and position), stop writing, leave its own transaction's rows unsealed by forging the
    transaction-local settings (reported), make other companies' writers wait up to 10 s and fail (holding the sealing
    lock), revoke `USAGE` on `public` from `bms_audit` so every write fails until the next hardening (reported as not
    hardened), append false rows to the server audit (never change or remove existing ones: a false anchor causes a
    false alarm, but a false "deletion" anchor followed by recreating the company database makes the replacement look
    like a deletion; both rows stay visible), and drop the whole company database (that is how companies are deleted:
    only detected, by the anchors).
  - Backups: `pg_dump` as the server owner's role no longer reads the log (it is `bms_audit`'s); dump and restore as the
    superuser (SELF_HOSTING.md §14). A restore of an older copy is reported (rows after it are missing), by design.
- At update time an existing server needs nothing by hand: update.sh migrates every company as the owner's role,
  installs the timer and hardens every company; check `/var/log/bms-activity-harden.log` (and the
  `activity.integrity_failed` rows with `source = activity-harden`: tampering found before the first hardening) and
  `GET /server/activity/verify` (`hardened`, `coverage`).
