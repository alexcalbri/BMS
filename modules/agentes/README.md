# Agentes IA (MCP)

Each user connects their own AI model or agent (Claude, ChatGPT, Cursor, a custom agent...) to
BMS through the [Model Context Protocol](https://modelcontextprotocol.io). The agent acts on
behalf of that user and is **limited to the functions the user is allowed to use**.

## Rules

1. **One agent = one user.** Every token belongs to the user that created it. The agent never has a
   permission of its own: on every request the server reads the user's permissions *at that moment*.
   Removing a permission, deactivating the user or disabling a module takes effect on the agent's
   next call.
2. **The token can only narrow access.** When creating it, the user may limit it to some modules and
   to read-only tools. It can never add anything the user does not have.
3. **Tools apply the same rules as the screens.** Module tools run the module's own validations and
   visibility scopes, so an agent sees exactly what its user sees.
4. **Everything is recorded.** Every call (successful, failed or denied) is stored in
   `agent_activity` for 180 days; modules may also record the agent in their own history.
5. **Revocable at any time** by the user (own agents) or by holders of `agentes.manage` (any agent
   of the company).

## Connecting an agent

1. The server owner installs the module and the company enables it.
2. The business owner grants `agentes.connect` to the users that may connect agents.
3. The user opens **Agentes IA → Conectar un agente**, chooses modules, read-only and expiration,
   and copies the token (shown once).
4. The agent is configured with the endpoint and the token:

```text
URL:    https://<server>/modules/agentes/mcp
Header: Authorization: Bearer bmsmcp.<company>.<secret>
```

Claude Code:

```bash
claude mcp add --transport http bms https://<server>/modules/agentes/mcp \
  --header "Authorization: Bearer bmsmcp.<company>.<secret>"
```

Generic JSON configuration (Cursor, VS Code and other clients with HTTP + headers):

```json
{
  "mcpServers": {
    "bms": {
      "type": "http",
      "url": "https://<server>/modules/agentes/mcp",
      "headers": { "Authorization": "Bearer bmsmcp.<company>.<secret>" }
    }
  }
}
```

### With OAuth (claude.ai and other connector-based apps)

No token to copy: add a custom connector with the URL `https://<server>/modules/agentes/mcp`
(claude.ai: *Settings → Connectors → Add custom connector*). The app discovers the authorization
server, registers itself, and opens BMS's sign-in page, where the user enters company
(empty for the server owner), user and password, then chooses modules, read only and how long the
connection lasts. The connection then appears in **Mis agentes** like any other agent and can be
revoked there.

Requirements: HTTPS (claude.ai rejects plain HTTP) and the Nginx location
`/.well-known/oauth-` proxied to the backend. New installs get it from `install.sh`;
`update.sh` adds it to existing sites (a backup of the site file is kept next to it).

## Endpoint

- `POST /modules/agentes/mcp`: JSON-RPC 2.0, Streamable HTTP transport with JSON responses
  (protocol versions `2025-06-18`, `2025-03-26`, `2024-11-05`). Methods: `initialize`, `ping`,
  `tools/list`, `tools/call`; `resources/list` and `prompts/list` return empty lists.
- `GET`/`DELETE` answer 405: the server does not open event streams or keep sessions.
- 401 for missing, malformed, unknown, expired or revoked tokens, inactive users, users without
  `agentes.connect` and companies without the module enabled.
- 120 requests per minute per token (429 with `Retry-After`).
- Requests with an `Origin` header from another host are rejected (DNS rebinding / browsers).

The token is `bmsmcp.<company>.<secret>`: the company travels in it, so the agent needs no other
header. Only its SHA-256 is stored.

## OAuth authorization server

Implements the MCP authorization spec (2025-06-18) with OAuth 2.1:

| Endpoint | Purpose |
|---|---|
| `GET /.well-known/oauth-protected-resource[/modules/agentes/mcp]` | Protected resource metadata (RFC 9728). The MCP endpoint's 401 points to it in `WWW-Authenticate: Bearer resource_metadata="..."`. |
| `GET /.well-known/oauth-authorization-server` | Authorization server metadata (RFC 8414). The issuer is `BMS_PUBLIC_BASE_URL` (set by `install.sh`/`update.sh`), falling back to the request `Host`; it never trusts `X-Forwarded-Host`. |
| `POST /modules/agentes/oauth/register` | Dynamic client registration (RFC 7591). Stateless: the client id carries the client metadata signed with HMAC; confidential clients get a secret derived from it. Redirect URIs must be HTTPS or loopback HTTP. |
| `GET/POST /modules/agentes/oauth/authorize` | Authorization code with mandatory PKCE S256. Sign-in page (rate limited per IP, no session is left behind) and consent page. `resource` (RFC 8707), when sent, must be the MCP endpoint. |
| `POST /modules/agentes/oauth/token` | `authorization_code` and `refresh_token` grants. Access tokens last 1 hour; refresh tokens rotate on every use and are bound to their client. A refresh fails (and the connection is revoked) when the user lost access. Reusing an already-rotated refresh token of ANY earlier generation revokes the connection (reuse detection over the whole refresh history), except within a ~30 s grace window for a client retrying after a lost response. |
| `POST /modules/agentes/oauth/revoke` | Token revocation (RFC 7009), only for tokens issued to the calling client. |

The HMAC key is created on first use in `server_settings` (`agentes.oauth.signing_key`); deleting it
invalidates registered clients (they register again), not existing connections. Sign-in tickets
(10 minutes) and authorization codes (2 minutes, single use) live in memory: a restart only
cancels flows in progress.

## Data

Migration `V001__create_agent_tokens.sql`:

- `agent_tokens`: owner (tenant user or server owner), name, hash, hint, allowed modules
  (`NULL` = all, including modules enabled later), read only, expiration, last use, revocation.
  OAuth connections also store the client id, the current refresh token hash and when the current
  access token expires; their `expires_at` is the end of the whole connection.
- `agent_refresh_history` (migration `V003__refresh_history.sql`): hashes of every refresh token already
  rotated for each OAuth connection and when they were rotated; written in the same statement as the
  rotation (atomic). The daily job deletes the history of connections revoked or expired more than 90 days ago.
- `agent_activity`: tool, outcome (`ok`, `error`, `denied`), detail and time. A daily job deletes
  entries older than 180 days.

## Permissions

| Permission | Allows |
|---|---|
| `agentes.connect` | Connect own agents, see their activity and revoke them. Required on every agent request. |
| `agentes.manage` | See and revoke every agent of the company with its activity. |

## Only a bridge

This module knows nothing about any other module, and no module depends on it. Each module has its
own MCP (`ServerModule.mcp`, a `ModuleMcp` with instructions and tools; contract in
`server/.../modules/McpTools.kt`), and this module only connects those to the model:

- `tools/list` offers the tools of the installed and enabled modules that the user (and the token)
  may use, as `<moduleId>_<name>`; `tools/call` runs them with the user's live permissions.
- The `instructions` sent on `initialize` are this module's own text (who the agent acts as and the
  general rules) followed by the instructions each module wrote, only for the modules the agent can
  use some tool of.
