# Global agent ("Asistente") — work order (branch feature/global-agent)

Owner's request: the Core gets a built-in AI assistant that helps users inside the app, limited to the permissions the
user has. The server owner and each business owner decide whether people may use this global agent or only the
per-user agents of the Agentes module (which each user connects themselves through MCP/OAuth; unchanged).
Owner's decisions: chat inside the app; provider = Claude only (Anthropic API); the server owner sets one API key for
everybody and a business owner may set the company's own key; monthly usage limit per company.

## Rules (same as the rest of the project)
- Kotlin/Ktor server, control DB + one DB per company. Stored/user-facing text Spanish through tr() with English catalog
  entries (I18N_MODULE_GUIDE.md). Activity log per ACTIVITY_LOG_MODULE_GUIDE.md. New migrations only. No secrets in
  code, tests, logs or the activity log. Server routes registered in ServerRoutePolicies (fail-closed matrix test).
- Commits: author AND committer alexcalbri <164930758+alexcalbri@users.noreply.github.com>, Spanish conventional
  messages, no co-author/session trailers, no push. Never commit the test helper's edits to root build.gradle.kts or
  gradle/gradle-daemon-jvm.properties.
- Tests: /home/user/tools/bms-test.sh <worktree> ["*Filter*"]; for PostgreSQL set
  `P='jdbc:postgresql://127.0.0.1:54329/postgres?user=postgres'` ON ITS OWN LINE, then export it as BMS_TEST_PG_URL,
  BMS_ACTIVITY_PG_URL, BMS_SERVER_ADMINS_PG_URL, BMS_CRM_ACTIVITY_PG_URL, and run with
  GRADLE_OPTS=-Dorg.gradle.caching=false (otherwise PG tests silently do not run). Unique database names, clean up.
  If nothing listens on 54329: `su postgres -c "/usr/lib/postgresql/16/bin/pg_ctl -D /tmp/pgtest54329/data -o '-p 54329' -l /tmp/pgtest54329/log -w start"`.

## Claude API rules (binding — read the skill files before writing API code)
Skill directory: /tmp/claude-0/bundled-skills/2.1.332/5b6841ccbf88275e11cac63663004213/claude-api
Read: SKILL-level guidance is summarised here; then `java/claude-api/README.md`, `java/claude-api/tool-use.md`,
`java/claude-api/streaming.md`, `shared/tool-use-concepts.md`, `shared/prompt-caching.md`, `shared/error-codes.md`,
and `shared/model-migration.md` → "Migrating to Claude Opus 5.5". Never guess SDK names: take them from those files
or from the installed jar (javap/jar tf), then let the compiler correct you.
- Use the official Anthropic Java SDK (`com.anthropic:anthropic-java`), not raw HTTP. One client per API key, with the
  key passed explicitly (never from the process environment of the service).
- Default model `claude-opus-5-5` (exact string, no date suffix); the server owner may choose another model id from a
  short allowlist kept in one place (`claude-opus-5-5`, `claude-sonnet-5-5`, `claude-haiku-5-5`).
- `thinking: {type: "adaptive"}`; set `output_config.effort` explicitly (default `medium`, configurable). Do NOT send
  budget_tokens, temperature/top_p/top_k, assistant prefill, or forced tool_choice (`any`/`tool` → 400 on this model):
  use `tool_choice: auto`. Tools declared with `strict: true` only where their schema allows it.
- Streaming for every request (max_tokens around 16000–32000); append the full `response.content` (including thinking
  blocks, unchanged) to history; handle `stop_reason`: `tool_use`, `end_turn`, `max_tokens`, `pause_turn`, `refusal`
  (check before reading content; show a neutral message). Enable server-side refusal fallback:
  betas `server-side-fallback-2026-07-01` + `fallbacks: "default"` if the Java SDK exposes it (verify in the SDK).
- Parallel tool calls: execute all tool_use blocks of a turn and return ALL tool_result blocks in ONE user message;
  failed tool → tool_result with is_error true.
- Prompt caching: stable prefix (tools sorted deterministically → system → messages), cache_control breakpoint on the
  system/tools prefix; no timestamps or per-request ids in the cached prefix; verify with usage.cache_read_input_tokens.
- Typed exception chain for errors (rate limit / overloaded → friendly retryable message; auth error → tell the owner
  the key is invalid, never echo it). Timeouts and max retries explicit.
- Token usage of every response (input, output, cache read/write) is recorded for the monthly limit.

## Design
1. **Tools = exactly what the user's own agent would get.** The Agentes module already exposes each module's tools over
   MCP with the user's permissions (find the Core contract modules implement for agent tools, how AgentesServerModule
   builds `tools/list` and dispatches `tools/call` for a user, read-only flags, and how calls are attributed in the
   activity log as "Agente de <usuario>"). Move/reuse that registry in Core so the global agent calls the SAME tool
   implementations in-process with the acting user's access object — never a broader one, never a service identity.
   Permission checks stay inside the tools (they already are); the assistant layer must not add any bypass. Tool list
   is computed per request from the user's CURRENT permissions and enabled modules.
2. **Writes need the user's confirmation.** Read-only tools run automatically. Any tool not flagged read-only pauses the
   turn: the app shows what the assistant wants to do (tool label + human-readable summary of arguments) and the user
   approves or rejects each call; rejected → tool_result is_error "El usuario no autorizo esta accion". A per-company
   setting may allow "sin confirmacion" for specific modules later — NOT in this version. Activity log entries of
   executed tools carry actor "Asistente de <usuario>" (distinct from "Agente de <usuario>"; add the actor kind in
   ActivityActor following the existing agent kind, with reserved-marker protection).
