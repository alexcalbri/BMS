# Two-step verification (TOTP) — HTTP contract (Core, SECURITY_FIXES.md B3)

Optional per account for every kind of user: server owner, server administrators, business owners and company
users. The server owner can make it mandatory for server administrators. App screens build on this file; servers
older than this Core (0.13.0) do not have these routes (check `isCoreAtLeast(coreVersion, 0, 13, 0)`; a `404` on
`GET /auth/two-factor` also means "not supported").

All bodies are JSON. Errors are always `{code, message}` (`message` translated to the request language). Every
answer carries `Cache-Control: no-store`.

## 1. Algorithm

RFC 6238 TOTP: HMAC-SHA1, 6 digits, 30-second period, secret of 20 random bytes shown as Base32 without
padding. A code is accepted for the current period and one period before/after (±30 s clock drift) and only
once: a code (or an older one) that was already used cannot be used again. Recovery codes: 10 per account,
`XXXXX-XXXXX` (Base32 alphabet `A-Z2-7`, 50 bits each); the server accepts them in any case, with or without the
dash or spaces, and each works once. The secret is stored encrypted with the master-key envelope (`enc:v1:`, data
key of that database); recovery codes are stored only as hashes. No secret or code is ever logged or written to
the activity log.

## 2. Sign-in in two steps

Step 1 is the usual `POST /auth/login {username, password, companyCode?}`. When the password is right and the
account has two-step verification on, no session is opened; the answer is:

```
401 {"code": "TWO_FACTOR_REQUIRED",
     "message": "Escribe el codigo de tu aplicacion de autenticacion.",
     "challengeToken": "<43 chars, base64url>",
     "expiresInSeconds": 300,
     "methods": ["totp", "recovery_code", "email_recovery"]}
```

`"email_recovery"` is listed only when the server has SMTP configured (§7). (Older apps show `message` and stop; that is intended. The message of an older app is the same text.)
A wrong password keeps answering `401 LOGIN_REJECTED` exactly as before: `TWO_FACTOR_REQUIRED` only proves the
password was right.

Step 2: `POST /auth/login/two-factor {challengeToken, code}` (same rate limit as `/auth/login`). `code` is either
the 6-digit code or a recovery code. On success: `200` with the normal `LoginSuccessResponse` of `/auth/login`
(server or company session, same fields), plus `recoveryCodeUsed: true` and `recoveryCodesLeft` when a recovery
code was used (the app should then warn and offer to generate new ones when few are left).

| Status | code | Meaning / what the app does |
|---|---|---|
| 401 | `TWO_FACTOR_INVALID_CODE` | Wrong code. Body also has `attemptsLeft` for this challenge. Ask again. |
| 401 | `TWO_FACTOR_CHALLENGE_EXPIRED` | Unknown, expired or used-up challenge (5 attempts or 5 minutes). Back to step 1. |
| 429 | `TWO_FACTOR_TOO_MANY_ATTEMPTS` | 5 wrong codes for this account in 15 minutes (any challenge, survives restarts). Wait. |
| 400 | `INVALID_REQUEST` | Body is not valid JSON. |
| 503 | `AUTH_UNAVAILABLE` | Database or master key unavailable. |

The challenge is bound to the account, the company and the client address of step 1; a success ends it.

### Every way in asks for the second step

- Company sign-in and server sign-in: as above.
- Company switcher (`POST /auth/switch-company`): the session row records whether THIS session passed a second step
  (authenticator or recovery code at sign-in; a session opened by a switch inherits it; enabling two-step
  verification in a session or finishing an email recovery does not set it). A switch is refused with
  `403 TWO_FACTOR_REAUTH_REQUIRED` "Para entrar ahi tienes que haber iniciado sesion con tu codigo…" when the
  acting session did not pass one and: the target company's owner uses two-step verification (business-owner
  switcher), or the target owner or the server person uses it (server owner/administrator switcher, into a company
  or back to the server). The app shows the message and offers to sign out and in again with the code.
