# RESTRUCTURE_PLAN.md: behaviour-preserving restructuring of BMS

Branch: `refactor/structure` @ `5880604` (worktree `/home/user/wt/restructure`). I measured everything below on that tree with `wc -l`/`grep`. Core is `0.14.0`, app is `0.12.0`.

---

## 0. Executor ground rules (every step refers to these)

**Conventions.** Follow GLOBAL_AGENT.md:
- Commits: author and committer `alexcalbri`, Spanish conventional messages such as `refactor(servidor): …`, no trailers, no push. The owner pushes for CI.
- Never commit the test helper's temporary edits to `build.gradle.kts` or `gradle/gradle-daemon-jvm.properties`.
- One mechanical move per commit. Each commit must compile and pass V-unit.

**Verification commands**
- **V-unit**: `/home/user/tools/bms-test.sh <wt> "<filter>"`
- **V-full** (server, with PostgreSQL). `P` goes on its own line:
  ```
  P='jdbc:postgresql://127.0.0.1:54329/postgres?user=postgres'
  export BMS_TEST_PG_URL=$P BMS_ACTIVITY_PG_URL=$P BMS_SERVER_ADMINS_PG_URL=$P BMS_CRM_ACTIVITY_PG_URL=$P BMS_OWNER_GROUP_PG_URL=$P BMS_OUTBOUND_PG_URL=$P
  GRADLE_OPTS=-Dorg.gradle.caching=false /home/user/tools/bms-test.sh <wt>
  ```
  The result must be `failures=0 errors=0`, and `tests=` must equal the baseline recorded in A0.0 plus any tests added. PostgreSQL tests skip silently without the variables.
- **V-noext**: the same run without extensions on the classpath, which is what CI does today. Run the helper's steps but drop `-I /home/user/tools/ext.init.gradle.kts`. After A0.1 this is simply the in-repo CI command.
- **V-scripts**: `BMS_SCRIPTS_PG=127.0.0.1:54329 bash scripts/ubuntu/tests/run.sh`
- **V-app**: the owner pushes; GitHub Actions `build.yml` jobs "Compilacion de la app (JVM, JS, Android)" and "Distribucion web de produccion" must be green. The app cannot be compiled in the container.
- **V-bin**: `scripts/dev/binary-compat.sh <baseline-commit>` (added in A0.3). No `com.ideasdeveloper.bms.server.*` or `…modules.*` class referenced by baseline-compiled optional modules or extensions may be missing.

**Rules**
- **R1 – Frozen SDK.** Never move, rename or change the JVM signature of anything in `server/src/test/resources/sdk/released-surface.txt` (A0.2). That includes default arguments and the types used in public member signatures. A type that appears in a frozen class's public signature is frozen too (for example `StorageUsage` via `ModuleFiles.usage()`).
- **R2 – Same-package file splits.** Moving a class, object or interface to another file in the same package is binary-safe. A top-level `fun`, `val` or `const` compiles into the facade `<File>Kt`. If code outside Core (optional modules or extensions) references it, it stays in its original file. Do not use `@file:JvmName` tricks.
- **R3 – Logger names.** Extracted code keeps the original logger: `LoggerFactory.getLogger(<OriginalClass>::class.java)`, or the same string (for example `"ModulePackages"`). Logger names show up in `/server/logs`.
- **R4 – i18n.** The namespace is decided only by the prefixes `com.ideasdeveloper.bms.modules.<id>.` and `com.ideasdeveloper.bms.extensions.<id>.` (`I18n.namespaceOfClassName`). Every `com.ideasdeveloper.bms.server.*` package is namespace `core`, so Core-to-Core moves never change catalogs, and module sub-packages keep the module namespace. Never split or rename `i18n/I18n.kt` or `i18n/Localization.kt`: their facades `I18nKt` and `LocalizationKt` are hard-coded in `I18n.Machinery`. Moving code across the module↔extension boundary (Part C only) requires moving catalog entries.
- **R5 – Collaborator pattern for splitting a class.**
  - The new `internal class X(...)` is constructed inside the facade, from the facade's own constructor parameters. The facade's constructor is unchanged.
  - Every public member stays on the facade with the same signature (default arguments included) and becomes a one-line delegation.
  - Shared mutable state (executors, maps, caches, `installStates`, `failedModuleMigrations`, …) exists exactly once and moves into exactly one collaborator.
  - Order of side effects is unchanged.
- **R6 – Visibility.** Widen `private` to `internal` only when needed. Never make anything `public`.
- **R7 – Routes.** Route paths, methods, `ServerRoutePolicies` and declaration order stay unchanged.
- **R8 – No edits to released module code in Parts A/B.** No file under `modules/*/server/src/main` or `extensions/*/server/src/main` changes. Exception: the bundled modules `clientes` and `empresa` compile with the server, but no step needs them. Module and extension `src/test` files may change: release-to-master strips them and the server compiles only `src/main`.
- **R9 – App moves.**
  - Copy the whole import block into each new file (unused imports are only warnings; no warnings-as-errors is configured).
  - For every package change, grep each moved top-level symbol across `app/` and add the import in every referencing file.
  - Widen `private` to `internal` for anything used across files.
- **R10 – Reflection check.** Before moving a class, grep it for `Class.forName`, `::class.qualifiedName`, `::class.java.name` and `simpleName`. Only `ServerModuleProvider`/`ServerExtensionProvider` (ServiceLoader names) were found, and they are frozen.

---

## 1. Findings that change the scope or the order

**F1 – Installed packages are not rebuilt when Core updates.** Module and extension jars are compiled once, at install or update, against the Core running at that moment (`modules/README.md` §install; `ModulePackages.kt:192`). After a Core update they are loaded as they are: "a package that cannot be loaded after a core update is skipped… and must be installed again". JVM linking is lazy, so a moved class surfaces as `NoClassDefFoundError` inside a request handler, not at load time.
- `typealias` and forwarding shims only give source compatibility, never binary compatibility, so they do not solve this.
- Consequence: every Core FQN referenced from `src/main` of the 10 optional modules and 15 extensions is frozen. That is **123 distinct FQNs**: `server.modules` 59, `outbound` 15, `extensions` 13, `activity` 13, `outbound.monday` 11, `security` 4, `auth.application` 3, `net` 2, `company.application` 1 (`ServerWorkspace`), `auth.infrastructure.security` 1 (`SessionTokenGenerator`), `auth.infrastructure.database` 1 (`SessionOpener`, used inline in agentes).
- This shrinks A3 (`ServerWorkspace` cannot leave `company.application`) and A4 (most storage, mail, notification and timeline types are SDK types and must stay in `server.modules`).

**F2 – File splits are safe.** Splitting files inside the same package is binary-safe for classes (R2). Only these top-level declarations reached from modules or extensions are pinned to their file:
- `requireModuleAccess` (17 module files) and `requireModulePermission` in `ModuleAccessService.kt`
- `tr`/`msg`/`trIn` in `I18n.kt`
- `localizedPayload`/`localizedFor` in `Localization.kt`
- `runGuarded` in `ModuleJobs.kt`
- `ServerMcpModuleId` in `McpTools.kt`
- `CoreModuleId` in `ActivityRoutes.kt`
- `ExtensionConnectionPermission` in `IntegrationConnections.kt`

This is why every A2 split is Core-only, with no module bumps.

**F3 – CI never compiles extensions.** `server/build.gradle.kts` has no extension source set. Only the local helper's init script (`/home/user/tools/ext.init.gradle.kts`) adds `extensions/*/server/src/{main,test}` to the test source set. Today a Core move can break all 15 extensions with CI green. A0.1 therefore comes first.

**F4 – A1 has more copies than listed, and the Core "public base" is not a drop-in.**
- Bounded body readers: **7** – CRM `CrmExtensions.readBody`, meta `MetaBodies.readText`, agentes `readBoundedText`, plus inline copies in `ComprasServerModule.kt:268`, `EventosRoutes.kt:72`, `HotspotGuestCodes.kt:432` and `MembresiaRoutes.kt:495`. Core has none (it relies on the `RequestBodyLimit` plugin).
- Client IP: 3 implementations – Core `app.clientAddress`, agentes `clientIp`, hotspot `clientIp`. `ClientAddresses.group` is not a duplicate: it builds the IPv6 /64 rate-limit key from the address and is complementary.
- Hotspot has its own /64 grouping, `HotspotGuestCodes.ipGroup`, with a **different, persisted format** (the `hotspot_ip_hits.ip_group` primary key, V012). It must not be unified.
- Core `publicBase(call, knownHost)` only accepts hosts in `public_domains`. Those are per-company module domains, so the server's main domain is not in that table. Core returns `null` on the main domain unless `BMS_PUBLIC_BASE_URL` is set. The module copies always return a URL. The canonical helper therefore needs two explicit policies (§3).
- Two more raw header reads exist: agentes `sameOrigin` (`X-Forwarded-Host`) and Core `ModuleRoutes.kt:71` (Secure cookie from `X-Forwarded-Proto`).

**F5 – Measured sizes differ from the brief.**
- `Application.kt` is 1,033 lines (not ~960); `module()` runs from line 97 to 717.
- `ExtensionService.kt` is 1,102 lines.
- `App.kt` is 532 lines, below the proposed limit. Its `NavHost` lambdas capture local state, so I do not split it (§7).

**F6 – `ActivityLog`.** Only one class is called `ActivityLog`: the object in `server/activity/ActivityLog.kt`, which is frozen (17 module and 9 extension main files). The clash is the file `core/src/commonMain/kotlin/com/ideasdeveloper/bms/ActivityLog.kt`. It has no top-level functions, so no facade class, and it holds `ActivityPermissionIds`, `ActivityLogRow`, … Resolution: rename that file. No API changes.

