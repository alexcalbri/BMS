# Signed updates and packages

BMS runs code that comes from the public GitHub repository in two ways:

1. **Core self-update.** The root helper `bms-core-update` (`scripts/ubuntu/core-update.sh`, loaded with
   `scripts/ubuntu/mirror.sh`) builds and installs the Core when the server owner asks from the app.
2. **Modules and extensions.** The server downloads a package folder from the catalog and compiles it in
   the server process.

This document says what is checked today, what the owner's signing keys add, and how to turn
signature enforcement on. **Nothing is signed today, and the live server keeps working unchanged until
a key is installed.** The commit pin (below) is always on.

## 1. What is always checked (no key needed)

### Core update: the approved commit, and nothing else

- `GET /server/core-update` shows `latestVersion`, the exact commit `latestCommit` (40 hex) of the
  configured branch (`CORE_UPDATE_REF` in `/etc/bms/server.env`, default `master`) whose
  `server/build.gradle.kts` declares that version, the branch `ref`, and `signatureRequired`.
- `POST /server/core-update` with `{"targetCommit": "<latestCommit>"}` stores that commit in
  `core_update_requests.target_commit` (control migration V016). If the branch moved since the app read
  it, the answer is 409 `CORE_UPDATE_COMMIT_CHANGED` and nothing is stored. A request without
  `targetCommit` (apps older than 0.11.0) is refused with 400 `CORE_COMMIT_INVALID` unless
  `CORE_UPDATE_REQUIRE_APPROVED_COMMIT=false` is set in `/etc/bms/server.env`; then the server pins the commit it
  reads at that moment.
- The root helper only goes forward: the requested commit must descend from the deployed one
  (`/var/lib/bms/deployed-commit`, written by install.sh and update.sh) and declare a higher version. Without that
  file (a server installed before this) the first update is run once by SSH with `update.sh`.
- The root helper treats the row as untrusted input: the id must be digits, the version `X.Y.Z`, the commit
  a full lowercase 40-hex sha (rows without a commit are refused). Values only reach SQL as psql variables
  and git as separate arguments, never through a shell string.
- The helper fetches the branch into its root-only mirror, checks that the commit **is in the history of
  that branch** (`git merge-base --is-ancestor`), checks out exactly that commit (never the branch tip),
  checks that `server/build.gradle.kts` there declares the requested version, and only then runs
  `update.sh --commit <sha>` from the mirror.
- `server.core.update` is a high-risk permission for server administrators.

### Module and extension packages

- The module catalog is pinned to a commit: a branch URL (`.../tree/master/modules`) is resolved to its
  commit first, the listing and every `module.json` are read at that commit, and the package is
  downloaded from that same commit (`https://codeload.github.com/<owner>/<repo>/zip/<sha>`). The
  extension catalog already worked this way. The inspection shown before "autorizar paquete sin firma"
  reports that commit.
- The module catalog URL must be `https` (port 443, no user or password). Every host is resolved once;
  private, loopback and link-local addresses are refused and the connection goes to the validated IP.
  Redirects are validated and pinned again (at most 3). Module source downloads only follow redirects
  inside GitHub over https.
- An unsigned extension always needs the owner's explicit "autorizar paquete sin firma" for its exact
  hash, after every change. An unsigned module needs it when the gate is on (below) and always when an
  administrator installs it.

## 2. The two keys

| Key | Signs | Private part | Public part on the server |
|---|---|---|---|
| **Release key** (SSH Ed25519) | Core releases: an annotated tag `core-v<version>` (or the commit itself) | Owner's computer only (`~/.ssh/...`) | `/usr/local/lib/bms/allowed_signers` (root, 0644) |
| **Package key** (Ed25519, keyId `bms-2026`) | `extension.sig.json` of each module/extension folder | Owner's computer only, outside any git repository | `/usr/local/lib/bms/package-keys/bms-2026.pub` (root, 0644) |

They can be different keys (recommended). Neither private key is ever copied to the server, committed or
pasted anywhere. The server only needs public keys.

### Release key: what the helper accepts

When `/usr/local/lib/bms/allowed_signers` exists, the helper installs a commit only if:

- the commit itself has a valid **SSH** signature by a key in that file (`git verify-commit`), or
- the annotated tag `core-v<version>` exists on GitHub, points to exactly that commit and has a valid SSH
  signature by a key in that file (`git verify-tag`).

GPG/X.509 signatures and the GitHub web-flow key never count. If the file exists but is not a regular
root-owned file that nobody else can write (or its folder is not), the helper refuses to update. Every
refusal is written to the request (`error`, shown in the app) and to `/var/log/bms-core-update-<id>.log`.
The result of a successful check is stored in `core_update_requests.signature_status`: `verified`, or
`unsigned` when no key is installed.