- AI agents (OAuth sign-in page of the Agentes module): Core's old `ModuleAccessService.verifyCredentials` now
  refuses accounts with two-step verification on (they cannot connect agents until the page is updated);
  `ModuleAccessService.signInForAgent(company, user, password, twoFactorCode, clientAddress, allowAuditAdmin)`
  returns `Verified` / `SecondFactorRequired` / `InvalidCode` / `TooManyAttempts` / `Rejected`, so the page
  (modules package) shows a code field after the password and sends both again.
- Password recovery by email code: see §4.
- A server administrator required to use it (§5) and not enrolled yet: see §5.

## 3. Managing it (signed-in user)

Session as for `/auth/profile`: `Authorization: Bearer <token>`, plus `X-Company-Code` for a company session
(none for a server session). Rate limit "admin".

`GET /auth/two-factor` →
```
{"enabled": false, "pending": false, "enabledAt": null, "recoveryCodesLeft": 0,
 "requiredByServer": false, "available": true}
```
`requiredByServer`: this is a server administrator and the owner requires it. `available`: false when the server
cannot store secrets (no `BMS_MASTER_KEY`, or the database lacks migration control V017 / tenant V012).

`POST /auth/two-factor/setup {currentPassword}` → starts (or restarts) enrolment, nothing changes at sign-in yet:
```
{"secret": "JBSWY3DPEHPK3PXP...", "otpauthUri": "otpauth://totp/BMS%20(acme):ana?secret=...&issuer=BMS%20(acme)&algorithm=SHA1&digits=6&period=30",
 "issuer": "BMS (acme)", "accountName": "ana", "algorithm": "SHA1", "digits": 6, "period": 30}
```
The issuer is `BMS (<company code>)`, or `BMS (<server name>)` for server accounts. The app shows the secret
(grouped in fours, copyable) and a QR of `otpauthUri`, then asks for a code.

`POST /auth/two-factor/enable {code}` → `{"recoveryCodes": ["ABCDE-FGHIJ", ... 10], "agentsDisconnected": 1,
"notice": "Se cerraron tus otras sesiones y se desconectaron tus agentes de IA: vuelve a conectarlos."}`. Turns it on,
closes the account's other sessions (the current one stays), for a server account also the company sessions it
opened with the switcher, and every AI agent connection of the account (the app shows `notice`). A password reset
by email does the same for sessions and agents; its message ends with "Se desconectaron tus agentes de IA: vuelve a
conectarlos.". The app must show the codes once, with "copy" / "save" and
"I saved them" before leaving; they cannot be read again.

`POST /auth/two-factor/recovery-codes {currentPassword, code}` (`code` = authenticator code, not a recovery code)
→ `{"recoveryCodes": [...]}`; the old ones stop working.

`POST /auth/two-factor/disable {currentPassword, code}` (authenticator or recovery code) → `204`.

| Status | code | Meaning |
|---|---|---|
| 401 | `INVALID_SESSION` | No valid session. |
| 400 | `PASSWORD_REQUIRED` / `INVALID_PASSWORD` | Current password missing / wrong (shared failure limit with `/auth/profile`). |
| 400 | `TWO_FACTOR_INVALID_CODE` | Wrong or reused code. |
| 429 | `TOO_MANY_ATTEMPTS` / `TWO_FACTOR_TOO_MANY_ATTEMPTS` | Password / code failure limit reached. |
| 409 | `TWO_FACTOR_ALREADY_ENABLED` | `setup` while on (disable first). |
| 409 | `TWO_FACTOR_NOT_PENDING` | `enable` without a `setup`. |
| 409 | `TWO_FACTOR_NOT_ENABLED` | `disable` / `recovery-codes` while off. |
| 403 | `TWO_FACTOR_REQUIRED_BY_SERVER` | An administrator tried to disable it while the owner requires it. |
| 403 | `PASSWORD_CHANGE_REQUIRED` | Administrator with a pending password change. |
| 503 | `TWO_FACTOR_UNAVAILABLE` | Secrets cannot be stored now (`available` false). |

Recorded (never the secret or a code): company accounts in the company's activity log (entity `user`, actions
`enabled` "Activo la verificacion en dos pasos.", `disabled`, `settings_changed` for new recovery codes, and
"Inicio sesion con un codigo de recuperacion." on use); server accounts in `provisioning_audit_log`
(`two_factor.enabled`, `two_factor.disabled`, `two_factor.recovery_codes_regenerated`,
`two_factor.recovery_code_used`).

