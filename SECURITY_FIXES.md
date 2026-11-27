# Security hardening (branch feature/security-hardening)

Source: three read-only audits of develop @ 227f559 (unauthenticated surface, authenticated surface, deployment).
This file is the work order. Each package owns a set of files; do not edit files of another package (list what you
need from them in your final report instead). Stored/user-facing text stays Spanish through `tr()` with the English
catalog entry added (see I18N_MODULE_GUIDE.md). Activity-log rules: ACTIVITY_LOG_MODULE_GUIDE.md. Never edit an applied
migration; add a new one. No secrets in code, tests, commits or logs.

Rules for every package
- Work only in your worktree. Commit there (identity: alexcalbri <164930758+alexcalbri@users.noreply.github.com>, author
  and committer, Spanish conventional messages, no co-author or session trailers). Do not push, do not touch other branches.
- Never commit `build.gradle.kts` (root) or `gradle/gradle-daemon-jvm.properties` changes made by the test helper.
- Tests: `/home/user/tools/bms-test.sh <your worktree> ["*Filter*"]`. For PostgreSQL-gated tests export
  `P='jdbc:postgresql://127.0.0.1:54329/postgres?user=postgres'` as BMS_TEST_PG_URL, BMS_ACTIVITY_PG_URL,
  BMS_SERVER_ADMINS_PG_URL, BMS_CRM_ACTIVITY_PG_URL (a PostgreSQL 16 is already running on that port; create and drop
  your own uniquely named databases/roles, never touch others). The app (app/) cannot be compiled here.
- Every fix gets a test that fails without it, where a test is feasible. Bump the version of each module/extension you
  change (module.json + definition + README) and Core when Core changes are user-visible (one bump, done by the lead).
- Keep changes minimal and backwards compatible with the live server (7 companies, PostgreSQL 18.6, Ubuntu, data in use).
- Final report: per item — fixed / partially (what is left) / not done (why); files changed; tests run and results;
  anything another package or the server operator must do.

## Package A — privilege separation and deployment scripts (scripts/ubuntu/**, CompanyProvisioningService DB-role parts)
A1. Runtime DB role is a member of the server-owner role (install.sh:668, CompanyProvisioningService.kt:1911-1920):
    inherits CREATEDB/CREATEROLE and owns tenant tables, so per-table grants are cosmetic and the app can drop the
    activity-log triggers. Give the app a plain role; provisioning (create database/role, migrations) must use a
    separate non-inherited path (e.g. NOINHERIT membership + explicit SET ROLE only in provisioning code, or a second
    connection). update.sh must migrate the existing server to the new layout idempotently (table owners on the VPS
    are mixed: some postgres, some owner role).
A2. Tenant migrations run as PostgreSQL superuser inside databases the runtime controls (update.sh:470,526,
    migrations.sh:46); tenant list comes from companies.database_name (runtime-writable, only regex-checked). Run them
    as the owner role, with a fixed search_path (pg_catalog, public), and only on databases matching the expected
    prefix and owned by the expected role.
A3. Build and service share the `bms` user; secrets are in the service environment (install.sh:186,752,
    update.sh:184-190,799). Build as a separate user; systemd `LoadCredential`/tight EnvironmentFile handling if it can
    be done without changing how the server reads its settings; `ProtectProc=invisible`.
A4. install.sh runs root code from the bms-owned /opt/bms/source and extracts a bms-controlled tar as root
    (install.sh:594-619,643,1099). Use the root-owned mirror/tree as update.sh does; reuse `copy_dist_as_bms`.
A5. Nginx (install.sh:817-1046, update.sh:318-326, domains-sync.sh:130-190): `client_max_body_size 0` on `/modules`
    lets anyone fill memory/disk before authentication. Small default (e.g. 2m) everywhere; unlimited/large only on the
    exact authenticated upload locations (find them: CRM attachments, company files, package uploads, imports) with
    `proxy_request_buffering off` there; `limit_req`/`limit_conn` zones; catch-all `default_server` that rejects unknown
    hosts (`ssl_reject_handshake on`); no HSTS over plain HTTP; `X-Forwarded-Host` set in every proxy block; a
    `Content-Security-Policy` for the web app that still lets the Compose/wasm app run (test it); update.sh must
    rewrite the existing live config.
A6. systemd: add CapabilityBoundingSet=, PrivateDevices, ProtectKernel*, ProtectControlGroups, RestrictAddressFamilies,
    RestrictNamespaces, RestrictSUIDSGID, LockPersonality, SystemCallFilter=@system-service, MemoryMax/TasksMax to
    bms.service; sandbox the root helper units as far as they still work.