- The module's MCP is read on every request, so a module update that adds or changes functions
  reaches agents as soon as that module is updated, without touching this one.

This module also has its own MCP, like any other, only about itself (`AgentesMcpTools.kt`):
`agentes_list_agents`, `agentes_get_agent_activity` and `agentes_revoke_agent`, with the same rules
as *Mis agentes* and *Agentes de la empresa*. Creating a connection is not offered: its token is a
credential and is shown only in the app.

It also exposes the user's notification center (core service, see `modules/README.md`):
`agentes_list_notifications` (own notifications, newest first, optionally only unread, paginated) and
`agentes_mark_notifications_read` (one by id, or all with `all=true`). Both act only on the
signed-in user's own notifications and need the *Conectar agentes IA* permission. Added in 0.3.0,
which **requires a Core with the notification center** (core migration V005, `access.notifications`
and `McpToolCall.moduleId`); `module.json` has no minimum-Core field, so update the Core first.

## Server owners

A server owner's agent manages the whole server:

- The server's own MCP (`server/.../company/application/ServerMcpTools.kt`, tools `servidor_*`, only
  for server owners): list, create, edit, activate, deactivate and delete companies, enable modules
  per company and in the server workspace, install, update and remove modules, and the module catalog.
- Every installed module's tools, in any company: the bridge adds an `acting_company` argument and runs
  the tool in that company's database with the server owner's live access there (it must still be an
  active server owner, the company active and the module enabled in it). Without the argument, the tool
  runs in the server workspace. The activity log stays in the server workspace, as `<tool> @ <company>`.
- Credentials still never pass through the agent. `servidor_create_company` gives the business owner
  a random password nobody sees; the server owner sets the real one in the app (*Empresas > Editar >
  Contrasena del business owner*), which also closes the business owner's open sessions.

## Business owners with several companies (0.4.0)

The server owner puts companies in the same **owner group** (duplicating a company with the same owner,
or linking them): the group says they belong to the same business owner, who already switches between
them in the app without a password. Since 0.4.0 that business owner's agent can work in all of them:

- Only for the **business owner** of the company the token belongs to, and only for a connection created
  with **"Tambien en las demas empresas de mi grupo"** (`agent_tokens.group_scope`, migration V004; the
  option is in the new-agent form and in the OAuth consent page, and is ignored for anyone who is not the
  business owner). Every connection that existed before, and every new one without the option, stays in
  its own company. Any other user's agent is unchanged. A server owner's agent is unchanged too.
- The agent is offered the tools of the modules enabled in its own company plus, with `acting_company`,
  those of the modules enabled in some company of the group it reaches now (read at most once a minute).
- The module tools get the `acting_company` argument. The target must be a company of the same owner
  group that is active, not suspended in the group and not the server workspace; the company the token
  belongs to must not be suspended either. This is read from the central database **on every call**
  (`ModuleAccessService.groupOwnerAccess`, `OwnerGroupScope`), so taking a company out of the group
  applies to the next call.