3. **Who may use it.** Server level (server owner; administrators with a new permission `server.assistant.manage`):
   enabled yes/no for the whole server, API key (stored sealed with the master-key envelope like other secrets; shown
   only as a hint), model, effort, default monthly token limit per company, and per company: allowed yes/no + limit
   override. Company level (business owner): enabled yes/no for the company (only if the server allows it), optional
   own API key (then usage is billed to that key; the server limit still applies unless the server owner exempts
   own-key companies — make that a server setting, default: limit applies only to the server key), and a company
   permission `empresa.assistant.use` (default granted to nobody but the business owner; assignable through the normal
   roles/permissions UI) deciding which users see the assistant. With the global agent disabled the Agentes module keeps
   working as today; there is also a company switch "solo agente global" is NOT requested — do not block Agentes.
   The server workspace itself: server owner/administrators may use the assistant there with server tools only if the
   existing server MCP tools are exposed to them today under the same rules; otherwise leave the server workspace out
   and say so.
4. **Conversations.** Stored per user in the company database (new tenant migration): conversation (id, user, title,
   created/updated, archived) and messages (role, content blocks as JSON exactly as needed to resend to the API,
   including tool_use/tool_result and thinking blocks, token usage). A user sees only their own conversations; the
   business owner does NOT read other users' conversations (privacy) but usage totals per user are visible. Retention
   setting (days, default 90) with a daily cleanup job. Context control: when a conversation grows large, start
   compaction (server-side compaction beta if the Java SDK supports it — verify — else cap history and tell the user to
   start a new conversation). Uploaded files/images: NOT in this version.