A7. update.sh order and safety (update.sh:786-880): build and test first; then `pg_dump` of control + tenant databases
    (kept N last, root-only dir); then migrate; automatic rollback (binaries + restart; database restore only offered,
    not automatic) when the health check fails. Prune `server.env.update-backup.*` (keep 3, mode 600).
A8. Backups: a daily root timer doing pg_dump of all BMS databases + company-files + /etc/bms into a root-only
    directory with retention, plus documented optional off-box copy (rclone/rsync target from a root-only config).
    Document master-key escrow in SELF_HOSTING.md.
A9. Low: SCRAM verifier on the psql command line (install.sh:636,665) → stdin; /var/log/bms-domains.log mode 640;
    Let's Encrypt order throttling in domains-sync.sh (per-domain attempt backoff, cap new orders per hour);
    REVOKE CONNECT on postgres/template1 from PUBLIC is NOT wanted (breaks tools) — instead document; optional
    `--harden-host` step that installs ufw (22/80/443), fail2ban and unattended-upgrades, off by default, never
    enabling ufw without allowing the current SSH port first.
Tests: extend scripts/ubuntu/tests; run them against the local PostgreSQL 16; shellcheck-clean.

## Package B — Core authentication and server (server/src/main/kotlin/.../server/** except packages C owns)
B1. Company sign-in has no per-account throttle and the per-address limit is bypassed with IPv6
    (Application.kt:96-103, AuthenticationService.kt:13): per (company, login) throttle with backoff like the server
    sign-in already has; group IPv6 addresses by /64.
B2. Login timing reveals users/companies (DummyIterations=120_000 vs 600_000 in ApplicationUserRepository.kt:128;
    ScopedLoginService.kt:18-21): equalise work for unknown user, unknown company, disabled user.
B3. Two-step verification (TOTP, RFC 6238) for the server owner, server administrators, business owners and users:
    optional per account, enrolment with secret + otpauth URI + 10 one-time recovery codes (stored hashed), secret
    stored encrypted with the master-key envelope already used for secrets; sign-in becomes two steps when enabled;
    server owner can require it for server administrators; password recovery by emailed code (AccountService.kt:334)
    must not bypass it: with 2FA on, recovery needs TOTP or a recovery code; for the server owner without 2FA keep
    email recovery but notify. Agents/OAuth sign-in (ModuleAccessService) must enforce it too. Activity log / server
    audit entries for enable, disable, recovery-code use. Define the HTTP contract first and write it to
    `TWO_FACTOR.md` (the app package builds on it). Throttle code attempts.
B4. Backup import read fully into memory with no limits (CompanyRoutes.kt:285-290): streaming with size and entry-count
    limits.
B5. OAuth sign-in does not record the client address (ModuleAccessService.kt:273).
B6. Unknown-company webhook answers reveal which companies use an integration (ExtensionRegistry.kt:137-147): same
    answer and timing for unknown company / not installed.
B7. `/version` and `GET /modules` disclose versions without sign-in: keep what the app needs before sign-in
    (compatibility check) and nothing else.
B8. Module jars not re-verified at load (Application.kt:725-745): store SHA-256 at install, check path is under the
    modules root and the hash matches before loading (existing rows: record the hash on first start after update).
B9. Upload quotas: per-company storage quota setting (server owner sets default and per company; default generous),
    free-disk-space floor check before accepting uploads, cap on pending uploads (CompanyFiles.kt, CrmAttachments.kt:22
    limit stays configurable). Core part here; CRM part in package D uses your API — write the API first.
B10. Global safety net: a Ktor-level default request-body limit for routes that do not declare otherwise (so a missing
    Nginx limit is not fatal), with explicit opt-out on the upload routes.
B11. In-app version catalog over plain http (VersionCatalogClient.kt:15): https only.

## Package C — signed updates and packages (core-update.sh, mirror.sh, ModulePackages.kt, RemoteModuleCatalog.kt,
##             ExtensionPackage* signature code, ServerPermission.kt, CompanyProvisioningService catalog-URL part)
C1. Core self-update runs whatever origin/master HEAD is (core-update.sh:184-210, mirror.sh:8-35). The request must
    carry the exact commit SHA the owner approved (shown in the app with the version); the helper checks out exactly
    that SHA, verifies it is an ancestor-reachable commit of the configured ref, and — when a signing key is installed
    in root-owned /usr/local/lib/bms/allowed_signers — requires a valid SSH signature (`git verify-commit` /
    `verify-tag`) and refuses otherwise. Without a key installed: works as today plus the SHA pin, and the app shows
    "sin firma". `server.core.update` becomes a high-risk permission.