- The group alone is not enough: both companies must have **the same business owner email** at the time
  of the call, and neither may lack one. A company linked by mistake, or whose owner changed, is out
  without waiting for anyone to suspend it.
- **Agentes IA must be enabled in the target company too**, and its business owner allowed to connect
  agents there: a company without the module is never reached by an agent of another one.
- In the target the tool runs as **the business owner of that company** (each company may have its own
  user; the group is what says they are the same person, like the company switcher), with its live
  access: the module must be enabled there. The token still narrows modules and read-only mode.
- `agentes_list_group_companies` (read only) returns the code and name of the companies of the group,
  nothing else about the server.
- The server's own tools (`servidor_*`) stay with server owners.
- Every call is recorded in the activity log of the token's company as `<tool> @ <company>`. A target
  outside the group is refused and recorded as `denied`, always with the same message, which does not
  tell whether that company exists. Whatever the agent sent as company is logged only when it looks
  like a company code. When a call in another company fails, the agent gets the real message but
  the log of the token's company keeps a fixed text, so details of one company do not end in another's log.
- `acting_company` that cannot be honoured (a tool that does not take it, a value that is not text) is
  **refused**; the tool never runs in the token's own company instead.
- The company where it acted keeps its own record too (`agent_group_activity`: company of origin, agent,
  user, tool, outcome and time, purged with the rest of the activity). People with *Administrar agentes*
  there read it with `agentes_list_group_activity`.
- A connection revoked or expired in the middle of a request (a batch) stops at its next call.

A JSON-RPC batch holds at most 20 messages (it is answered by one request of the rate limit).
- Requires Core 0.9.1 for `ModuleAccessService.ownerGroupCompanies` and `groupOwnerAccess` (the module itself needs Core 0.10.0 since 0.5.0).

How a module writes its MCP, and the rules the registry enforces (names, permissions, no
credentials), are in `modules/README.md`; each module's README lists its own tools.

## Languages (0.5.0, needs Core 0.10.0)

Every text the module shows to people (screens, errors, permissions and the sign-in and consent pages of the OAuth
authorization, which follow the browser language) is available in Spanish (source) and English: the code writes
`tr("Texto en espanol")` and the English catalog is `server/src/main/resources/modules/agentes/i18n/en.json`.
The MCP tools (names, descriptions, results), the OAuth protocol answers meant for the client (`error_description`),
the activity rows already stored and the tool titles shown in the lists stay in Spanish. Token handling, scopes and
permission checks do not change with the language.

## Hardening (0.7.0)

- Public OAuth routes (`register`, `authorize`, `token`, `revoke`) read at most 64 KB; the MCP endpoint reads at most 1 MB without buffering more.
- Redirect URIs (registration and every authorization): https, or http on a loopback host; no user info, wildcards, spaces, backslashes or
  control characters. The `redirect_uri` of an authorization must match one of the client's registered URIs exactly, and errors are only
  ever redirected to such a URI. Clients already registered keep working (the registration is carried in the signed client id and is
  not changed); only a registration holding a URI that is not valid today stops being accepted.
- Sign-in of the OAuth page with two-step verification (Core 0.13.0, TWO_FACTOR.md): an account that has it on gets a second page
  that asks for the authenticator code or a recovery code; the agent is only authorized after Core accepted the password and the
  code together. The second page never contains the password (it stays in the server's memory for at most 5 minutes, bound to the
  OAuth client, its redirect URI and the client address) and ends after 5 wrong codes; Core's per-account code limit applies too.
  The client address of the browser is recorded with the sign-in. Requires Core 0.13.0 (`minCoreVersion`).

## 0.7.1

- When an account turns on two-step verification or resets its password by email, Core tells the module
  (`AccountSecurityEvents`) and every agent connection of that account (access and refresh tokens) is revoked: the agents
  must be connected again. Server accounts' agents (server workspace) are revoked the same way.
- The sign-ins waiting for their code keep at most one entry per user and client address, expired entries are dropped on
  every sign-in, and at most 1000 wait at the same time (past that the page asks to try again in a few minutes).

## 0.7.2

- Each connection records who really opened it (migration V006): the account itself, the server owner or administrator
  behind a switcher session, or the owner of another company of the group behind the business-owner switcher. When
  that person turns on two-step verification or resets the password, their connections are revoked in every active
  company and the server workspace, as well as the account's own ones. A connection opened through a switcher stops
  working (checked on every use, cached 30 s) as soon as that person could no longer enter the company the same way.
- Connections made before 0.7.2 have no recorded origin: they keep working and show "origen desconocido". They cannot
  be told apart reliably afterwards (token creation was not in the activity log, and matching switcher sessions by
  time would blame the business owner for what the server person did, or the other way round), so the business owner
  or the server owner should review them and revoke the ones nobody recognises.
- Needs Core with tenant migration V015 (switch origin of sessions).

## 0.7.3 (needs Core 0.14.0)

`revoke_agent` has a `preview` for the Core's in-app assistant confirmation card (the connection, its user and access). Needs Core 0.14.0
(`McpTool.preview`), so `minCoreVersion` is now 0.14.0.