### Package key: what the server accepts

The server reads `/usr/local/lib/bms/package-keys/<keyId>.pub` (base64 X.509 SubjectPublicKeyInfo, the
output of `scripts/extensions/keygen.sh`) at startup. Files that are not root-owned or that the group or
others can write are ignored. Without a file it falls back to the bundled
`server/src/main/resources/extensions/bms-2026.pub` (empty today). A key can therefore be installed or
rotated with a restart, without a rebuild.

`MODULE_SIGNATURE_ENFORCED` in `/etc/bms/server.env`:

- `true`: an unsigned module needs its hash authorized by the owner; a bad signature is always rejected.
- `false`: unsigned modules install with a warning in the log (administrators still get the strict gate).
- not set: on exactly when a package key is installed (`ModuleSignaturePolicy`); without a key it behaves
  as `false`, as before.

## 3. Operator procedure: create the keys and turn enforcement on

Do steps 1–4 on **your own computer**, in your clone of the repository.

1. **Release key.** Use an existing SSH Ed25519 key or create one (it never leaves your computer):
   `ssh-keygen -t ed25519 -C "bms-release" -f ~/.ssh/bms_release`. Protect it with a passphrase.
   Optionally add it on GitHub as a *signing key* so tags show "Verified" there too.
2. Tell git and the script which key to use:
   `export BMS_SSH_SIGNING_KEY=~/.ssh/bms_release.pub` (or `git config user.signingkey ~/.ssh/bms_release.pub`),
   and set `git config user.email` to the address you will use as the principal.
3. **Package key** (only if you want signed modules/extensions):
   `scripts/extensions/keygen.sh ~/.bms/bms-2026.key` — it refuses a path inside a git repository and
   prints the public key in base64. Keep the private file backed up offline.
4. Print the line for the server: `scripts/dev/sign-release.sh allowed-signers you@example.com`.
5. **On the server, as root** (paste only public keys):

   ```bash
   install -d -m 755 -o root -g root /usr/local/lib/bms /usr/local/lib/bms/package-keys
   # Release key: the line printed in step 4.
   printf '%s\n' 'you@example.com ssh-ed25519 AAAA...' > /usr/local/lib/bms/allowed_signers
   chown root:root /usr/local/lib/bms/allowed_signers && chmod 644 /usr/local/lib/bms/allowed_signers
   # Package key (optional): the base64 printed by keygen.sh.
   printf '%s\n' 'MCowBQYDK2VwAyEA...' > /usr/local/lib/bms/package-keys/bms-2026.pub
   chown root:root /usr/local/lib/bms/package-keys/bms-2026.pub && chmod 644 /usr/local/lib/bms/package-keys/bms-2026.pub
   systemctl restart bms   # loads the package key
   ```

   `openssh-client` (for `ssh-keygen`) must be installed; it is on every Ubuntu server.
   **From this moment the Core only updates to signed releases** and the app stops showing "sin firma".
6. **Every Core release** (on your computer, after merging to `master` and bumping the version):

   ```bash
   git checkout master && git pull
   scripts/dev/sign-release.sh tag 0.13.0          # signs core-v0.13.0 on HEAD after checking the version
   git push origin core-v0.13.0
   ```

   Then press "Actualizar" in the app and check that the version and the commit it shows are the ones the
   script printed. Push the tag **before** requesting the update; a request for an unsigned commit fails
   with "no tiene una firma valida" and can be requested again after pushing the tag (10-minute cooldown).
7. **Signed packages** (optional, after step 3): before the release commit,
   `scripts/dev/sign-release.sh packages ~/.bms/bms-2026.key` (all optional modules and extensions, or
   name folders), review the `extension.sig.json` files, commit, then tag as in step 6. Signed packages
   install without "autorizar paquete sin firma"; a package changed after signing is rejected.
8. **Enforce modules** (after signed packages exist): set `MODULE_SIGNATURE_ENFORCED=true` in
   `/etc/bms/server.env` and `systemctl restart bms`.

`scripts/dev/sign-release.sh verify <version> [<commit>]` checks a commit or tag the same way the server
will.

### Turning it off again (emergency)

`rm /usr/local/lib/bms/allowed_signers` (Core updates go back to pinned-but-unsigned) and/or remove
`/usr/local/lib/bms/package-keys/*.pub` and set `MODULE_SIGNATURE_ENFORCED=false`, then
`systemctl restart bms`. The manual `sudo bash update.sh` from a root directory remains available in any case.

### Rotating or losing a key

Add the new public key as a second line in `allowed_signers` (or a second `<keyId>.pub`), sign the next
release with the new key, then remove the old line. If a private key is lost or leaked, remove its line
on the server first; nothing signed only by it will install afterwards.