C2. Module/extension packages: signature gate is off and the bundled public key is empty
    (Application.kt:223, ModulePackages.kt:114,200-208, resources/extensions/bms-2026.pub). Design so that: the catalog
    is pinned to a commit SHA (not a mutable branch) and the package download uses that SHA; the public key is read
    from a root-owned file on the server (fallback: bundled resource) so a key can be installed without a rebuild;
    `MODULE_SIGNATURE_ENFORCED` stays configurable, default remains off until a key exists, and the app keeps the
    explicit "autorizar paquete sin firma" step (already high-risk). Provide `scripts/dev/sign-release.sh` (run on the
    owner's own computer, key never leaves it) that signs commits/tags or the package manifests, and document the
    whole procedure in SIGNING.md. Do not generate or commit any private key.
C3. Catalog SSRF (RemoteModuleCatalog.kt:73,140,171-172, CompanyProvisioningService.kt:1280): https only, validated and
    pinned resolution like ExtensionUrlDownloader/PinnedHost.

## Package D — modules and extensions (modules/**, extensions/**)
D1. HIGH CRM XLSX import: crafted column index exhausts memory (CrmImportParse.kt:141-145): bound columns, rows, cell
    and shared-string sizes, decompressed size (zip bomb) — check the other importers (clientes, tareas, etc.) and
    every extension importer for the same pattern.
D2. WordPress site check: SSRF / DNS rebinding (CrmWordPress.kt:85-115): use PinnedHost; request replay within five
    minutes (CrmWordPress.kt:51,68-74): remember seen signatures/nonces for the window.
D3. CRM downloads use the uploader's content type (CrmServerModule.kt:341-349): `X-Content-Type-Options: nosniff`,
    `Content-Disposition: attachment` for types that are not a safe inline list, sanitised content type.
D4. Meta diagnostics log floodable (CrmMeta.kt:203-211): cap and rate-limit; unauthenticated handlers that read the whole
    body (AgentesServerModule.kt:193-194, AgentOAuth.kt:186,252,365,468, MetaServerExtension.kt:116,121,149 and any
    other): bounded reads (e.g. 1 MB, 64 KB for OAuth forms).
D5. Hotspot public form (HotspotPortal.kt:232-290, 715-730, :131): do not create the customer before the address is
    verified; per-address and per-destination-email send limits; limits keyed so that a third party cannot lock out a
    guest (per device/session rather than global per email only); `my_wifi_code` is declared read-only but writes
    (HotspotMcpTools.kt:165-171): declare it correctly.
D6. Tareas (TareasService.kt:83,124): check that the linked customer is visible to the user and that the group belongs
    to the board.
D7. Agentes OAuth client registration open redirect (AgentOAuth.kt:224-232): exact-match registered redirect URIs,
    https or loopback only, no wildcards; error redirects only to a validated registered URI.
D8. CRM attachments use the Core quota API from B9 once available (coordinate through the lead; if it is not ready,
    leave a clearly marked call site and say so).

## Package E — build and CI supply chain (.github/**, gradle/**, settings.gradle.kts)
E1. `distributionSha256Sum` for the Gradle wrapper; remove the dead JetBrains Space repository; decide the daemon JVM
    criteria so no JDK is auto-downloaded on the server (document in SELF_HOSTING.md).
E2. Dependency verification (`gradle/verification-metadata.xml`): it cannot be generated in this container (Android
    plugin unreachable). Add a manually triggered CI job that generates it and uploads it as an artifact, and document
    the steps to adopt it; do not enable verification blindly.
E3. Workflows: pin every action by commit SHA (comment with the tag); `permissions:` least privilege per job;
    `persist-credentials: false`; web-dist release only from master pushes (never PRs or other branches) and signed
    (cosign keyless or minisign — pick the one update.sh can verify offline with a public key in a root-owned path;
    coordinate the verification snippet for update.sh with package A through the lead: write it in your report);
    add dependabot.yml (gradle, github-actions).

## App package (after B3): two-step verification screens (enrol, sign-in second step, recovery codes, disable),
## storage-quota settings, "sin firma"/commit shown in the Core update dialog. Validated by CI only.

## Not code (operator): SSH root password + key-only login; GitHub two-factor and branch protection on master.