## 4. Password recovery

`POST /auth/password-reset/confirm {username, companyCode?, code, newPassword, twoFactorCode?}` (new optional
field). For an account with two-step verification on, once the emailed `code` is right:
- without `twoFactorCode` → `400 TWO_FACTOR_REQUIRED` "Escribe tambien el codigo de tu aplicacion de
  autenticacion o un codigo de recuperacion." — the emailed code stays valid (its attempt counted), the app shows
  a second field and sends both again;
- with a wrong one → `400 TWO_FACTOR_INVALID_CODE` (counts in the account's code limit; `429
  TWO_FACTOR_TOO_MANY_ATTEMPTS` when reached);
- with a right one (authenticator or recovery code) → the password changes as before.
An account without it works as before. The server owner without two-step verification keeps email recovery; the
owner then gets an email "Se cambio la contrasena del dueno del servidor" and the audit row
`server_owner.password_recovered` is written.

## 5. Server owner: require it for administrators

`GET /server/two-factor-policy` → `{"requireForAdmins": false}`; `PUT /server/two-factor-policy
{requireForAdmins}` → same body. Owner only (`ServerRoutePolicies`), audited
`server.two_factor_policy_changed`. While required, an administrator without it signs in with the password only
and gets `twoFactorSetupRequired: true` in the `LoginSuccessResponse` (later: `GET /auth/two-factor` shows
`requiredByServer: true, enabled: false`); every server
route except `GET /server/me`, `PUT /auth/password`, `/auth/two-factor*` and logout answers
`403 TWO_FACTOR_SETUP_REQUIRED` "Activa la verificacion en dos pasos para continuar.", the switcher refuses, and
company sessions they opened stop working. The app shows the enrolment screen right after sign-in. Disabling is
refused (`TWO_FACTOR_REQUIRED_BY_SERVER`).

## 6. What the app must show (app package)

1. Sign-in: on `TWO_FACTOR_REQUIRED`, a second screen with one field (numeric keyboard, 6 digits, auto-submit),
   a "Usar un codigo de recuperacion" switch (text field), the remaining attempts on error, back to step 1 on
   `TWO_FACTOR_CHALLENGE_EXPIRED`, a wait message on `429`. After a recovery code, a notice with
   `recoveryCodesLeft`.
2. Account settings (profile screen, every kind of user): "Verificacion en dos pasos" card with state; enrol flow
   (password → QR + secret → code → recovery codes shown once); regenerate codes; disable (password + code).
3. Password recovery: a second field when `TWO_FACTOR_REQUIRED` comes back.
4. Server owner: a switch "Exigir verificacion en dos pasos a los administradores" (Administradores del servidor).
5. Administrators with `twoFactorSetupRequired`: the enrolment screen before anything else.
6. The Agentes OAuth page (module package) gets the code field from Core; nothing in the app.


## 7. Delayed recovery by email (lost authenticator and recovery codes)

The everyday fallback stays the recovery codes; there is no email code as a second factor. A person who knows
the password but has neither the authenticator nor a recovery code can start a **delayed** recovery: the account's
mailbox gets a warning with a cancel link at once, and only **7 days later** a one-time code that, entered at the
second step, signs in once and turns two-step verification off (the person must enrol again). Every account type
that can have two-step verification can use it. It needs the server's SMTP (`mail.*` settings); without it the
option is not offered (`"email_recovery"` missing from `methods`, `emailRecovery.available` false). Feature
detection: the field `emailRecovery` in `GET /auth/two-factor` (Core 0.13.0, the same release as the rest of this file).

### 7.1 Start — `POST /auth/login/two-factor/email-recovery {challengeToken}`

Same rate limit as `/auth/login`; needs the challenge of step 1 (right password) from the same client address,
and counts one attempt of it (the challenge stays usable for codes). Answer, always the same once the challenge is
valid (whether a process was started now, one was already open, the account has no valid email, or starts are
throttled — at most 3 starts per account in 7 days):

```
202 {"status": "requested",
     "message": "Si la cuenta tiene un correo valido, enviamos un aviso. El codigo para entrar llegara a ese correo dentro de 7 dias; desde el aviso se puede cancelar.",
     "waitDays": 7}
```

| Status | code | Meaning |
|---|---|---|
| 401 | `TWO_FACTOR_CHALLENGE_EXPIRED` | Unknown/expired/used-up challenge: back to step 1. |
| 503 | `MAIL_NOT_CONFIGURED` | The server has no SMTP (the app should not have offered it). |
| 400 | `INVALID_REQUEST` | Body is not valid JSON. |

When a process really starts: at most one open process per account; the account's email (as it is now) gets
"Se inicio la recuperacion del acceso" with the start time (UTC), the client address of step 1, the date the code
will be sent, and a cancel link `<public base>/auth/two-factor/recovery-cancel?t=<token>` (`&c=<company code>` for
a company account). The public base is `BMS_PUBLIC_BASE_URL` when set, else the request's Host only when it is a
domain of this server (`public_domains`); otherwise the warning has no link and says to cancel from the account
section (Verificacion en dos pasos). The start's work and the email run in the background (every outcome answers
alike); a warning that could not be sent is sent again by the job every 15 minutes, each time with a new link. An
in-app notification is added (company accounts: their company; server accounts: the server workspace, when it
exists). The token is 32 random bytes (base64url), single use, stored only as its SHA-256.

### 7.2 Cancel by the link (no sign-in, works in any browser) — server-rendered page

- `GET /auth/two-factor/recovery-cancel?t=<token>[&c=<code>]` → `200 text/html`: a minimal page "¿Cancelar la
  recuperacion del acceso?" with one form button that POSTs to the same path (hidden `t`, `c`). It changes
  nothing (mail scanners that prefetch links cancel nothing). An unknown, used or finished token shows "Este
  enlace ya no es valido" (still 200). With `Accept: application/json`: `{"valid": true|false}`.
- `POST /auth/two-factor/recovery-cancel` (form `t`, `c`, `application/x-www-form-urlencoded`) → `200 text/html`
  "Se cancelo la recuperacion del acceso. Si no la pediste tu, cambia tu contrasena." or the "no longer valid"
  page. With `Accept: application/json`: `{"cancelled": true|false}`.
- Headers on both: `Cache-Control: no-store`, `Content-Security-Policy: default-src 'none'; style-src
  'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'`, `X-Frame-Options: DENY`,
  `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`. Same rate limit as `/auth/login`. The page is
  in the account's language when the token is valid, else the request's.

### 7.3 Signed in — the account section

`GET /auth/two-factor` gains:
```
"emailRecovery": {"available": true,
                  "pending": {"startedAt": "2026-11-27T10:00:00Z", "startedFrom": "203.0.113.7",
                              "codeDueAt": "2026-12-04T10:00:00Z", "codeSent": false, "codeExpiresAt": null}}
```
(`pending` null when there is none; `available`: SMTP configured, the account has two-step verification on and a
valid email.) `DELETE /auth/two-factor/email-recovery` (same session rules as §3) cancels the open process →
`204`, or `409 EMAIL_RECOVERY_NOT_PENDING`. The app shows, when `pending` is set, a warning card "Alguien inicio la
recuperacion del acceso por correo el … desde …; el codigo se enviara el …" with a "Cancelar" button.

### 7.4 After 7 days — the code, and finishing

A server job (every 10 minutes, idempotent) sends the second email "Tu codigo para recuperar el acceso" with a
one-time code `XXXX-XXXX-XXXX` (Base32, 60 bits, stored hashed), valid 24 hours. At most one valid code exists at
any time (a lost send is re-issued with a new code that replaces the old). Unused for 24 h, the process ends
(`expired`); a new one may be started.

`POST /auth/login/two-factor/email-recovery/complete {challengeToken, code}` (same rate limit as `/auth/login`, a
fresh step-1 challenge) → `200` normal `LoginSuccessResponse` plus `"twoFactorDisabled": true`: two-step
verification of the account is off (secret and recovery codes deleted), this one session is open, and the app
should take the person to enrolment (an administrator the owner requires to use it also gets
`twoFactorSetupRequired: true`). Errors: `401 TWO_FACTOR_INVALID_CODE` (with `attemptsLeft` of the challenge; also
for "no code issued", "expired" or "wrong account"), `401 TWO_FACTOR_CHALLENGE_EXPIRED`, `429
TWO_FACTOR_TOO_MANY_ATTEMPTS` (5 wrong codes per account in 15 minutes, as for other codes).

### 7.5 What ends a process

- The cancel link, or `DELETE /auth/two-factor/email-recovery` → `cancelled` (by link / by the signed-in user).
- Any full sign-in with the authenticator or a recovery code → cancelled automatically; the login response then
  has `"emailRecoveryCancelled": true` (the app says "Se cancelo la recuperacion por correo que estaba en curso")
  and the account gets an email saying so.
- A change of the account's email address (any path: the check runs on the change through `/auth/profile`, when
  the code is sent and when it is entered) → cancelled.
- Turning two-step verification off → cancelled. A password reset does not shorten or skip the wait.

Recorded (never the token or the code): company accounts in the activity log (entity `user`), server accounts in
`provisioning_audit_log`: `two_factor.email_recovery_started`, `…_cancelled` (details `by`: `link`, `user`,
`sign_in`, `email_change`, `disabled`), `…_code_sent`, `…_completed`, `…_expired`.

### 7.6 What the app shows at the second step

Besides the code field and "Usar un codigo de recuperacion": when `methods` has `"email_recovery"`, a link "No
tengo acceso a mi autenticador ni a mis codigos" → explanation (7 days, warning email with cancel link, two-step
verification is turned off when finished) → "Iniciar recuperacion por correo" (calls 7.1, shows its `message`),
and a field "Tengo el codigo del correo" (calls 7.4 with a fresh challenge: the app keeps the password from step 1
only in memory, or asks for it again).

## 8. Turning it off for someone else (lost authenticator and recovery codes)

When a person lost their authenticator and their recovery codes, someone responsible for the account can turn
two-step verification off for them; the person then signs in with the password only and should enrol again.

### 8.1 Who may do it

| Actor | Targets | Route |
|---|---|---|
| Business owner of a company, own session | any other user of THAT company (not themself; staff never) | `POST /companies/me/users/{userId}/two-factor/disable` |
| Business owner of another company of the owner group, through the business-owner switcher | the users of the company it entered, never its business owner | same company route; password of its OWN account |
| Server owner, or administrator with `server.users.two_factor_reset` and the company in scope, inside the company through the server switcher | any user of the company, its business owner too (administrator: whole owner group in scope) | same company route; password of the SERVER account, recorded as the server person |
| Server owner | any user of any company, business owners included | `POST /server/companies/{code}/users/{userId}/two-factor/disable` |
| Server administrator with `server.users.two_factor_reset` (new, **high risk**, company scope applies) | users of companies in scope; a business owner only when every company of its owner group is in scope | same |
| Server owner only | a server administrator | `POST /server/admins/{id}/two-factor/disable` |

Nobody can reset the server owner's two-step verification this way (the owner has recovery codes and the delayed
email recovery, §7). An administrator can never reset an administrator, themself included (the admins route is
owner only). In a company session opened by a switcher, the real person behind it acts: the second-step mark of
that session (inherited from the session it was switched from) is required when that person uses two-step
verification, and the throttle is that person's. Routes are in `ServerRoutePolicies` (company route under
`/companies/me` is business-owner only like the rest of user management).

### 8.2 Request and answer

Body `{"currentPassword": "…"}` (the actor's own password, re-authentication). Session as usual (`Bearer`, plus
`X-Company-Code` for the company route). Answer `200`:
```
{"userId": "…", "twoFactorEnabled": false, "sessionsClosed": 2, "agentsDisconnected": 1,
 "agentsDisconnectFailed": false, "message": "Se desactivo la verificacion en dos pasos de ana. …"}
```

| Status | code | Meaning |
|---|---|---|
| 400 | `PASSWORD_REQUIRED` / `INVALID_PASSWORD` | The actor's password is missing / wrong. |
| 429 | `TOO_MANY_ATTEMPTS` | Password failures, or more than 10 resets by this actor in an hour. |
| 403 | `TWO_FACTOR_REAUTH_REQUIRED` | The actor uses two-step verification but this session did not pass a second step: sign in again with the code. |
| 403 | `BUSINESS_OWNER_REQUIRED` / `SERVER_PERMISSION_REQUIRED` / `SERVER_OWNER_REQUIRED` | Not allowed (wrong actor, out of scope, own account, server owner as target). |
| 404 | `USER_NOT_FOUND` | No such user in that company / no such administrator. |
| 409 | `TWO_FACTOR_NOT_ENABLED` | The target does not have it on. |
| 503 | `TWO_FACTOR_UNAVAILABLE` | Database/migration unavailable. |

### 8.3 Effect

On the target: secret and recovery codes deleted; a pending email recovery cancelled (`by: reset`); ALL its
sessions closed (for an administrator also the company sessions it opened with the switcher); its AI agents
disconnected (Core hook, as when it turns it on); an email to the target (its language) and an in-app notification
saying who did it and when. A reset administrator whom the owner requires to use it gets the setup-required flow at
the next sign-in (§5). Recorded without any secret: the company's activity log for company users ("Desactivo la
verificacion en dos pasos de …"), and always the server audit `two_factor.reset_by_other` with the actor's kind,
the target and the company.

### 8.4 Listings (optional fields; older apps ignore them)

- `GET /companies/me/users` → each `CompanyUserResponse` gains `twoFactorEnabled: Boolean?` (null when unknown,
  e.g. before the migration) and `canResetTwoFactor: Boolean?` (whether THIS caller may turn it off for that row
  now, with every rule above; the app shows the button only when it is `true`).
- `GET /companies/{code}/business-owner` → `BusinessOwnerResponse.twoFactorEnabled: Boolean?` and `userId: String?`
  (for the server route).
- `GET /server/admins`, `GET /server/admins/{id}` → `ServerAdminResponse.twoFactorEnabled: Boolean?`.
The app shows the state and, for the actors of §8.1 and only when it is `true`, a "Desactivar verificacion en dos
pasos" button that asks for the actor's password and warns that the person's sessions and agents are closed.

## 9. Replacing the authenticator (and the "last recovery code" case)

Every session remembers how it passed its second step (`totp`, `recovery_code`, or `inherited` from the session
it was switched from) and when.

- Sign-in with the LAST recovery code: the `LoginSuccessResponse` has `recoveryCodeUsed: true`,
  `recoveryCodesLeft: 0` and `lastRecoveryCodeUsed: true`; the app must take the person to "Configurar un nuevo
  autenticador" at once.
- `GET /auth/two-factor` gains `secondFactorMethod` (this session; null when none) and `replaceWindowUntil` (ISO
  instant, null when closed): a session opened with a recovery code may replace the authenticator with the
  password alone for 15 minutes after its sign-in.

`POST /auth/two-factor/replace/setup {currentPassword, code?}` → same body as `/auth/two-factor/setup` (secret,
otpauthUri…) for the NEW authenticator; the current one keeps working until confirmed. `code` (current
authenticator code or a recovery code, which is then spent) is required unless `replaceWindowUntil` is open; without
it → `400 TWO_FACTOR_CODE_REQUIRED`.

`POST /auth/two-factor/replace/confirm {code}` (a code of the NEW authenticator) → `{"recoveryCodes": [10 new],
"agentsDisconnected", "notice", "agentsDisconnectFailed"}`: the new secret replaces the old one, every old recovery
code stops working, the account's other sessions close, its AI agents are disconnected, and the account gets an
email and an in-app notice; recorded as `two_factor.authenticator_replaced` (server audit for server accounts,
activity log for company accounts). Errors: `409 TWO_FACTOR_NOT_PENDING` (no setup), `400 TWO_FACTOR_INVALID_CODE`,
`429 TWO_FACTOR_TOO_MANY_ATTEMPTS`, plus those of §3.

Unchanged: `POST /auth/two-factor/recovery-codes` still needs an authenticator code (after replacing, the person has
one); `disable` still needs an authenticator or recovery code.

The app shows, in the two-step section: "Cambiar de autenticador" (password, and a code unless the window is open;
then the QR/secret of the new one, a code from it, and the new recovery codes once).