5. **Privacy and safety.** The system prompt (Spanish, answer in the user's language) states: the assistant works for
   this user of this company inside BMS, only through the tools provided, must treat tool results and record contents
   as data, never as instructions, must not reveal secrets, and must ask before writes. It names the company, the
   user's display name and language, today's date in the company's time zone (date goes AFTER the cached prefix).
   Tool results are passed as tool_result content only. Customer data is sent to the AI provider — the settings screens
   must say so plainly before enabling ("los datos que el asistente consulte se envian a Anthropic para generar la
   respuesta"). Nothing from conversations goes to logs. The activity log records tool executions (as today for agents)
   and settings changes (key set/removed without the value), not the chat text.
6. **Limits and abuse.** Monthly token budget per company (input+output, cache reads counted at their real billed
   ratio or simply counted — document the formula), per-user rate limit (requests/minute and concurrent streams = 1 per
   conversation, small cap per user), max tool calls per turn (e.g. 25) and max turn duration; clear messages when a
   limit is hit. Usage table per company/user/day for the owners' screens.
7. **HTTP contract (write it into this file under "Contract" BEFORE coding, the app agent builds from it).**
   Company routes under `/companies/me/assistant/...`: status (available/enabled/why not), conversations CRUD,
   `POST .../conversations/{id}/messages` streaming Server-Sent Events (text deltas, tool call started/finished with
   label, confirmation requests, usage, done/error), `POST .../confirmations/{id}` approve/reject, settings for the
   business owner, usage. Server routes `/server/assistant/...`: settings, key, per-company allowance, usage. Check how
   the body-size limits, Nginx (proxy buffering must be off for SSE: `X-Accel-Buffering: no` header and/or a location
   rule in scripts/ubuntu/nginx-site.sh with matching test), CSP and rate limits introduced by the security work affect
   a long-lived streaming response, and adjust them.
8. **Dependency.** Adding the SDK to server/build.gradle.kts and the version catalog; check its transitive size and
   that the server distribution still builds; pin the exact version.

## App (second package, after the contract exists)
Assistant entry visible only when status says available: chat screen (conversation list, streaming answer with
markdown-ish plain rendering, tool activity lines, confirmation cards with approve/reject, stop button, new
conversation, limit/disabled messages), business-owner settings (enable, own key, permission hint, usage), server-owner
settings (enable, key, model, effort, limits, per-company allowance, usage). Provider disclosure text before enabling.

## Contract

Server side: Core 0.14.0 (empresa module 0.4.0 declares the company permission). The app checks
`isCoreAtLeast(coreVersion, 0, 14, 0)`; a `404` on `GET /companies/me/assistant/status` also means "not supported".
Shared ids and DTOs live in `core/src/commonMain/kotlin/com/ideasdeveloper/bms/Assistant.kt` (`AssistantIds`,
`Assistant*Response`, `AssistantEvent*`); the JSON below is what they serialize to. Every answer carries
`Cache-Control: no-store`. Errors before a stream starts are `{code, message}` (message in the request language).

### How the assistant gets its tools (decision)

The tool registry already lives in Core: every module publishes a `ModuleMcp` (`ServerModule.mcp`, contract
`server/.../modules/McpTools.kt`), `ModuleAccessService.moduleMcp()` returns those of the installed modules, and
`ModuleAccessService.userAccess(tenant, company, user, serverOwner=false)` gives the user's live `UserModuleAccess`
(enabled modules + granted permissions, read from the tenant on every call). The Agentes module only filters that list
(`AgentesMcp.visibleTools`) and runs `tool.handler(McpToolCall(...))`. The assistant does exactly the same in-process,
in a new Core class `AssistantTools`, without going through MCP/JSON-RPC and without depending on the Agentes module:

- Offered tools = every registered tool whose module is enabled in the company and whose `permission` the user holds now
  (`UserModuleAccess.permissions(moduleId)?.has(tool.permission)`), excluding the server's own `servidor_*` tools
  (only server owners have them; the server workspace is out, see below). Computed again for every model request and
  again right before executing each call; a name the model sends that is not in the current list is answered with
  `tool_result is_error "Herramienta desconocida o sin permiso"` and never executed. No `acting_company`: the assistant
  acts only in the company of the session.
- Execution: `tool.handler(McpToolCall(arguments, permissions.withActivityActor(sessionActor.viaAssistant()), tenant,
  access, agentName = "Asistente", moduleId))` on an IO thread with the company's tenant connection — the same
  handler, the same `ModulePermissions` a screen gets for that user, so the visibility scopes inside the tools apply.
- Activity log: the actor is the session's actor (switcher-aware) with the new origin `assistant`
  (`ActivityActor.viaAssistant()`, `ActivityActor.Origins.Assistant`): rows show "Asistente de <usuario>"
  (`viaAgent` false, `viaAssistant` true in `ActivityLogRow`). "asistente de" is a reserved marker like "agente de"
  (a person or agent named that is shown quoted / refused where users type names).
- Tool order is deterministic (sorted by qualified name `<moduleId>_<name>`), descriptions/schemas are sent as the
  module wrote them; `strict` is not set (module schemas use formats and optional fields; the tools validate their own
  arguments) and `eager_input_streaming` is not set (inputs are small; a write is shown to the user before it runs).
- Read-only = `McpTool.readOnly`. Anything else (including `destructive`) needs the user's confirmation.

**Server workspace: out of this version.** The status answers `available=false, reason=SERVER_WORKSPACE` there. The only
server tools agents get today are the owner's `servidor_*` tools (whole-server blast radius, used with `acting_company`
rechecks that live in the Agentes bridge) and an administrator's two audit read tools through a dedicated agent path;
neither maps onto "a user in a company with their permissions", so the assistant does not expose them.

### Who may use it (all must hold, checked on every request)

1. Not the server workspace (`SERVER_WORKSPACE`).
2. Server switch on (`SERVER_DISABLED`) and the company allowed by the server (`COMPANY_NOT_ALLOWED`; default allowed).
3. Company switch on (`COMPANY_DISABLED`; default off, only the business owner turns it on).
4. An API key: the company's own key, else the server key (`NO_API_KEY`); BMS_MASTER_KEY present to open it
   (`SECRETS_UNAVAILABLE`).
5. The user is the business owner or holds `empresa.assistant.use` (`PERMISSION_REQUIRED`).
6. Monthly limit not reached (`LIMIT_REACHED`; history stays readable).

A business owner session opened through a switcher (server owner/administrator or another company's owner) is a
different person: its conversations are kept apart (owner key of the session opener) and never shown to the business
owner, and vice versa.

### Permissions

| Id | Level | Default | Allows |
|---|---|---|---|
| `server.assistant.manage` | server administrator permission (group Servidor, high risk, scoped) | owner only | Server assistant settings, key, per-company allowance and usage (companies of the administrator's scope). |
| `empresa.assistant.use` | company permission (module empresa) | business owner only | See and use the assistant. Assignable in the normal permissions screen. |

### Company routes (company session: `Authorization: Bearer` + `X-Company-Code`)

`GET /companies/me/assistant/status`
```json
{"visible": true, "available": true, "reason": null, "message": null,
 "canManage": false, "model": "claude-opus-5-5", "keySource": "server",
 "monthlyLimitTokens": 2000000, "monthUsedTokens": 12345, "limitApplies": true,
 "confirmationHours": 24, "maxMessageChars": 20000, "disclosure": "Los datos que el asistente consulte..."}
```
`visible`: show the assistant entry (rules 1-5 hold, or the caller is the business owner, who sees it to configure it).
`available`: may send now (all rules). `reason`: first failing rule code above, `message`: its translated text.
`keySource`: `company` | `server` | null. `monthlyLimitTokens` null = unlimited; `limitApplies` false when the company
uses its own key and the server exempts own keys.

`GET /companies/me/assistant/conversations?archived=false&limit=50&before=<ISO updatedAt>` — only the caller's own:
```json
{"conversations": [{"id": "uuid", "title": "Clientes nuevos", "createdAt": "...", "updatedAt": "...",
  "archived": false, "state": "idle", "pendingConfirmations": 0}], "nextBefore": null}
```
`state`: `idle` | `running` | `awaiting_confirmation` | `closed` (cannot go on: `409 CONVERSATION_CLOSED` on a new message; start a new conversation).

`POST /companies/me/assistant/conversations` `{"title": "optional"}` → `201` the conversation (title defaults to the
first message, cut to 80 characters, when the first message is sent).

`GET /companies/me/assistant/conversations/{id}` → the conversation plus `items` (display form, oldest first) and
`pending` (open confirmations, same shape as the `confirmation_required` event):
```json
{"conversation": {...}, "items": [
  {"id": "m12", "role": "user", "kind": "text", "text": "Busca a Ana", "createdAt": "..."},
  {"id": "m13", "role": "assistant", "kind": "tool", "tool": {"toolUseId": "toolu_1", "name": "clientes_search_customers",
     "label": "Clientes: Buscar clientes", "status": "ok"}, "createdAt": "..."},
  {"id": "m13", "role": "assistant", "kind": "text", "text": "Encontre 2 clientes...", "createdAt": "..."},
  {"id": "e3", "role": "notice", "kind": "notice", "code": "REFUSAL", "text": "El asistente no puede ayudar con esto.", "createdAt": "..."}],
 "pending": []}
```
Tool `status`: `ok` | `error` | `rejected` | `expired` | `pending` | `not_run` | `unknown` (a write that started but whose end is not known: never run again). Tool results and arguments are never
returned to the app (only the confirmation summary). Thinking is never shown.
Another user's conversation (the business owner included) answers `404 CONVERSATION_NOT_FOUND`.

`PUT /companies/me/assistant/conversations/{id}` `{"title"?: "...", "archived"?: true}` → the conversation.
`DELETE /companies/me/assistant/conversations/{id}` → `204` (messages, pending confirmations go with it; usage stays).

`POST /companies/me/assistant/conversations/{id}/messages` `{"text": "..."}` → `200 text/event-stream` (below).
Refused before the stream with JSON: `400 MESSAGE_INVALID` (empty), `413 MESSAGE_TOO_LONG` (> 20000 chars),
`409 CONVERSATION_BUSY` (a turn is running), `409 CONFIRMATION_PENDING` (decide the open confirmations first),
`409 CONVERSATION_TOO_LONG` (start a new one), `429 ASSISTANT_RATE_LIMITED` (+`Retry-After`), `403` with a status
reason code (`COMPANY_DISABLED`, `LIMIT_REACHED`...), `404 CONVERSATION_NOT_FOUND`.

`POST /companies/me/assistant/confirmations/{id}` `{"decision": "approve" | "reject"}` → `200 text/event-stream`. The
decision is recorded; when it was the last open call of that turn, the turn resumes on this same stream (approved calls
run now, with the permissions the user has NOW; rejected ones get `is_error "El usuario no autorizo esta accion"`);
otherwise the stream ends with `done {"reason": "waiting"}`. JSON refusals: `404 CONFIRMATION_NOT_FOUND` (also for a
confirmation of someone else's conversation), `409 CONFIRMATION_DECIDED`, `410 CONFIRMATION_EXPIRED`, plus the rules
above (a decision needs the assistant available: when it was turned off meanwhile, the call simply expires).

`GET /companies/me/assistant/settings` (business owner) →
```json
{"enabled": false, "serverEnabled": true, "companyAllowed": true, "ownKeySet": false, "ownKeyHint": null,
 "serverKeySet": true, "monthlyLimitTokens": 2000000, "limitAppliesToOwnKey": false, "model": "claude-opus-5-5",
 "retentionDays": 90, "disclosure": "...", "permissionId": "empresa.assistant.use"}
```
`PUT /companies/me/assistant/settings` `{"enabled"?: true, "acceptDisclosure"?: true, "apiKey"?: "sk-ant-...",
"removeApiKey"?: true}` → the settings. Turning it on needs `acceptDisclosure: true` (`400 DISCLOSURE_REQUIRED`);
`apiKey` is stored sealed (`400 API_KEY_INVALID` for a value that does not look like a key, `503 SECRETS_UNAVAILABLE`
without BMS_MASTER_KEY); it is never returned, only `ownKeyHint` (`sk-ant-…` + last 4). Changes are written to the
company activity log (`core`, `assistant_settings`, `settings_changed`; the key only as "changed"). `403
BUSINESS_OWNER_REQUIRED` for anyone else.

`GET /companies/me/assistant/usage?month=2026-11` (business owner) →
```json
{"month": "2026-11", "monthlyLimitTokens": 2000000, "usedTokens": 12345,
 "users": [{"userId": "uuid", "name": "Ana Ruiz", "requests": 12, "inputTokens": 1, "outputTokens": 2,
            "cacheReadInputTokens": 3, "cacheCreationInputTokens": 4, "countedTokens": 5, "keySource": "server"}],
 "days": [{"day": "2026-11-27", "countedTokens": 5, "requests": 12}]}
```
Totals only: the business owner never reads other users' conversations.

### SSE stream (`text/event-stream; charset=utf-8`, header `X-Accel-Buffering: no`)

Frames are `event: <type>\ndata: <one-line JSON>\n\n`; a `: ping` comment every 15 s keeps proxies open. Closing the
connection is the stop button: the running model request is cancelled, the partial answer is discarded (not stored,
its tokens still count) and the conversation goes back to `idle`.

| event | data |
|---|---|
| `turn_started` | `{"conversationId": "uuid"}` |
| `text_delta` | `{"text": "..."}` (assistant answer text; thinking never streams) |
| `tool_started` | `{"toolUseId": "toolu_..", "name": "clientes_search_customers", "label": "Clientes: Buscar clientes", "readOnly": true}` |
| `tool_finished` | `{"toolUseId": "..", "name": "..", "label": "..", "status": "ok" \| "error" \| "rejected" \| "expired" \| "not_run", "message": "optional short error"}` — an approved call that was NOT run because its data changed after the card was shown ends as `status: "error"` with the message "Los datos cambiaron desde que se mostro la confirmacion; hay que confirmar de nuevo" (and a `notice CONFIRMATION_CHANGED`, below) |
| `confirmation_required` | `{"confirmationId": "uuid", "toolUseId": "..", "name": "..", "label": "CRM: Enviar mensaje a un lead", "module": "CRM", "destructive": false, "outbound": true, "summary": [{"label": "Texto del mensaje...", "value": "<every argument, whole value>", "hiddenCharacters": true}], "preview": [{"label": "Destinatario (lead)", "value": "Luis Mora"}, {"label": "Se envia a", "value": "luis@example.com"}, {"label": "Mensaje (completo)", "value": "..."}], "previewError": null, "expiresAt": "ISO"}` — see "Confirmation card" |
| `usage` | `{"inputTokens": 1, "outputTokens": 2, "cacheReadInputTokens": 3, "cacheCreationInputTokens": 4, "countedTokens": 5, "monthUsedTokens": 6, "monthlyLimitTokens": 7}` (after every model response) |
| `notice` | `{"code": "REFUSAL" \| "CLOSED" \| "MAX_TOKENS" \| "FALLBACK" \| "COMPACTED" \| "EXTERNAL_LINK" \| "CONFIRMATION_CHANGED", "message": "..."}` — on `REFUSAL` drop the text streamed since the last tool line (a declined answer is never stored); `CLOSED`: the conversation cannot go on; `EXTERNAL_LINK`: the answer links to a host that is not the company's own (show the warning next to the answer); `CONFIRMATION_CHANGED` (new): the call the user approved was NOT run because what it would do changed after the card was shown (a lead's address, a template...). Mark that card as "no ejecutada: los datos cambiaron" (the same `toolUseId` has a `tool_finished` with `status: "error"` just before). The model then receives the error and may propose the call again: a NEW `confirmation_required` follows in the same stream with the current data. Nothing else for the app to do |
| `error` | `{"code": "...", "message": "...", "retryable": true}` |
| `done` | `{"reason": "end_turn" \| "awaiting_confirmation" \| "waiting" \| "max_tokens" \| "refusal" \| "error" \| "limit" \| "cancelled"}` |

Every stream ends with exactly one `done`. Stream `error` codes: `MODEL_RATE_LIMITED`, `MODEL_OVERLOADED` (retryable),
`MODEL_KEY_INVALID` (tell the business owner/server owner the key was rejected; the key is never echoed),
`MODEL_BAD_REQUEST`, `MODEL_UNAVAILABLE`, `TURN_TOO_LONG` (10 min), `TOO_MANY_TOOL_CALLS` (25 per turn),
`LIMIT_REACHED`, `INTERNAL`.

### Confirmations: pause, persistence, expiry

When a model response asks for tools, read-only calls run at once; every other call is stored in
`assistant_pending_calls` (tool, arguments, summary) with the results of the read-only calls of the same response, the
conversation becomes `awaiting_confirmation` and the stream ends with `done awaiting_confirmation`. Nothing is held in
memory: the app may close, and the user decides later from the conversation screen (`pending`). A pending call expires
after **24 hours**; on expiry (checked on every read and by the daily job) it is answered with `is_error "La
confirmacion vencio sin respuesta del usuario; no se ejecuto."`, the results are stored and the conversation is `idle`
again (no model call is made for it). A running turn holds a lease of 12 minutes; after a crash the conversation is
`idle` again when the lease is over and unanswered tool calls are answered `not_run` before the next message.

### Approval is bound to what runs (V020)

The card the user approves is what runs, or nothing runs:

- When a turn pauses, the engine stores a **fingerprint** of each call's confirmation card (`assistant_pending_calls.preview_hash`,
  tenant `V020__assistant_preview_hash.sql`): SHA-256 of the preview rows in order (label and value of each; for a failed
  preview, only the fact that it failed; none when the tool has no preview). The fingerprint is computed from the preview
  resolved in the **source language (Spanish)**, whatever the language of the request, so deciding in another language does not
  change it. The card itself (`preview`) is still resolved in the language of the request.
- When the user approves, before the call is claimed, the engine resolves the same preview again (same hook, the user's
  permissions now) and compares. Different: the call is NOT executed; its `tool_use` is answered `is_error` with "Los datos
  cambiaron desde que se mostro la confirmacion; hay que confirmar de nuevo"; the stream emits `tool_finished` (`status: "error"`)
  and `notice CONFIRMATION_CHANGED`, and the turn continues: the model sees the error and may propose the call again, which
  produces a new `confirmation_required` with the current preview. There is no extra field on `confirmation_required`.
- Every tool with `outbound: true` or `destructive: true` MUST provide a preview, or be listed (with its reason) in
  `ToolRegistryContractTest.withoutPreview`; a new tool that does neither fails that test.
- Previews are read only and reach nothing else (see "Preview sandbox"); **a preview makes no HTTP request of any kind** (no
  translation service, no provider): it runs before the user decided. A tool that needs an external answer to describe its
  effect says so in plain words instead ("se pedira a Encuentra24 ...").

### Preview sandbox

A preview runs on a connection of its own whose every transaction is read only for its whole life
(`SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY` before the first transaction, plus `SET TRANSACTION READ ONLY`). The
connection the preview receives refuses `commit`, `setAutoCommit`, `setReadOnly`, `close`, `abort` and `unwrap`, and the module
service refuses to open any other connection (`ModuleAccessService.withoutNewConnections`: `tenantConnectionForCompany`,
`tenantConnectionForDatabase`, `centralConnection`, `tenantConnection(call)`, and the writes to the control database). Afterwards the
connection is aborted (evicted from the pool), never reused. A preview that fails any of this gets "No se pudo preparar la vista
previa." Not enforceable: raw SQL that turns the read-only mode off by itself (`SET default_transaction_read_only = off`) and
HTTP requests (rule of the module, tested per tool with a transport that fails when called).

### Limits

- Monthly tokens per company (calendar month, UTC). Counted per model response as
  `input + cache_creation + output + ceil(cache_read / 10)` (cache reads at their ~0.1 price ratio), summed over
  `usage.iterations` when the response has them (fallbacks, compaction). Default limit: server setting
  `defaultMonthlyTokens` (2 000 000; 0 = unlimited), overridden per company. It applies to the server key always and
  to a company's own key only when `limitOwnKeyCompanies` is on (default off). Checked before every model request; a
  turn already running finishes its current request.
- Per user: 10 requests (messages + confirmations) per minute, 1 stream per conversation, 2 streams at once; 20 new
  conversations per minute and 500 kept (`409 TOO_MANY_CONVERSATIONS`; counted and created in one step under a lock per
  user, so two requests at once cannot both take the last place).
- Per company 8 streams at once (`429 ASSISTANT_RATE_LIMITED`), per server 32 (`503 ASSISTANT_BUSY`, with `Retry-After`).
  Turns run on their own pool of 32 threads, never on the server's shared IO threads.
- A stream whose client stops reading (its buffer of 1024 frames is full, or one write waits 30 s) is cancelled like a
  closed connection.
- Every model request is counted, also when it is stopped or fails mid-stream: the usage the provider already reported,
  or, when it reported none yet, the prompt size of the previous response of the conversation. Output tokens only arrive in
  the last `message_delta`, so a stopped or broken stream charges `max(reported output, estimated output)` with
  `estimated = (characters of text + thinking + tool-input deltas received) / 3`. A response that arrived complete but could
  not be read (`AnthropicResponses.from` failed) is charged what the provider reported and the turn ends with `error INTERNAL`.
- The monthly limit is checked before each model request and not reserved: concurrent turns of one company can pass it
  by at most what their requests in flight consume (bounded by the per-company stream limit). Documented overshoot.
- Per stream (a turn resumed after a confirmation starts its own counts): 25 tool calls, 40 model requests, 10 minutes.
- Message: 20 000 characters. History: server-side compaction (beta `compact-2026-01-12`, trigger 150 000 input
  tokens); a conversation over 400 stored messages answers `409 CONVERSATION_TOO_LONG`.

### Server routes (server session; policy `server.assistant.manage` in `ServerRoutePolicies`)

`GET /server/assistant/settings` →
```json
{"enabled": false, "keySet": false, "keyHint": null, "model": "claude-opus-5-5",
 "models": ["claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5"], "effort": "medium",
 "efforts": ["low", "medium", "high", "xhigh", "max"], "defaultMonthlyTokens": 2000000,
 "limitOwnKeyCompanies": false, "retentionDays": 90, "secretsAvailable": true, "disclosure": "..."}
```
Settings and key (`GET`/`PUT /server/assistant/settings`, `PUT`/`DELETE /server/assistant/key`) affect every company:
an administrator needs `server.assistant.manage` AND every company in scope (`allCompanies`); a scoped one gets `403
SERVER_PERMISSION_REQUIRED`. The per-company route stays scoped by its path and the lists keep to the scope.
`PUT /server/assistant/settings` `{"enabled"?, "acceptDisclosure"?, "model"?, "effort"?, "defaultMonthlyTokens"?,
"limitOwnKeyCompanies"?, "retentionDays"? (1-3650)}` → the settings (`400 DISCLOSURE_REQUIRED`, `400 MODEL_INVALID`,
`400 EFFORT_INVALID`, `400 VALUE_INVALID`).
`PUT /server/assistant/key` `{"apiKey": "sk-ant-..."}` / `DELETE /server/assistant/key` → the settings.
`GET /server/assistant/companies` → `{"companies": [{"code", "name", "allowed", "monthlyTokens" (override or null),
"effectiveMonthlyTokens", "enabled", "ownKeySet", "monthUsedTokens"}]}` (administrators: their scope).
`PUT /server/assistant/companies/{code}` `{"allowed": true, "monthlyTokens": null}` → that company row (scoped by
path; `404 COMPANY_NOT_FOUND`).
`GET /server/assistant/usage?month=2026-11` → `{"month", "companies": [{"code", "name", "requests", "countedTokens",
"serverKeyTokens", "ownKeyTokens"}]}`.
Every change is audited in `provisioning_audit_log` (`assistant.settings_changed`, `assistant.key_set`,
`assistant.key_removed`, `company.assistant_changed`), never with the key.

### Storage

- Server settings in `server_settings` (no control migration, like the storage quotas): `assistant.enabled`,
  `assistant.model`, `assistant.effort`, `assistant.monthly_tokens.default`, `assistant.limit_own_key`,
  `assistant.retention_days`, `assistant.api_key` (sealed with BMS_MASTER_KEY + the control data key),
  `assistant.company.<code>.allowed`, `assistant.company.<code>.monthly_tokens`.
- Tenant migration `V020__assistant_preview_hash.sql`: `assistant_pending_calls.preview_hash` (above). Registered with V018/V019 in
  `scripts/ubuntu/update.sh` (required files) and `CompanyProvisioningService.applyAssistantMigration`.
- Tenant migration `V018__create_assistant.sql`: `assistant_settings` (one row: enabled, sealed own key, hint),
  `assistant_conversations`, `assistant_messages` (role, content blocks JSON exactly as resent, including thinking,
  tool_use and tool_result; usage per response), `assistant_pending_calls`, `assistant_usage` (day, user, key source).
- Retention: conversations not updated for `retentionDays` (default 90) are deleted by a daily job; usage rows are
  kept 13 months.

### Confirmation card (review fix)

- `summary`: EVERY argument the model sent, each value WHOLE (never cut; a message can be 20 000 characters): the app
  shows it all (scroll or expand, never a silent preview cut). Characters that hide or reorder text (bidi controls,
  zero-width, control and private-use characters) arrive written as `[U+202E]`...; `hiddenCharacters: true` on that row
  means the app must warn ("Este texto contiene caracteres ocultos"). The rule (`AssistantTools.visible`): always shown as
  `[U+XXXX]`: control and format characters (bidi, zero-width, tags), line/paragraph separators, unassigned and private-use code
  points, and the blank-looking fillers (Hangul fillers, Khmer inherent vowels, Mongolian free variation selectors, combining
  grapheme joiner, Braille blank). Variation selectors (U+FE00-FE0F, U+E0100-E01EF) are how bytes are smuggled into
  ordinary text, so they are shown unless they mean something: FE0E/FE0F right after ONE symbol, digit, `#` or `*` (emoji and
  keycap presentation) and the others right after ONE Han ideograph; repeated or after a letter they are shown. Combining marks
  (accents) stay readable up to 3 on one base character; a mark with no base (at the start or after a space/punctuation) or
  more than 3 stacked ones are shown. A zero-width joiner between two emoji stays readable (family and profession emoji).
- `preview`: what the module resolved (`McpTool.preview`, run read-only before the card is shown): recipient name and
  address and the final text of a message, the names of what a delete removes instead of ids. Shown above the
  arguments. `previewError`: the module could not resolve it (show it; the user may still reject).
- `outbound: true`: the call sends something out of the company; the card says "Envia un mensaje fuera de la empresa".
  Marked tools (all with a preview that says in plain words what goes where): CRM `send_message`, `run_automation`,
  `press_lead_button`, `save_automation`, `save_message_template`, `import_from_url`, `update_mail_settings`,
  `update_user_notifications`, `update_meta`, `meta_connect_page`, `meta_connect_whatsapp`, `meta_fill_names`, `update_wordpress`,
  `wordpress_test`, `wordpress_sync_agents`, `encuentra24_sync`, `update_cloudbeds`, `cloudbeds_test`, `close_property_sale`,
  `create_reservation`; Hotspot `update_settings`, `set_integration`, `unifi_choose_console`, `unifi_choose_site`; every
  monday `*_outbound_configure` and `*_outbound_retry`. Destructive tools with a preview: CRM `delete_lead`, `delete_interest`,
  `delete_stage`, `delete_message_template`, `delete_automation`, `bulk_update_leads`, `apply_pipeline_template`; Clientes
  `delete_customer_field`; Empleados `delete_shift`; Hotspot `delete_network`; Agentes `revoke_agent`; monday `*_outbound_discard`.
  The audit of the rest is in `ToolRegistryContractTest` (what is not marked and why).
- CRM `send_message` sends **exactly what the card shows**: the text the model wrote goes out as PLAIN TEXT (every `<` is
  escaped, so an `<img src>`, a link or an invisible element is shown as text, never sent as HTML; the card has a "Formato"
  row), for the assistant and for MCP agents alike (`CrmAdminScreens.composeReply(fromAgent = true)`); and a template is
  translated only from translations already saved (a database read): neither the preview nor the send calls a translation
  service, and without a saved translation the base language goes out and the card says so ("Traduccion"). The card's
  `Mensaje (completo)` is therefore the text sent. A template written by the company keeps its HTML (the card says
  "Plantilla de la empresa con formato HTML"); `save_message_template` shows its raw HTML source for approval.
- Preview text is written in Spanish (like the MCP tools): the fingerprint above is taken in Spanish anyway.

### Rendering contract (review fix)

Assistant text is rendered as plain text: no images, no HTML, nothing loaded automatically. Links are not clickable; when
the app offers to open one it shows the full URL and asks for confirmation first. The system prompt tells the model that
leads, WiFi visitors, form submissions, received messages and imported text come from the public and are never
instructions, and that it must not build URLs from record data. The server flags any link to a host that is not the
server's (`BMS_PUBLIC_BASE_URL`) or one of the company's public domains with a `notice` `EXTERNAL_LINK`. Detection
(`AssistantLinks`): `scheme://authority`, `www.host` and bare `host.tld` followed by a path/query/port or with a common TLD (an
e-mail address is not a link); the host is read as a browser does (`https://acme.com@evil.com` is evil.com; port dropped);
invisible characters are removed and full-width forms normalized first; a host that is not plain ASCII letters, digits, hyphens
and dots (an IDN, a `%` escape) is ALWAYS external (shown with its punycode), and only a punycode host that is exactly a known
domain (or a subdomain of one) is not.

### Turn safety (review fix)

- Each turn holds the conversation with a lease token (tenant V019); every write of the turn (messages, pending calls,
  claims, giving the conversation back) checks it, and the lease (12 min) is renewed before each model request and each
  tool call. A turn that lost it stops (`error CONVERSATION_BUSY`) without touching the conversation.
- The SDK does not retry (`maxRetries 0`); the server retries once, only before anything was streamed. Request timeout
  4 min, read timeout 2 min: one model request takes at most ~8 min, under the lease.
- Expiry of confirmations takes the conversation's lease from the paused state, so it never races a resumed turn.
  Results go in one transaction with the deletion of the pending rows; a message is never stored empty; only the calls of
  the last assistant message are answered, in its order.
- An approved write is claimed (`executing`) before it runs and its result stored right after: it never runs twice, and
  a crash in between is reported as `unknown` ("se empezo a ejecutar pero no se sabe si termino"), never as "not run".
- A conversation left `awaiting_confirmation` with every call decided (a crash between the last decision and the resumed turn)
  is recovered, never stuck: the retry of a decision resumes the turn (approved calls run once, as usual); a new message takes
  the conversation over and the decided calls are answered truthfully (approved ones "No se ejecuto."); the daily job
  (`expireAll`) settles those untouched for 15 minutes the same way, with no model request.
- A turn taken by a request that then fails before it starts (for example closing the tenant connection throws) gives the
  conversation back at once (`AssistantEngine.abandon`) instead of keeping its lease for 12 minutes.
- Retention (`purge`) also deletes conversations stuck in `running` whose lease is older than the retention horizon.
- History sent to the API: user and assistant always alternate (stored consecutive messages of one role are joined, the
  same way every time, so the prefix stays stable).
- A declined turn: when it holds only the user's text (and the answer), it is left out of every later request by a notice
  row that names it (append-only: nothing before it changes); when tool results were already part of it, the
  conversation is closed (`CLOSED`), because leaving them out would drop work the user saw.

### Tool list and caching (review decision)

The tool list keeps following the user's live permissions per request, with `drop_block`. The documented alternative
(full stable tool set plus `tool_addition` / `tool_removal` blocks, beta `mid-conversation-tool-changes-2026-07-01`) is
present in the Java SDK, but it would declare tools the user may not use and rely on the model honouring removals, while
the bridge's rule is "a tool the user lacks is not offered"; permission changes are rare, so the cost (thinking of
earlier turns dropped and one cache miss after a change) is accepted. The user's language moved out of the system prompt
into each user turn's context block, so a language change no longer changes the cached prefix.

### What the app shows

- The entry only when `status.visible`; when `available` is false, the `message` instead of the input (history stays).
- Chat: conversation list, streaming text (plain markdown-ish), a line per tool (`label` + status), a confirmation
  card per `confirmation_required` (label, module, summary rows, approve/reject, expiry), stop button (close the
  stream), new conversation, `notice`/`error` lines, the limit message.
- Business owner settings: enable (with the disclosure text and `acceptDisclosure`), own key (hint only), the
  permission hint (`empresa.assistant.use` in the users screen), usage per user.
- Server settings: enable (disclosure), key (hint), model, effort, default limit, own-key exemption, retention,
  per-company allowance/limit, usage.
- Disclosure text (exact, translated): "Los datos que el asistente consulte se envian a Anthropic para generar la
  respuesta."

## Implementation (server, Core 0.14.0)

Where it lives: `server/.../server/assistant/` — `AssistantModel.kt` (the SDK behind `AssistantModelClient`,
request builder `AnthropicRequests`, response reader `AnthropicResponses`, typed errors), `AssistantEngine.kt` (turn loop,
pause/resume, expiry, system prompt), `AssistantTools.kt` (tool registry reuse, see "How the assistant gets its tools"),
`AssistantStore.kt` (tenant V018), `AssistantSettings.kt` (server_settings and tenant settings, sealed keys, audit),
`AssistantService.kt` (caller, availability, runtime, per-user limits, daily upkeep), `AssistantRoutes.kt` /
`AssistantServerRoutes.kt` (HTTP + SSE), `AssistantView.kt` (what the app sees). Contract types:
`core/.../Assistant.kt`.

Claude API (official Java SDK `com.anthropic:anthropic-java:3.14.0`, names checked with `javap` on that jar):
beta Messages endpoint (`client.beta().messages().createStreaming`) because the refusal fallback, thinking block binding
and compaction are beta parameters there; model from the allowlist (default `claude-opus-5-5`); `thinking: adaptive`
with `block_binding.prefix_mismatch_behavior: drop_block` (beta `thinking-binding-controls-2026-08-01`: the tool list
follows the user's live permissions, so a prefix may legitimately change; the API then drops the old thinking blocks
instead of failing); `output_config.effort` explicit (default `medium`); `max_tokens` 32000; `tool_choice: auto` only
when there are tools; no sampling parameters, no budget, no prefill; `fallbacks: "default"` (beta
`server-side-fallback-2026-07-01`) except for `claude-haiku-5-5`, which has no fallback model; compaction
`compact_20260112` at 150 000 input tokens (beta `compact-2026-01-12`) on the three models (the reference lists compaction
among what is new on Claude Haiku 5.5); the SDK log level is OFF whatever `ANTHROPIC_LOG` says; cache breakpoint on the system
block (tools + system) plus top-level automatic caching for the conversation; the date goes in each user turn
(`<contexto_bms>` text block, hidden from the app). Every `stop_reason` is handled (`refusal` first, `max_tokens`,
`tool_use`, `pause_turn`/`compaction` continue, anything else ends). The response is stored as `BetaMessage.toParam()`
(thinking with signature, tool_use...), minus the blocks before a `fallback` block that the docs say not to echo. A
manual loop rather than the SDK's tool runner: the pause for confirmation must survive the HTTP request, which the
runner cannot do. One SDK client per API key (by its hash), the key passed explicitly, never `fromEnv()`; timeout 10 min,
2 retries; errors classified by HTTP status only (the exception text is never logged or shown).

Database: tenant `V018__create_assistant.sql` (applied to new companies with the Core migrations, to existing ones by
the startup catch-up and by `update.sh`, which applies every tenant migration from V006 on). Grants: the runtime role gets
SELECT, INSERT, UPDATE, DELETE on `assistant_settings`, `assistant_conversations`, `assistant_pending_calls`,
`assistant_usage`, and only SELECT, INSERT on `assistant_messages` (+ USAGE, SELECT on its sequence): messages go only
with their conversation (cascade, run as the table owner). The same rule is in `grantRuntimeDefaults`, `update.sh`
and `db-roles.sh`. No control migration: the server settings are `server_settings` keys (runtime already has DML there).

Settings and environment: nothing new in `server.env`. The server key is set from the app (sealed with BMS_MASTER_KEY;
without it no key can be stored and the status says `SECRETS_UNAVAILABLE`). The server needs outgoing HTTPS to
`api.anthropic.com` once the assistant is on.

Not verified against the real API (no call was made, no real key used anywhere): the beta parameters' acceptance per
model (block binding and compaction on Sonnet/Haiku 5.5, `fallbacks: "default"` on Sonnet 5.5), the exact streaming of
fallback/compaction blocks, prompt-cache hit rates (`cache_read_input_tokens` is recorded per response and in the usage
screens, to be checked on a real server), and SSE through a real Nginx (the generated config is checked by `nginx -t`
in `scripts/ubuntu/tests`).