**F7 – Extensions go beyond the "stable host surface".** They import **~85 CRM symbols**, not just `CrmHost`/`CrmExtensions`: `CrmScreens` (17 files), `CrmAudit` (16), `CrmActor` (15), `CrmRepository` (4), `CrmImport*`, `CrmFields`, … The CRM surface has to be narrowed before provider code can leave.

**F8 – A CRM breaking change would fail silently today.**
- Extensions cannot declare `minCoreVersion`.
- Compatibility is only `minModuleVersion <= moduleVersion`, so there is no upper bound.
- `ExtensionPackageLoader.load` catches `Exception`, not `LinkageError`.

Part C therefore needs a Core prerequisite (C0).

**F9 – Thresholds.** hotspot (22 files, 6,997 lines) and mantenimiento (20 files, 6,794 lines) are below the sub-package threshold I propose (30 files or 10,000 lines). Only crm (64 files, 19,921 lines) qualifies.

**F10 – Other names that cannot change.**
- Logger names surface in `/server/logs` (`RecentServerErrors` stores `loggerName`).
- `logback.xml` names `server.app.RecentServerErrorsAppender`.
- `application.conf` names `server.app.ApplicationKt.module`.
- `server/build.gradle.kts` names `mainClass ApplicationKt` and the ServiceLoader file `…server.modules.ServerModuleProvider`.
- The `ServerRouteMatrixTest` mounts `companyRoutes(resolver, provisioning = null, selfService = null, coreUpdates = null)`, `storageRoutes(...)`, etc. Keep those signatures.
- `update.sh` greps for `"PostgreSQL connection verified"` (`Application.kt:593`). Keep that line inside `module()`.
- Serialization needs no care: the only polymorphic serializers are content-based (`UiNodeSerializer`, `UiActionSerializer`), so no class names appear in JSON.

### Files in scope: size and fan-in (number of files referencing the class)

| File | Lines | server main/test | modules main/test | extensions main/test | app |
|---|---|---|---|---|---|
| company/application/CompanyProvisioningService.kt | 2,828 | 7 / 7 | 0 / 1 (agentes) | 0 / 0 | – |
| company/application/CompanySelfService.kt | 1,400 | 6 / 2 | 0 / 0 | 0 / 0 | – |
| auth/infrastructure/database/LoginDatabases.kt | 1,383 | 9 / 13 | 0 / 3 | 0 / 1 | – |
| modules/ModuleAccessService.kt | 1,345 | 21 / 10 | **49** / 6 | **9** / 1 | – |
| company/api/CompanyRoutes.kt (`companyRoutes`) | 1,110 | 2 / 2 | 0 | 0 | – |
| extensions/ExtensionService.kt | 1,102 | 7 / 3 | 0 | 0 | – |
| app/Application.kt | 1,033 | (entry) | 0 | 0 | – |
| auth/application/AccountService.kt | 843 | 6 / 3 | 0 | 0 | – |
| company/application/CoreUpdateService.kt (12 types) | 409 | 3 / 3 | 0 | 0 | – |
| company/application/ServerMcpTools.kt | 289 | 2 / 1 | 0 / 1 | 0 | – |
| company/application/ServerOwnerAuthorizer.kt | 95 | 3 / 2 | 0 / 1 | 0 | – |
| auth/application/ClientAddresses.kt | 31 | 3 / 1 | 0 | 0 | – |
| storage impl (StorageGuard, StorageQuotas, CompanyFiles, FileBackend, LocalFileBackend, formatBytes) | 484 (2 files) | 6 / 5 | 0 | 0 / 1 (monday test) | – |
| storage SDK (ModuleFiles, StoredFileRef, FileTooLargeException, StorageQuotaException, StorageUsage) | – | – | **11+9+6+5** | 4 | – |
| JdbcNotificationStore + NotificationRoutes.kt | 135 | 1 / 0 | 0 | 0 | – |
| company/api/StorageRoutes.kt | 103 | 1 / 1 (matrix) | 0 | 0 | – |
| app NativeModuleRenderer.kt | 1,711 | – | – | – | 2 (ModuleNode etc.) |
| app CompanyProvisioningScreen.kt | 1,155 | – | – | – | 2 |
| app CompanyApi.kt | 951 | – | – | – | 17 |
| app ServerDrivenModuleScreen.kt | 776 | – | – | – | 2 |
| app Server{Update,Logs,ModuleManagement,Staff}Screen.kt | 418/–/581/237 | – | – | – | 1 each (App.kt) |
| app ModuleAuthorizeUnsignedDialog.kt | – | – | – | – | 2 (incl. ExtensionPackagesSection) |

Kotlin `main` files over 800 lines: 26 today (list in §5). Over 600 lines: 59. Test files over 1,000 lines: none.

---

## 2. Target layout

### Server (`server/src/main/kotlin/com/ideasdeveloper/bms/server/`)
```
app/                      Application.kt (main, module – FQN fixed), HttpPlugins.kt, StartupModules.kt,
                          VersionEndpoint.kt, ServerSettings.kt, ServerOwnerNotices.kt,
                          RecentServerErrors.kt (FQN fixed), RequestBodyLimits.kt, VersionCatalogClient.kt, VersionModels.kt
http/          NEW (SDK)  ClientAddresses.kt, PublicOrigin.kt, BoundedBody.kt
platform/      NEW        server-level operations that do not belong to a company
  coreupdate/             CoreUpdateService.kt, CoreUpdateStore.kt, CoreRelease.kt, CoreUpdateRoutes.kt
  mcp/                    ServerMcpTools.kt
  ServerInfoRoutes.kt     GET /server/logs, PUT /server/name
admins/                   + ServerOwnerAuthorizer.kt
auth/api/                 unchanged
auth/application/         AccountService.kt, AccountRules.kt, DatabaseLoginScope.kt, CompanySwitcher.kt, … (ClientAddresses leaves)
auth/infrastructure/database/  LoginDatabases.kt (pools, scope registry, session-opener queries), LoginDatabaseTypes.kt
company/api/              CompanyRoutes.kt (wrapper, same signature), CompanySelfRoutes.kt, CompanyAdminRoutes.kt,
                          BusinessOwnerRoutes.kt, CompanyRouteSupport.kt, DTO files (unchanged)
company/application/      CompanyProvisioningService.kt (company lifecycle facade), CompanyDuplication.kt, CompanyRegistryQueries.kt,
                          BusinessOwnerAccounts.kt, TenantSchemaProvisioner.kt, ProvisioningSql.kt,
                          ServerWorkspace.kt (frozen), ServerWorkspaceProvisioner.kt,
                          CompanySelfService.kt, CompanyBackups.kt, CompanyUserAdministration.kt, CompanyPermissionCatalog.kt,
                          CompanyLinks.kt, CompanyProvisioningConfig.kt, ProvisioningAudit.kt
modules/                  = module SDK (frozen) + module infrastructure
                          ModuleAccessService.kt (class + top-level requireModuleAccess/requireModulePermission/customerEventVisibility/
                          AlwaysEnabledModules/fallbackActor), ModulePermissions.kt, ModuleAccessTypes.kt, ModuleAgentSignIn.kt,
                          ModuleCustomerAccess.kt, ModuleFiles.kt (ModuleFiles, StoredFileRef, FileTooLargeException),
                          ModuleStorage.kt (StorageUsage, StorageQuotaException), MailSender.kt, CompanyMailSettings.kt,
                          Notifications.kt (service + SDK types), CustomerTimeline.kt, CustomerReferences.kt, McpTools.kt, ModuleJobs.kt,
                          ModulePackages.kt, ServerModule.kt, … (unchanged)
  install/     NEW        ServerModuleInstaller.kt, ServerModuleRoutes.kt
storage/       NEW        StorageGuard.kt, StorageQuotas.kt, CompanyFiles.kt (FileBackend, LocalFileBackend, CompanyFiles), StorageRoutes.kt
notifications/ NEW        JdbcNotificationStore.kt, NotificationRoutes.kt
extensions/               ExtensionService.kt, ExtensionServiceTypes.kt, ExtensionSchemaSync.kt, ExtensionGroupOperations.kt, packages/ (unchanged)
activity/ assistant/ i18n/ infrastructure/ net/ outbound/ security/   unchanged
```

### App (`app/shared/src/commonMain/kotlin/com/ideasdeveloper/bms/`)
```
App.kt                    imports only
app/features/
  company/data/           CompanyApi.kt (company endpoints only), company models
  company/ui/             CompanyProvisioningScreen.kt, CompanyModulesRow.kt, BusinessOwnerFields.kt, LinkCompanyDialog.kt, (rest unchanged)
  server/      NEW        data/ServerApi.kt (extension functions on CompanyApi), data/ServerModels.kt
                          ui/ServerUpdateScreen.kt, ServerLogsScreen.kt, ServerModuleManagementScreen.kt, ServerStaffScreen.kt,
                             ModuleAuthorizeUnsignedDialog.kt
  modules/ui/             ServerDrivenModuleScreen.kt (generic only), NativeModuleRenderer.kt (core of renderer), ModuleContentNodes.kt,
                          ModuleFormRenderer.kt, ModuleFieldRenderer.kt, ModuleBoardRenderer.kt, ModuleHosts.kt
  customers/   NEW        ui/CustomerModuleContent.kt
  serveradmin/            unchanged (administrators, audit)
core/src/commonMain/kotlin/com/ideasdeveloper/bms/ActivityLogContracts.kt   (renamed from ActivityLog.kt)
```

---

## 3. A1 – canonical helpers (new package `server.http`, part of the SDK, additive in Core 0.15.0)

