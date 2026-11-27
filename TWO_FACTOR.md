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
- Business-owner company switcher (`POST /auth/switch-company` between companies of one owner group): switching
  into a business owner account that has two-step verification on is refused (`403 SWITCH_REJECTED`) unless the
  account the session belongs to has it on too. The server owner/administrator switcher is unchanged (the
  server person is the one who signed in; their own second step applies to their server sign-in).
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

`POST /auth/two-factor/enable {code}` → `{"recoveryCodes": ["ABCDE-FGHIJ", ... 10]}`. Turns it on, closes the
account's other sessions (the current one stays). The app must show the codes once, with "copy" / "save" and
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
a company account). The public base is `BMS_PUBLIC_BASE_URL` when set, else the request's validated Host. An
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