```kotlin
object ClientAddresses {                       // moved from auth.application (Core-only users)
    val LoopbackProxies = setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
    fun resolve(remoteAddress: String, realIpHeader: String?): String   // body of today's app.clientAddress, byte-identical
    fun of(call: ApplicationCall): String = resolve(call.request.local.remoteAddress, call.request.headers["X-Real-IP"])
    fun group(address: String): String         // unchanged (IPv6 /64 rate-limit key)
}
object PublicOrigin {
    fun configured(): String?                  // today's configuredPublicBase (BMS_PUBLIC_BASE_URL)
    fun scheme(call): String                   // X-Forwarded-Proto only from LoopbackProxies, http|https; else local.scheme
    fun requestHost(call): String?             // Host header, trimmed, lowercased, ≤255, [a-z0-9.-]+(:port); never X-Forwarded-Host
    fun forLinks(call, knownHost: (String) -> Boolean): String?   // today's app.publicBase: configured, or allow-listed host, else null
    fun forRequest(call): String               // configured ?: "${scheme}://${requestHost ?: local.serverHost}" (URLs returned to the same client)
    internal fun resolve(...)                  // pure function behind all of the above, table-tested
}
// BoundedBody.kt
suspend fun ApplicationCall.receiveBoundedBytes(maxBytes: Int): ByteArray?   // null if Content-Length > max or body > max; reads ≤ max+1
suspend fun ApplicationCall.receiveBoundedText(maxBytes: Int): String?
suspend fun ApplicationCall.receiveBoundedBytesOr413(maxBytes: Int): ByteArray?   // responds 413 itself, returns null
suspend fun ApplicationCall.receiveBoundedTextOr413(maxBytes: Int): String?
fun formOf(text: String, limit: Int): Parameters                             // '+' is space; at most `limit` pairs
```

### Behaviour per call site

"Nginx" means the shipped `nginx-site.sh`/`domains-sync.sh`. They set `Host $host`, `X-Forwarded-Host $host`, `X-Forwarded-Proto $scheme` and `X-Real-IP $remote_addr`; `$host` is lowercased; Ktor listens on 127.0.0.1; no `XForwardedHeaders` plugin is installed, so `request.origin == request.local`.

| # | Call site | Today | Canonical | Difference / resolution | When |
|---|---|---|---|---|---|
| C1 | `app.clientAddress` ← AuthRoutes:138,209,227,245; Application rate-limit keys 115–152; ModuleAccessService:282,329 | loopback ∈ 3 forms → X-Real-IP trimmed, take(64), chars [letter/digit . :] else remote | `ClientAddresses.of` | **identical** (code moved) | A1.1 |
| C2 | `ClientAddresses.group` ← LoginDatabases:92,94; Application | IPv6 /64 grouping | same object, new package | **identical** | A1.1 |
| C3 | agentes `ApplicationCall.clientIp()` (AgentesHttp.kt:24) ← AgentOAuth:140,271,287,302 | only `127.0.0.1`; X-Real-IP if non-blank, no trim, no cap, no charset | `ClientAddresses.of` | Differs only for (a) a proxy on `::1` and (b) malformed or oversized X-Real-IP, which today becomes a limiter key or a recorded origin. Identical behind Nginx. **Adopt.** | M1 (agentes 0.8.0) |
| C4 | hotspot `clientIp()` (HotspotPortal.kt:109) ← :174,201,214 | only `127.0.0.1`; non-blank, take(64) | `ClientAddresses.of` | Same as C3. **Adopt.** `HotspotGuestCodes.ipGroup` is **not** replaced (persisted format). | M2 (hotspot 0.16.0) |
| P1 | `app.publicBase` ← AuthRoutes:209 (email recovery link) | configured, else allow-listed Host, else null | `PublicOrigin.forLinks` | **identical** (code moved; ReviewFixesTest already covers it) | A1.2 |
| P2 | agentes `publicBase()` ← AgentOAuth:155 (OAuth metadata), :374 (`iss`), AgentesServerModule:504 (WWW-Authenticate), :527 (MCP URL shown) | configured; proto from `127.0.0.1` only; Host if no whitespace, `/` or `@` (not lowercased); fallback `origin.serverHost`; never null | `PublicOrigin.forRequest` | (a) proto is also trusted from `::1`; (b) Host must match `[a-z0-9.-]+(:port)` and is lowercased, otherwise falls back to serverHost. Identical behind Nginx. **Adopt.** | M1 |
| P3 | agentes `sameOrigin()` (AgentesServerModule:497) | `X-Forwarded-Host ?: Host ?: serverHost`, from anyone | `PublicOrigin.requestHost(call) ?: serverHost` | Ignores a client-sent X-Forwarded-Host when Ktor is reached without Nginx (stricter CSRF check). Identical behind Nginx. **Adopt.** | M1 |
| P4 | CRM `CrmExtensions.publicBase(call)` ← meta MetaServerExtension:124 (data-deletion status URL) | proto and host from `X-Forwarded-*` **from anyone**, then Host, then serverHost; **ignores BMS_PUBLIC_BASE_URL** | body becomes `= PublicOrigin.forRequest(call)`; the **function stays** (frozen, meta uses it) | (a) configured base now wins; (b) X-Forwarded-Host is no longer read; (c) proto only from loopback; (d) Host validated. Behind Nginx only (a) is visible, when the variable is set. **Adopt** (an explicitly configured base is the owner's declaration; links on it still route to this server). | C1 (crm 0.34.0) |
| P5 | CRM private `ApplicationCall.publicBase()` (CrmServerModule:1250) ← :409, :434 (webhook URLs in Integraciones) | same as P4 | delete; call `PublicOrigin.forRequest` | same as P4 | C1 |
| P6 | Core `ModuleRoutes.kt:71` Secure cookie | `X-Forwarded-Proto == "https"` from anyone | **unchanged** | Trusting the header can only add `Secure` (fail-safe); changing it would affect direct-HTTP access. Documented exception. | – |
| B1 | CRM `CrmExtensions.readBody` (1 MB) ← meta:106 | 413 if declared > max; reads ≤ max+1; 413 if over; returns bytes | body becomes `receiveBoundedBytesOr413(MaxWebhookBytes)`; **function stays** (frozen) | **identical** | C1 |
| B2 | meta `MetaBodies.readText/readForm/form` (64 KB, form limit 100) | same semantics, String | `receiveBoundedTextOr413`, `formOf(…,100)` | **identical**; `MetaBodies` deleted | C1-meta (meta 1.4.0, minModuleVersion 0.34.0) |
| B3 | agentes `readBoundedText/readBoundedForm/formOf` (limit 1000) | null without responding; caller responds 413 | `receiveBoundedText`, `formOf(…,1000)` | **identical** | M1 |
| B4 | compras :268, eventos :72, hotspot :432, membresia :495 inline `readNBytes(max+1)` | per site: some check Content-Length first, eventos/membresia use `receiveStream` | `receiveBoundedBytes`/`Text` | Same status and response. Sites without a Content-Length pre-check now refuse before reading. Characterisation tests assert the status for oversize declared and undeclared bodies. | M3 |

**Characterisation tests come first** (each in the commit before the switch):
- Core: A1.1 and A1.2 first extract the pure `resolve(...)` from the existing code without changes, then add table tests covering the loopback branch. Ktor's test engine reports a non-loopback remote, so that branch is only reachable through the pure function.
- Modules: in `modules/<id>/server/src/test`, add `testApplication` tests that pin today's outputs for the header matrix: non-loopback remote, with and without X-Real-IP, X-Forwarded-Host, X-Forwarded-Proto, missing Host, uppercase Host, oversize body. The switch commit then edits only the rows listed as "Difference" above, so the diff states the behaviour change.

---

## 4. Ordered steps for Parts A and B

S = Sonnet, H = Haiku. "∥" means the steps can run in separate worktrees (`/home/user/wt/<id>`) created from the batch's start commit. Merge them in the listed order. They touch disjoint files.

### Batch A0 – safety nets and rules (sequential, one worktree)

| ID | Goal / exact change | Risks | Verify | Model |
|---|---|---|---|---|
| A0.0 | Record the baselines: V-full `tests=` count, V-noext count, current SHA. Write them into the PR description. | – | V-full, V-noext | H |
| A0.1 | Make CI compile and test extensions. Copy `/home/user/tools/ext.init.gradle.kts` to `gradle/ci/with-extensions.init.gradle.kts`. Add a job `server-extensions` to `.github/workflows/build.yml` that runs `./gradlew -I gradle/ci/with-extensions.init.gradle.kts :server:test --continue`. Keep the existing `server` job (no-extensions configuration). | Extension tests may not pass in CI today; if not, fix the tests, not production code. Do not touch the root build. | V-full + V-noext; owner push → both jobs green | S |
| A0.2 | Add `server/src/test/kotlin/…/server/repo/SdkSurfaceTest.kt` and snapshot `server/src/test/resources/sdk/released-surface.txt` (design in §5.3). Generate the snapshot from optional modules (module.json without `bundled:true`) and extensions at A0.0's SHA. | Top-level function resolution must go through the facade classes (scan compiled classes of the package). | V-unit `*SdkSurface*`; deliberately rename a frozen class locally → test must fail | S |
| A0.3 | Add `scripts/dev/binary-compat.sh <baseline-ref>`. Steps: (1) `git worktree add` the baseline; build `:server:modulesClasses :server:testClasses` with the init script → baseline module classes (`server/build/classes/kotlin/modules`) and extension classes (`com/ideasdeveloper/bms/extensions/**` from the test output). (2) Build the current `:server:installDist`. (3) Run `jdeps --multi-release 21 --missing-deps -cp "<new lib/*>:<baseline module classes>" <baseline module classes> <baseline extension classes>`. (4) Exit non-zero if any `com.ideasdeveloper.bms.(server|modules)` class is missing. | Class-level only; member-level breaks are caught by A0.2 + R1 review. | run against HEAD → clean; shellcheck clean | S (H runs it afterwards) |
| A0.4 | **Part B.** Add `SourceLayoutTest` + `server/src/test/resources/source-layout/allowlist.txt` (initial content §5.4). Add the ARCHITECTURE.md §16 text (§5.1) and a pointer in §13. | No behaviour change. | V-unit `*SourceLayout*`; add a 801-line dummy file locally → fails | S |
| A0.5 | **A5.** `git mv core/src/commonMain/kotlin/com/ideasdeveloper/bms/ActivityLog.kt …/ActivityLogContracts.kt`. No content change. | None: no top-level functions, no facade; importers (empresa ×2, server ×6 main, 1 test) import classes, not the file. | V-full; V-app in the APP-1 batch | H |
| **End A0** | | | V-full, V-noext, V-scripts, owner push (CI incl. the new job) | H |

### Batch A1 – canonical helpers in Core (sequential, one worktree)

| ID | Change | Risks | Verify | Model |
|---|---|---|---|---|
| A1.1 | (a) Move `auth/application/ClientAddresses.kt` → `http/ClientAddresses.kt` (package `server.http`); fix imports in LoginDatabases.kt, Application.kt (FQN at 115–152) and `TwoFactorUnitTest`. (b) Move the body of `app.clientAddress` to `ClientAddresses.resolve/of`; delete `app.clientAddress`. Update AuthRoutes (4 calls) and ModuleAccessService:282,329 (inline FQN → `ClientAddresses.of(call)`). (c) Add `ClientAddressesTest` (table over `resolve`). | `clientAddress` is `internal`: no module users (verified). Keep `group` byte-identical. | V-unit `*TwoFactor*`, `*ClientAddresses*`, `*ReviewFixes*`; V-full | S |
| A1.2 | Move `publicBase` + `configuredPublicBase` from Application.kt to `http/PublicOrigin.kt` as `forLinks`/`configured`, via the pure `resolve`. Add `scheme`, `requestHost`, `forRequest`. Update AuthRoutes:209, Application:546,739 and ReviewFixesTest imports. Add a `PublicOriginTest` table: loopback × proto × Host × configured × knownHost, for both policies. | `coreUpdateRequiresApprovedCommit` stays in `app` (moved in A2.7). | V-unit `*ReviewFixes*`, `*PublicOrigin*`; V-full | S |
| A1.3 | Add `http/BoundedBody.kt` (additive, unused by Core) + `BoundedBodyTest` (declared over limit, chunked over limit, exact limit, empty). Add the 3 files to `released-surface.txt` once a module adopts them (M1). | none | V-unit | S |
| **End A1** | | | V-full, V-noext, V-bin vs A0.0 SHA | H |

### Batch A2 – splitting oversized server files (∥ worktrees W1…W7, after A1 is merged)

All steps use R5. Line numbers refer to SHA `5880604`; executors confirm them with `grep -n`. Each lettered sub-step is one commit.

| ID ∥ | File → destination: what moves | Risks | Verify | Model |
|---|---|---|---|---|
| **A2.1 (W1)** CompanyProvisioningService.kt (2,828 → ≤800) | | | | S |
| a | → `company/application/ProvisioningSql.kt` (internal top-level): `Connection.exists`, `existsDatabase`, `existsRole`, `quoteIdentifier`, `quoteLiteral`, `dropDatabaseForced`, `terminateSessions`, `Connection.setProvisioningRole`, `sha256`×2 (≈2670–2765). `inCentralTransaction` and `insertAudit` stay (they use `central`). | SQL quoting must stay byte-identical. | V-unit `*Provisioning*` | |
| b | → `TenantSchemaProvisioner` (internal class): `tenantConfig`, `adminConnection`, `tenantAdminConnection`, `applyTenantCoreMigrations`, `applyAssistant/TwoFactor/ActivityLog/Language/CompanyMail/Extensions/Notifications/UserProfile/AccountThrottle/OutboundMigration`, `revokeIfPresent`, `grantIfMissing`, `grantColumnsIfMissing`, `runAsTableOwner`, `grantSequenceIfMissing`, `applyBaseModuleMigrations`, `applyModuleMigrations`, `migrationSql`, `applyMigration`, `seedBusinessOwner`, `grantTenantPermissions`, `grantRuntimeDefaults`, `grantProvisioningConnect` (1935–2414), plus `applyExtensionMigrations` (2615–2648; the facade delegates). | Migration-path string literals must stay (dist_test.sh scans the whole tree, so the location does not matter). | V-full (ProvisioningRolesPostgresTest, ActivityIntegrityPostgresTest) | |
| c | → `modules/install/ServerModuleInstaller.kt`: `listServerModules`, `moduleResponse`, `serverModuleCatalog`, `updateServerModuleCatalog`, `installServerModule`, `inspectServerModule`, `authorizeUnsignedServerModule`, `activateLoadedModule`, `setServerModuleUsage` (1205–1469 minus `updateServerName`); `moduleMigrationFailed`, `applyPendingModuleMigrations`, `firstServerOwnerRole`, `buildAndInstall`, `finishBuild`, `moduleInstallPending`, `resumePendingInstalls`, `dropQueuedInstall`, `deleteServerModule`, `ensureLockedServerModulesPersisted`, `syncRemoteAvailableModules`, `moduleCatalogUrl`, `installedPackage`, `upsertServerModule` (1578–1904); fields `installStates`, `buildExecutor`, `moduleCatalogSettingKey`, `failedModuleMigrations`; private `InstallStatus`, `ModuleInstallState` (2775–end). It receives `TenantSchemaProvisioner` for `applyModuleMigrations`. | Single build executor and state (R5). Logger = `CompanyProvisioningService` (R3). | V-full (ServerAdminsPostgresTest, PackageInstallQueueTest) | |
| d | → `BusinessOwnerAccounts`: 732–930 (`resetBusinessOwnerPassword` … `updateBusinessOwner`, `centralLoginTaken`, `loginNameTaken`, `companiesSharingOwner`, `ownerGroupOf`, `roleUsedIn*`, `silenceStatementLog`, `businessOwner`) and 1054–1120 (`updateBusinessOwnerBySelf`, `ownerOf`, `companyTenants`, `ownerRoleIn`, `serverOwnerRole`). | `silenceStatementLog` (passwords never logged) must be preserved. | V-full | |
| e | → `ServerWorkspaceProvisioner`: `ensureServerWorkspace`, `serverWorkspaceRow`, `serverWorkspaceModules`, `rejectServerWorkspace` (1470–1577). The `ServerWorkspace` object is **not** moved (frozen). | | V-full | |
| f | → `CompanyDuplication`: `duplicateCompany`, `ownerGroup` (404–594). | | V-full | |
| g | → `CompanyRegistryQueries`: `loadCompanyModules`, `countCompaniesWithModuleEnabled`, `tenantRuntimeConnection`, `insertCompany`, `findCompany`, `findCompanyAnyState` (2415–2544). Then measure: the facade must be ≤800, otherwise move `withCompanyActivity`/`recordOnTenant`/`actorName`/`previousLogo` (2545–2606) to `CompanyActivityRecorder`. | | V-full; `wc -l` | |
| **A2.2 (W2)** CompanySelfService.kt (1,400) | a → `CompanyBackups`: backup methods 190–308, `backupDirectory`/`validateBackupName` 1086–1123, `BackupManifest` + `object BackupImportLimits` 1296–1400 (same package). b → `CompanyPermissionCatalog`: 909–1077 (`syncPermissionCatalog` … `existsUser`). c → `CompanyUserAdministration`: 358–757 (`disableUserTwoFactor`, `resetAuthority`, `users`, `createUser`, `updateUserPermissions`, `updateUser`) and 1170–1295 (`recordStaffCredentials`, `isInactiveUser`, `staffRowIsStale`, `deactivateWorkspaceStaffOf`). The facade keeps profile, mail, `authorize*`, `audited/logged/record`. | `BackupImportLimits` is used by 2 main files and `BackupImportLimitsTest` (same package, no import changes). | V-unit `*Backup*`, `*Company*`; V-full | S |
| **A2.3 (W3)** LoginDatabases.kt (1,383) | a → `LoginDatabaseTypes.kt` (same package): `TenantDatabaseConfig` (55), `SessionSecondFactor`, `SessionOpener` (**frozen FQN, same package OK**), `SwitchNeedsSecondFactorException`, `ServerPerson` (1352–1383). b → `auth/application/DatabaseLoginScope.kt`: the `private inner class Scope` (797–1271) becomes `internal class DatabaseLoginScope(private val owner: LoginDatabases, …) : LoginScope`, with every outer reference qualified as `owner.x`; outer members it needs become `internal`; `profile`, `serverWorkspace`, `visibleModules` (1272–1337) move with it. c → `auth/application/CompanySwitcher.kt`: `switchCompany`, `trackCompanySession`, `activeAdmin`, `switcher`, `serverSwitcher`, `switchTargets`, `switchTo`, `logout`, `companies`, `activeDatabase` (389–550, 659–750); public delegates stay on `LoginDatabases`. The session-opener queries (551–658) stay. | (b) is the riskiest split: inner → top-level class; rely on the compiler; identical login, lock-out and timing (`spendSignInWork`). Module tests (agentes, clientes, tareas) and the tareas_monday test keep compiling unchanged (the class name stays). | V-full (TwoFactor*, ActivityLog*, PublicDisclosureTest, ProfileLanguageTest) | S |
| **A2.4 (W4)** ModuleAccessService.kt (1,345) | a → `ModulePermissions.kt` (`ModulePermissions`, `UserModuleAccess`) and `ModuleAccessTypes.kt` (`OwnerGroupScope`, `CompanyLinkException`, `UserIdentity`, `AgentSignIn`, `VerifiedUser`, `ServerAuditAgent`, `ActivityReaderLookup`, `UserSessionLookup`, `ModulePermissionLookup`, `ModuleAccessDecision`, `ModuleAccessErrorResponse`, `ModuleUser`), all in package `server.modules`. **Keep in ModuleAccessService.kt:** the class, `CustomersModuleId`, `AlwaysEnabledModules`, `fallbackActor`, `requireModuleAccess`, `requireModulePermission`, `customerEventVisibility` (facade `ModuleAccessServiceKt` is frozen). b → internal `ModuleAgentSignIn` (274–394). c → internal `ModuleCustomerAccess` (`customerReferences` 83–121; 520–680). Every public member stays, with signatures unchanged. | 49 module + 9 extension main files depend on member signatures (R1). | V-full; V-bin; SdkSurfaceTest | S |
| **A2.5 (W5)** ExtensionService.kt (1,102) | a → `ExtensionServiceTypes.kt`: `ExtensionNotifier` interface + factory function, `ExtensionMigrationRunner`, `ExtensionActor`, `ServerAdminScope` (32–105; Core-only users, same package). b → internal `ExtensionSchemaSync` (295–462). c → internal `ExtensionGroupOperations` (463–632, 774–946); `notifyBusinessOwner` stays shared. | | V-unit `*Extension*`; V-full | S |
| **A2.6 (W5)** AccountService.kt (843) | → `AccountRules.kt`: `AccountException`, `EmailChangeRule`, `PasswordConfirmation`, `PasswordAttemptLimiter`, `AccountProfile`, `AccountPasswords` (39–171). | none | V-unit `*Account*` | S |
| **A2.7 (W6)** Application.kt (1,033) | → `app/HttpPlugins.kt` `internal fun Application.installHttpPlugins()` = lines 99–155 (ContentNegotiation, RequestBodyLimit, RateLimit); `app/StartupModules.kt` = 887–1000 (keep `getLogger("ModulePackages")`); `app/VersionEndpoint.kt` = 770–886; `app/ServerSettings.kt` = `coreUpdateRequiresApprovedCommit`, `serverMailSettings`, `companyProvisioningConfig`; `app/ServerOwnerNotices.kt` = `notifyServerOwners`. `main` and `module()` stay, with the call order unchanged and the "PostgreSQL connection verified" line in place. ReviewFixesTest import of `coreUpdateRequiresApprovedCommit` stays valid (same package). | Plugin install order must be identical. | V-full; start `:server:run` locally and check the log line | S |
| **A2.8 (W7)** CompanyRoutes.kt (1,110) | Keep `fun Route.companyRoutes(...)` (same signature) calling, **in this order**: `companySelfRoutes` (54–367 → `company/api/CompanySelfRoutes.kt`), `companyListRoute` (368–386 → CompanyAdminRoutes.kt), `serverInfoRoutes` (387–392 `/server/logs`, 935–950 `/server/name` → `platform/ServerInfoRoutes.kt`), `serverModuleRoutes` (393–612 → `modules/install/ServerModuleRoutes.kt`), `companyAdminRoutes` (613–900 → CompanyAdminRoutes.kt), `coreUpdateRoutes` (901–934 → `platform/coreupdate/CoreUpdateRoutes.kt`), `businessOwnerRoutes` (951–1065 → BusinessOwnerRoutes.kt). Private helpers 1066–1110 → `company/api/CompanyRouteSupport.kt` (internal). The `/server/logs` route sits between `/companies` and `/server/modules` today: keep it in that position inside the wrapper. | ServerRouteMatrixTest enumerates the tree; ServerRoutePolicies unchanged. | V-unit `*ServerRouteMatrix*`; V-full | S |
| **End A2** | Merge W1…W7; H: tighten the allow-list (strict mode). | | V-full, V-noext, V-bin, SdkSurfaceTest, V-scripts | H |

### Batch A3 – server-level code out of `company` (sequential, after A2)

| ID | Change | Fan-in to fix | Verify | Model |
|---|---|---|---|---|
| A3.1 | `company/application/CoreUpdateService.kt` → `platform/coreupdate/`: `CoreUpdateService.kt` (DTOs 22–99 + service 100–257), `CoreUpdateStore.kt` (`JdbcCoreUpdateStore` 258–344), `CoreRelease.kt` (`CoreRelease`, `GitHubCoreCommits` 345–409). Move tests `CoreUpdateServiceTest`, `CoreUpdateStorePostgresTest`, `CoreUpdateHelperShellTest` → `server/src/test/…/platform/coreupdate/`. | Application.kt, CoreUpdateRoutes.kt, ServerMcpTools.kt (if used); ReadOnlyToolsPostgresTest | V-unit `*CoreUpdate*`; V-full | S |
| A3.2 | `ServerMcpTools.kt` → `platform/mcp/`; test `ServerMcpToolsTest` → `platform/mcp`. | Application.kt; `modules/agentes/server/src/test/…/AdminAuditAgentPostgresTest.kt` import | V-full | S |
| A3.3 | `ServerOwnerAuthorizer.kt` (with `Connection.existsBySingleString`) → `admins/`. | activity/ServerAuditMcpTools.kt, app/Application.kt, CompanySelfService.kt; TwoFactorPostgresTest, ServerAdminsPostgresTest, agentes AdminAuditAgentPostgresTest | V-full | S |
| A3.x | **Not moved:** `company/application/ServerWorkspace.kt` (frozen: hotspot HotspotAccess/HotspotPortal, agentes ×3). Add a KDoc line: "server-level; stays here for binary compatibility (RESTRUCTURE_PLAN §7)". | – | – | H |

### Batch A4 – split the `modules` grab-bag (sequential, after A3)

| ID | Change | Stays in `server.modules` (frozen SDK) | Verify | Model |
|---|---|---|---|---|
| A4.1 | `CompanyStorage.kt` and `CompanyFiles.kt` → `storage/StorageGuard.kt`, `storage/StorageQuotas.kt` (+ `formatBytes`), `storage/CompanyFiles.kt` (`FileBackend`, `LocalFileBackend`, `CompanyFiles`, private `LimitedInputStream`). Keep in modules: `ModuleFiles.kt` (`ModuleFiles`, `StoredFileRef`, `FileTooLargeException`) and `ModuleStorage.kt` (`StorageUsage`, `StorageQuotaException`). | ModuleFiles' internal constructor takes `CompanyFiles` (internal, OK); `usage(): StorageUsage` keeps `StorageUsage` frozen. | V-unit `*Storage*`, `*CompanyFiles*`, `*Upload*`; V-full; V-bin. Update imports in Application, ModuleAccessService, CompanyProvisioningService/Self, server tests and `extensions/monday/server/src/test/…/MondayActivityLogTest.kt`. | S |
| A4.2 | `Notifications.kt`: `JdbcNotificationStore` (internal) → `notifications/JdbcNotificationStore.kt`. `NotificationRoutes.kt` (routes + 4 response DTOs) → `notifications/`. | `NotificationService`, `NotificationStore`, `NewNotification`, `StoredNotification`, `NotificationPage`, `NotificationTarget`, `ModuleNotifier`, `NotificationText` | V-full | S |
| A4.3 | `company/api/StorageRoutes.kt` → `storage/StorageRoutes.kt`. | – | ServerRouteMatrixTest import; V-full | S |
| A4.4 | Mail (`MailSender`, `MailHostGuard`, `SmtpSettings`, `MailException`, `CompanyMail*`), customer timeline/references, `SettingsCopy`/`SettingsTable`, `TenantPools`, `PackageInstallQueue`, McpTools, ModuleJobs: **no move**. Either frozen or genuine module infrastructure (§7). | | – | – |
| **End A4** | H: update docs that name moved files (COMPANY_PROVISIONING_FLOW.md lines 198–408, PROGRAM_FLOW.md:56, STORAGE_QUOTA.md, SERVER_ADMINS.md, I18N.md:229 if affected) and ARCHITECTURE.md §13 backend tree. Tighten the allow-list. | | V-full, V-noext, V-bin, V-scripts | H |

### App batches (sequential within each batch; each batch ends with one CI run)

**APP batch 1** (one branch; APP-1, APP-2, APP-4 and APP-5 touch disjoint files and can be done in any order):

| ID | Change | Risks | Model |
|---|---|---|---|
| APP-1 | `features/company/ui/{ServerUpdateScreen,ServerLogsScreen,ServerModuleManagementScreen,ServerStaffScreen,ModuleAuthorizeUnsignedDialog}.kt` → `features/server/ui/` (package `…app.features.server.ui`). Imports: App.kt (4 screens), `features/extensions/ui/ExtensionPackagesSection.kt` (dialog), and ServerStaffScreen adds `features.company.ui.CompanyProfileFields` and `CompanyUsersSection`. The private `LoginResponse.withWorkspace` copies stay per file. | Same-package uses were checked by grep (only those listed). | S |
| APP-2 | NativeModuleRenderer.kt (1,711) split, same package `features.modules.ui`. The file keeps lines 1–339 (ModuleColors, ModuleRenderContext, ModuleNode, children/flow helpers, NoticeBanner, Text, Row, ResponsiveGrid). New files: `ModuleContentNodes.kt` (Card, Image, optionLabel, Metric, BarChart, Table, Button 340–512; Badge/Badges/ShowMore 1166–1202; List, Timeline, formatValue 1652–1711); `ModuleFormRenderer.kt` (Form, SuggestingField, TriggerToken, findTriggerToken, TriggerSuggestingField 513–811); `ModuleFieldRenderer.kt` (FilesField, formatBytes, ModuleField 812–1165); `ModuleBoardRenderer.kt` (1203–1651). Cross-file `private` → `internal`. | Two private `formatBytes`/`formatValue` helpers must not collide once internal: keep them private in their own files. | S |
| APP-4 | CompanyProvisioningScreen.kt (1,155): `CompanyModulesRow` (733–966) → `CompanyModulesRow.kt`; `BusinessOwnerFields` (967–1118) → `BusinessOwnerFields.kt`; `LinkCompanyDialog` (1119–1155) → `LinkCompanyDialog.kt`; same package, private → internal. | | S |
| APP-5 | ServerDrivenModuleScreen.kt: `CustomerServerDrivenContent`, `EmailStatusBadge`, `EmailStateFilter`, `CustomerRow`, `CustomerProfileDialog` (358–776), plus `customerFieldTypes` if only they use it → `features/customers/ui/CustomerModuleContent.kt`. ServerDrivenModuleScreen imports `CustomerServerDrivenContent`. The new file imports what it uses from `features.modules.ui` (ModuleColors, ModuleNode, …). | Hidden same-package uses: grep every top-level name of `features/modules/ui/*.kt` inside the new file. | S |
| End | Owner pushes; V-app green. A failure is fixed only by imports or visibility. | | H (reads CI log) |

**APP batch 2** (after batch 1 is green):

| ID | Change | Model |
|---|---|---|
| APP-3 | CompanyApi.kt (951): the server endpoints (`listServerModules`, `serverModuleCatalog`, `updateServerModuleCatalog`, `deleteServerModule`, `installServerModule`, `inspectServerModule`, `authorizeUnsignedServerModule`, `createServerWorkspace`, `setServerModuleUsage`, `updateServerName`, `serverLogs`, `coreUpdateStatus`, `requestCoreUpdate`, `serverStorage`, `updateDefaultStorageQuota`) become `suspend fun CompanyApi.<same>(…)` in `features/server/data/ServerApi.kt`, with identical bodies. Same HttpClient, so behaviour is identical. Helpers they use (`client`, base URL, `call`, `send`, error mapping) become `internal`. Server DTOs (`ServerLogsResponse`, `ServerErrorLogEntry`, `CoreUpdateStatusResponse`, `CoreUpdateRequestDto`, `ServerStorageResponse`, `ServerModuleResponse`, `ServerModuleCatalogResponse`, `ServerWorkspaceResponse`, `ServerNameResponse`, `ModulePackageInspection` if app-local) → `features/server/data/ServerModels.kt`. Grep every moved name across `app/` and add imports, including the extension-function imports at each call site. JSON is unaffected (property names). | S |

### Release step R-A (after all of A and B; H)
- Bump Core to `0.15.0`: `server/build.gradle.kts:8`, `app/VersionModels.kt:6` `BMSVersion`, and `version.json` `latestCoreVersion`.
- No module or extension version changes in this release (R8).
- App code changed only structurally; it ships with the next app release, so no forced app bump.
- Final checks: V-full, V-noext, V-bin vs A0.0, V-scripts, V-app.

### Batch M – modules adopt the A1 helpers (after Core 0.15.0 is on the catalog; ∥ per module; S)

| ID | Module | Change (table §3) | Version |
|---|---|---|---|
| M1 | agentes | C3, P2, P3, B3. Delete the `AgentesHttp.kt` helpers (keep `MaxOAuthBodyBytes`). Characterisation tests first. | 0.7.3 → 0.8.0, minCoreVersion 0.15.0 |
| M2 | hotspot | C4 (keep `ipGroup`), B4 (:432) | 0.15.2 → 0.16.0, minCoreVersion 0.15.0 |
| M3 | compras, eventos, membresia | B4 | patch bump each, minCoreVersion 0.15.0 |

CRM (P4, P5, B1) and meta (B2) adopt in C1.

Compatibility: a server on Core 0.14 refuses these versions (`ModuleRequirementGate`) and keeps the old jars, which still work because Core 0.15 keeps every frozen symbol. Add `server.http.*` to `released-surface.txt` in M1.

---

## 5. Part B – rules text, automated check, initial allow-list

### 5.1 Text to add to ARCHITECTURE.md (new §16, before "Documentation Status")

```markdown
## 16. Code organisation

### 16.1 File size
- A Kotlin source file in `src/main` (server, Core, modules, extensions, app) has at most **800 lines**;
  aim for **≤ 400**. Test files have at most **1,000 lines**. Rationale: 800 lines is what a reviewer or a
  coding agent can read and hold in one pass; above it every BMS file mixes responsibilities (26 files did on
  2026-11-28, none of them below 840 lines without at least two unrelated flows).
- A function or composable should stay under ~150 lines; `Application.module()` is the known exception.
- Exceptions live only in `server/src/test/resources/source-layout/allowlist.txt`
  (`file <maxLines> <path>  # reason`). An entry records the current size; it may only go down and is
  removed as soon as the file is ≤ 800 lines. Adding an entry needs the owner's approval in the PR.

### 16.2 One responsibility per file
- A file holds one responsibility named by its file name: one service, one store, one set of routes for one
  resource, one screen or one family of renderer nodes. Data/DTO files may group the models of one feature.
- A class that grows past the limit is split with the collaborator pattern: internal classes built by the
  original class, which keeps its public members as delegations so callers and binary compatibility are kept.

### 16.3 Packages and sub-packages
- Server Core packages are named by function (`auth`, `company`, `platform`, `storage`, `notifications`,
  `extensions`, `http`, …). Server-level operations that do not belong to one company live in `platform`.
- A Core package with more than **25 files** must be split by function.
- A module or extension whose `src/main` passes **30 files or 10,000 lines** uses sub-packages by function
  under `com.ideasdeveloper.bms.modules.<id>` (for example `.screens`, `.mcp`, `.automation`, `.importing`,
  `.integrations`). Its root package keeps the module entry (`<Id>Module`, `<Id>ServerModule`,
  `<Id>ServerModuleProvider`) and the surface its extensions compile against. Sub-packages do not change the
  i18n namespace (I18N.md §2).

### 16.4 Shared helpers
- A helper needed by more than one package lives once in Core: HTTP request helpers in `server.http`
  (client address, public origin, bounded bodies), secrets in `server.security`, outbound HTTP in `server.net`.
- Modules and extensions use these helpers instead of copies. A copy found in a module is replaced in the
  module's next release (with `minCoreVersion` raised to the Core that provides the helper).

### 16.5 The module SDK and binary compatibility
- Installed module and extension packages are compiled once and are not rebuilt when Core is updated. Every
  public declaration that a released optional module or extension references (listed in
  `server/src/test/resources/sdk/released-surface.txt`) is frozen: its fully qualified name, its JVM
  signature and, for top-level functions, the file that declares it. `typealias` does not preserve binary
  compatibility.
- `server.modules` is the module SDK plus module infrastructure; `server.http`, `server.i18n`,
  `server.activity`, `server.extensions` (contracts), `server.outbound`, `server.security` and `server.net`
  are also SDK packages. Implementation-only classes do not belong in SDK packages.
- The same rule applies to a module's root package for its extensions (CRM: `CrmHost`, `CrmExtensions`,
  `CrmScreenProvider`, …).
- Removing or moving a frozen symbol is a breaking release: a new Core with a compatibility baseline, the
  dependants updated in the same catalog publication, and `minCoreVersion`/`minModuleVersion` raised.

### 16.6 Automated checks
- `SourceLayoutTest` (server test suite, runs in CI) enforces 16.1 and 16.3.
- `SdkSurfaceTest` fails when a frozen symbol no longer resolves, or when a module/extension starts using a
  Core symbol that is not yet in the frozen list (adding it is an explicit decision).
- CI compiles and tests the server with and without the extensions on the classpath
  (`gradle/ci/with-extensions.init.gradle.kts`).
- `scripts/dev/binary-compat.sh <ref>` checks, before a release, that packages built at `<ref>` still link
  against the new Core.
```

Also edit §13: replace the sentence "The backend source is organized under …" with a pointer to the §2 tree of this plan once A is done.

### 5.2 `SourceLayoutTest` design
- Location: `server/src/test/kotlin/com/ideasdeveloper/bms/server/repo/SourceLayoutTest.kt`. Repo root found with the same logic as `I18nCatalogCheck.repoRoot()`.
- Scans `server/src`, `core/src`, `app/*/src`, `modules/*/server/src`, `extensions/*/server/src`; `*.kt` only; skips `/build/`. Lines = physical lines, as `wc -l` counts them.
- Classification: a path containing `/src/test/` or `/src/<name>Test/` is a test file (limit 1,000); otherwise main (limit 800).
- Package check: Core directories under `server/src/main/kotlin/…/server/**` may hold at most 25 `.kt` files each. The root package directory of each module or extension may hold at most 30.
- Allow-list entries: `file <max> <path>` and `package <maxFiles> <dir>`, plus a `# reason` comment.
- Failures (always):
  - an unlisted file or package over its limit;
  - a listed one larger than its recorded value;
  - a listed path that no longer exists;
  - more entries than `MaxEntries` (a constant in the test, initially 27, which reviewers only ever lower).
- Ratchet: with `-Dbms.layout.strict=true` the test also fails when a listed item is at or below the limit ("remove entry") or below its recorded value ("lower to N"). Normal CI runs lenient, so parallel steps do not conflict on the allow-list. Every batch-end H step runs strict and tightens the file.

### 5.3 `SdkSurfaceTest` design
- Location: `server/src/test/kotlin/com/ideasdeveloper/bms/server/repo/SdkSurfaceTest.kt`.
- Snapshot: `server/src/test/resources/sdk/released-surface.txt`, one FQN per line, sorted. Header comment: "entries are removed only in a breaking release (ARCHITECTURE §16.5)".
- Reference extraction: scan `src/main/kotlin` of optional modules (`module.json` without `"bundled": true`) and of all extensions. Collect every `com\.ideasdeveloper\.bms\.server\.[a-z0-9_.]+\.[A-Za-z_]\w*` occurrence, from imports and inline FQNs alike.
- Test 1: every current reference is in the snapshot; otherwise fail with "add it deliberately: it becomes frozen".
- Test 2: every snapshot entry resolves:
  - `Class.forName`, retrying with `$` for nested classes;
  - otherwise a top-level declaration: some `…Kt` class of that package (classes listed from the test classpath directory) declares a static method `name`, `get<Name>` or a field `name`.
  - Otherwise fail with "binary break for installed packages".

### 5.4 Initial allow-list (lines as of 2026-11-28; the step that removes each entry in brackets)
```
file 2828 server/src/main/kotlin/com/ideasdeveloper/bms/server/company/application/CompanyProvisioningService.kt   # A2.1
file 1711 app/shared/src/commonMain/kotlin/com/ideasdeveloper/bms/app/features/modules/ui/NativeModuleRenderer.kt   # APP-2
file 1445 modules/mantenimiento/server/src/main/kotlin/com/ideasdeveloper/bms/modules/mantenimiento/MantenimientoRepository.kt   # module owner
file 1426 modules/clientes/server/src/main/kotlin/com/ideasdeveloper/bms/modules/clientes/ClientesLinking.kt   # module owner (bundled)
file 1400 server/src/main/kotlin/com/ideasdeveloper/bms/server/company/application/CompanySelfService.kt   # A2.2
file 1383 server/src/main/kotlin/com/ideasdeveloper/bms/server/auth/infrastructure/database/LoginDatabases.kt   # A2.3
file 1345 server/src/main/kotlin/com/ideasdeveloper/bms/server/modules/ModuleAccessService.kt   # A2.4
file 1321 modules/crm/server/src/main/kotlin/com/ideasdeveloper/bms/modules/crm/CrmScreens.kt   # C1
file 1287 modules/crm/server/src/main/kotlin/com/ideasdeveloper/bms/modules/crm/CrmServerModule.kt   # C1
file 1155 app/shared/src/commonMain/kotlin/com/ideasdeveloper/bms/app/features/company/ui/CompanyProvisioningScreen.kt   # APP-4
file 1110 server/src/main/kotlin/com/ideasdeveloper/bms/server/company/api/CompanyRoutes.kt   # A2.8
file 1102 server/src/main/kotlin/com/ideasdeveloper/bms/server/extensions/ExtensionService.kt   # A2.5
file 1098 modules/crm/server/src/main/kotlin/com/ideasdeveloper/bms/modules/crm/CrmRepository.kt   # C1/C2
file 1033 server/src/main/kotlin/com/ideasdeveloper/bms/server/app/Application.kt   # A2.7
file 974 modules/crm/server/src/main/kotlin/com/ideasdeveloper/bms/modules/crm/CrmIntegrationMcpTools.kt   # C2
file 951 app/shared/src/commonMain/kotlin/com/ideasdeveloper/bms/app/features/company/data/CompanyApi.kt   # APP-3
file 931 modules/hotspot/server/src/main/kotlin/com/ideasdeveloper/bms/modules/hotspot/HotspotPortal.kt   # module owner
file 923 modules/tareas/server/src/main/kotlin/com/ideasdeveloper/bms/modules/tareas/TareasRepository.kt   # module owner
file 919 modules/mantenimiento/server/src/main/kotlin/com/ideasdeveloper/bms/modules/mantenimiento/MantenimientoScreens.kt   # module owner
file 905 extensions/inmobiliaria/server/src/main/kotlin/com/ideasdeveloper/bms/extensions/inmobiliaria/InmobiliariaStore.kt   # extension owner
file 870 modules/membresia/server/src/main/kotlin/com/ideasdeveloper/bms/modules/membresia/MembresiaRepository.kt   # module owner
file 864 modules/crm/server/src/main/kotlin/com/ideasdeveloper/bms/modules/crm/CrmMcpTools.kt   # C1
file 843 server/src/main/kotlin/com/ideasdeveloper/bms/server/auth/application/AccountService.kt   # A2.6
file 825 modules/compras/server/src/main/kotlin/com/ideasdeveloper/bms/modules/compras/ComprasRepository.kt   # module owner
file 817 modules/crm/server/src/main/kotlin/com/ideasdeveloper/bms/modules/crm/CrmImport.kt   # C1
file 813 modules/tareas/server/src/main/kotlin/com/ideasdeveloper/bms/modules/tareas/TareasScreens.kt   # module owner
package 64 modules/crm/server/src/main/kotlin/com/ideasdeveloper/bms/modules/crm   # C1/C2
```
That is 27 entries. After Part A, 11 file entries go away (`MaxEntries` → 16). Module-owned entries stay until their owners split them: same-package splits, binary-safe under R2, need a module version bump.

---

## 6. Part C – large modules and the CRM provider extraction (separate releases)

**Scope.** Only crm passes the threshold. hotspot (22 files) and mantenimiento (20) do not: their big files stay in the allow-list for same-package splits by the module owner.

### C0 – Core prerequisite release (Core 0.16.0; behaviour addition, so not part of A/B)
1. **Compatibility baseline.** `ModuleDefinition` and `module.json` get optional `extensionApiSince: "<version>"`. `ExtensionLifecycle.isCompatible(min, moduleVersion, baseline) = min <= moduleVersion && (baseline == null || min >= baseline)`. It is used by `markIncompatible`, install and enable checks, `ExtensionAccess` and the startup check. This works retroactively: already-installed old extensions (min 0.31.0) become `incompatible`, so their code does not run, and the business owner is notified through the existing path.
2. **Loader hardening.** `ExtensionPackageLoader.load` and provider creation catch `LinkageError` (log the error and skip the package) in addition to `Exception`.
3. **Re-enable.** Check that installing a compatible version restores the previous `enabled`/`disabled` state per company (EXTENSIONS.md says it does). Add a test.

Tests: lifecycle table, a fake extension under a baseline, and a jar with a missing class. Verify with V-full and V-scripts.

### C1 – CRM 0.34.0, non-breaking (minCoreVersion 0.15.0; no extension changes, so no re-authorisation)
- **A1 adoption:**
  - P4 and B1 become one-line bodies inside the **kept** `CrmExtensions.publicBase` and `CrmExtensions.readBody`.
  - P5 is deleted and its callers use `PublicOrigin.forRequest`.
  - meta 1.4.0 replaces `MetaBodies` with Core `receiveBounded*`. It needs `minModuleVersion 0.34.0`, because CRM 0.34.0 requires Core ≥0.15.0 and extensions cannot state `minCoreVersion`.
- **Sub-packages for internals only.** A file may move only if:
  - (a) none of its declarations is referenced by any extension `src/main` (the 33 files marked `<none>` in my scan); and
  - (b) none of its types appears in a public signature of a class extensions use. Check with a grep over `fun|val|class` lines of `CrmHost.kt`, `CrmExtensions.kt`, `CrmScreenProvider.kt`, `CrmModel.kt`, `CrmActions.kt`, `CrmItems.kt`, `CrmAudit.kt`, `CrmImportTarget.kt`, `CrmImportSource.kt`, `CrmFields.kt`, `CrmMail.kt`, `CrmInbox.kt`, `CrmMetaModels.kt`, `CrmCloudbedsModels.kt`, `CrmWordPressModels.kt`, `CrmEncuentra24Models.kt`.
  - Known keep-backs from (b): `CrmExit` (`CrmExitAutomation` in `CrmHost.loadExit`) and `CrmTemplateMailer` (`RenderedMail` in `CrmHost.renderFor`).

  Target layout:
  ```
  modules/crm/…/crm/                 root: CrmModule, CrmServerModule(+Provider), all extension-facing files (frozen until C2)
    screens/     CrmAdminScreens, CrmAutomationScreens, CrmExitScreens, CrmImportScreens, CrmButtons, CrmFieldSections*, CrmDownloads
    mcp/         CrmMcpTools, CrmSettingsMcpTools, CrmAutomationMcpTools
    automation/  CrmAutomationEngine*, CrmAutomationStore
    actions/     CrmBoardActions, CrmBulkActions
    people/      CrmAssignees, CrmMentions, CrmNotify*, CrmContacts
    customers/   CrmCustomerEvents, CrmCustomerTimeline, CrmLeadEvents
    mail/        CrmMailMigration, CrmSpam, CrmTranslate, CrmTexts
    security/    CrmSecrets
    stay/        CrmStay*
    routes/      route groups extracted from CrmServerModule (the object keeps `routes` and delegates)
  ```
  (*) means: check rule (b) first.
- **Body splits.** `CrmScreens`, `CrmRepository` and `CrmImport` stay in root with every member signature unchanged; their bodies delegate to internal objects in `screens/`, `leads/` and `importing/` (R5). `CrmIntegrationScreens`, `CrmIntegrationMcpTools`, `CrmWordPressScreens` and `CrmMetaChannels` wait for C2.
- **i18n.** None: sub-packages keep namespace `crm`.
- **Verify:**
  - extensions compile unchanged against the new CRM (A0.1 job);
  - V-bin with baseline = CRM 0.33.1, so the 0.33.1-built extension classes link against 0.34.0;
  - `I18nCatalogCheck.assertModule("crm")`, CrmActivityLogTest, V-full.
- **Release.** Bump crm to 0.34.0 in `module.json` and the definition. The package hash changes, so sign it or re-authorise it.

### C2 – CRM 0.40.0, breaking: finish the provider extraction (Core ≥0.16.0)
- **C2.0 (Opus, spec first).** Write `CRM_EXTENSION_API.md`. It defines the narrowed root surface:
  - `CrmHost`, `CrmExtensions`, `CrmScreenProvider` (+ a lead-block hook, a channel provider and an MCP-tool provider), contract models (`CrmLead`, `CrmUser`, `CrmActor`, `CrmItem`, `CrmField*`, `CrmTemplate`, `CrmInbound`/`CrmIntakeResult`, `CrmImport*` contract types, `CrmReservation` + `CrmReservations` because `crm_reservations` belongs to the CRM), `CrmValidationException`, `CrmAudit`/`CrmLeadAudit`;
  - new `CrmHost` facade methods replacing every extension use of `CrmScreens`, `CrmRepository`, `CrmImport`, `CrmImportParse` and `CrmFields` internals.

  Hard requirement: every UI screen id, action id, route, permission id and activity-log text keeps its exact value. If a moved screen id lacks the `<extensionId>_` prefix, the CRM keeps an alias that maps the old id to the provider. Saved app navigation and JSON contracts must not change.
- **Moves** (each extension's files under `extensions/<id>/server/src/main/kotlin/com/ideasdeveloper/bms/extensions/<id>/`):

  | From CRM | To |
  |---|---|
  | CrmCloudbedsScreens (221 lines, 62 `tr`), CrmCloudbedsModels (except `CrmReservation`), CrmCloudbedsStore, Cloudbeds parts of CrmIntegrationScreens and CrmIntegrationMcpTools, `CloudbedsProvider` + `CrmHost.cloudbeds*` | `extensions/cloudbeds` (implements the provider hooks) |
  | CrmWordPressScreens (214, 61 `tr`), CrmWordPressModels, WordPress parts of Integration screens/tools, `WordPressProvider` + `CrmHost.wordpress*` | `extensions/wordpress` |
  | CrmMetaModels, CrmMetaChannels (→ channel-provider hook), Meta parts, `MetaProvider` + `CrmHost.meta*` | `extensions/meta` |
  | CrmEncuentra24Models settings/sync/ads, `Encuentra24Provider` | `extensions/encuentra24`; ad-link recognition stays in CRM (`crm.channels`), as its comment requires |
  | CrmIntegrationScreens (379, 128 `tr`) | CRM keeps only the generic integrations hub listing extension sections |
  | `CrmModuleExtensions`, provider interfaces in CrmActions | removed |

  After this the CRM root holds about 16 files, and the remaining internals go to sub-packages (`leads/`, `importing/`, `inbox/`, `screens/`).
- **i18n.** Copy every moved `tr` literal (about 250) into `extensions/<id>/…/i18n/en.json`. Remove from `modules/crm/…/i18n/en.json` the keys the CRM no longer uses; `I18nCatalogCheck` fails on missing and on unused keys, so it enforces this. Run `AllCatalogsTest`.
- **Activity log.** Extensions keep recording through `CrmAudit`/`CrmHost`, so `module_id = crm` and the stored Spanish texts stay identical. Assert this with CrmActivityLogTest and the extension `*ActivityLogTest`s.
- **Data.** No migrations. `ext_<id>__*` and `crm_*` tables are unchanged.
- **Versions.**
  - crm `0.40.0`: `minCoreVersion 0.16.0`, `extensionApiSince 0.40.0`.
  - All 7 CRM extensions `2.0.0` with `minModuleVersion 0.40.0`: cloudbeds, encuentra24, inmobiliaria, inmobiliaria_monday, meta, monday, wordpress. The baseline makes every older CRM extension incompatible, so all seven are rebuilt and re-signed even where their code changes little.
- **Release sequence on a live server:**
  1. Install Core 0.16.0. Nothing changes yet.
  2. Publish CRM 0.40.0 and the 7 extensions in **one** catalog commit, signed with `scripts/extensions/sign.sh` (otherwise every hash must be re-authorised).
  3. The owner updates the CRM, ideally queueing the 7 extension updates in the same operation: `PackageInstallQueue` installs "modules first, then the extensions that waited for them" and restarts once.
  4. On CRM 0.40.0 load, the baseline marks the old extensions `incompatible` in every company. No old extension code runs, so there is no `NoClassDefFoundError`, and business owners are notified.
  5. Each extension update is rebuilt against CRM 0.40.0 and returns to its previous per-company state.

  Interim effect (unqueued updates): Meta/WordPress webhooks and integration screens are unavailable until the extensions are updated. Meta retries webhooks.
- **Edge cases:**
  - Updating an extension before the CRM is refused (`minModuleVersion 0.40.0 > installed`).
  - Updating the CRM on Core 0.15 is refused (`minCoreVersion`).
  - If the CRM build fails, the current CRM keeps running (existing rule). There is no downgrade path, so rehearse C2 first on a staging server with `scripts/ubuntu/rehearse.sh` plus a real extension rebuild.
- **Verify:** V-full with extensions, V-noext, V-bin against CRM 0.34.0 (it must report the expected breaks, and only in the 7 extensions), all catalog checks, V-scripts, V-app (screen JSON unchanged; app untouched).

---

## 7. What not to move, and why

- `app/Application.kt` `main` and `module()`: `application.conf` names `ApplicationKt.module`; `build.gradle.kts` names `mainClass`. Keep the "PostgreSQL connection verified" log line (`update.sh` health check).
- `app/RecentServerErrors.kt` `RecentServerErrorsAppender` (named in `logback.xml`).
- `i18n/I18n.kt` and `i18n/Localization.kt`: never split or rename (`I18n.Machinery` facade names; `tr`/`msg`/`trIn`/`localizedPayload`/`localizedFor` are used by every module).
- `ServerModuleProvider` and `ServerExtensionProvider` (ServiceLoader file names in `build.gradle.kts`, `ModulePackages`, `ExtensionPackageBuilder`).
- All 123 frozen SDK FQNs (R1), in particular:
  - `company.application.ServerWorkspace`;
  - `auth.infrastructure.database.SessionOpener`;
  - `auth.infrastructure.security.SessionTokenGenerator`;
  - the storage/mail/notification/timeline SDK types in `server.modules`;
  - `activity.ActivityLog` (hence the core *file* is renamed instead);
  - `outbound.*` and `outbound.monday.*`;
  - top-level functions in their files (F2).

  Wrongly placed frozen symbols stay where they are (owner question 3).
- `HotspotGuestCodes.ipGroup` (persisted `hotspot_ip_hits.ip_group` format).
- `ModuleRoutes.kt:71` Secure-cookie header read (fail-safe; documented in §3, P6).
- `App.kt` (532 lines, under the limit; `NavHost` lambdas capture state, so splitting without a compiler is risky).
- `Application.module()` wiring beyond the plugin block: a `ServerComponents` holder would be a redesign of startup ordering, not a move. Recorded as the known function-size exception.
- Duplicated app/server DTOs (`ServerModuleResponse`, … in both trees): consolidating them into `core/` changes the client build surface. Out of scope; candidate follow-up.
- Database schema, migrations, route paths, permission ids, i18n keys, activity texts, `ServerRoutePolicies`.
- Bundled-module and optional-module big files (ClientesLinking, MantenimientoRepository, …): module-owned, allow-listed.
- `BMSPublicKeys` (`ReviewFixesTest` attaches to its logger by class).

---

## 8. Open questions for the owner (everything else is decided above)

1. **Catalog branch of live servers.** Do live servers install optional modules and extensions from `develop`, where `src/test` is inside the package tree, or only from `master`/signed releases?
   - If `develop` with unsigned hash authorisation: the test-only edits in A3.2, A3.3 and A4.1 (agentes test, monday extension test) change those packages' hashes, and new installs need re-authorisation. Installed jars are unaffected and no version bump is needed.
   - I assume `master`/signed, which means no impact.
2. **C2 trade-off.** Do you accept a coordinated breaking release (CRM 0.40.0 plus 7 extensions at 2.0.0, re-signed, a short integration outage per server if updates are not queued together)? The alternative is to stop after C1 and keep the provider model types in the CRM root as permanent "host surface".
3. **Frozen Core symbols in the wrong packages** (`ServerWorkspace` in `company.application`, `SessionOpener` in `auth.infrastructure.database`, SDK types in `server.modules`): leave them there permanently (my default), or relocate them later? Relocating would need a Core-level `coreApiSince` baseline mirroring C0 and coordinated updates of agentes, hotspot and every module.

### Critical files for implementation
- /home/user/wt/restructure/server/src/main/kotlin/com/ideasdeveloper/bms/server/company/application/CompanyProvisioningService.kt
- /home/user/wt/restructure/server/src/main/kotlin/com/ideasdeveloper/bms/server/modules/ModuleAccessService.kt
- /home/user/wt/restructure/server/src/main/kotlin/com/ideasdeveloper/bms/server/app/Application.kt
- /home/user/wt/restructure/server/src/main/kotlin/com/ideasdeveloper/bms/server/i18n/I18n.kt
- /home/user/wt/restructure/server/build.gradle.kts
- /home/user/wt/restructure/modules/crm/server/src/main/kotlin/com/ideasdeveloper/bms/modules/crm/CrmHost.kt
- /home/user/wt/restructure/server/src/main/kotlin/com/ideasdeveloper/bms/server/extensions/packages/ExtensionPackageLoader.kt
- /home/user/tools/ext.init.gradle.kts (source for `gradle/ci/with-extensions.init.gradle.kts`)
