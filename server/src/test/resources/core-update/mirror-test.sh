#!/usr/bin/env bash
# shellcheck disable=SC2015 # pass and fail never fail
# Prueba de la logica del ayudante root (scripts/ubuntu/mirror.sh) con repositorios git temporales:
# commit fijado (formato, pertenencia a la rama) y verificacion de la firma SSH con allowed_signers.
# Uso: mirror-test.sh <raiz-del-repositorio> <directorio-temporal>
# Las llaves SSH son desechables, se crean en el directorio temporal y se borran con el.
set -uo pipefail

ROOT=$1
WORK=$2
export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_TERMINAL_PROMPT=0
# shellcheck source=/dev/null
source "$ROOT/scripts/ubuntu/mirror.sh"

FAILURES=0
pass() { printf 'ok   %s\n' "$1"; }
fail() { printf 'FAIL %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
expect_ok() { local name=$1; shift; if "$@" >/dev/null 2>&1; then pass "$name"; else fail "$name"; fi; }
expect_fail() { local name=$1; shift; if "$@" >/dev/null 2>&1; then fail "$name"; else pass "$name"; fi; }
expect_output() {
    local name=$1 expected=$2 actual
    shift 2
    actual=$("$@" 2>/dev/null) || actual="<fallo>"
    if [[ $actual == "$expected" ]]; then pass "$name"; else fail "$name (esperado '$expected', obtenido '$actual')"; fi
}

cd "$WORK" || exit 2
ssh-keygen -q -t ed25519 -N '' -C owner -f owner_key
ssh-keygen -q -t ed25519 -N '' -C stranger -f stranger_key

G=(git -c user.name=Owner -c user.email=owner@example.com -c gpg.format=ssh -c gpg.ssh.program=ssh-keygen -c init.defaultBranch=master)
"${G[@]}" init -q --bare origin.git
"${G[@]}" init -q work
cd work || exit 2
mkdir -p server
printf 'version = "0.1.0"\n' > server/build.gradle.kts
"${G[@]}" add -A && "${G[@]}" commit -q -m v010
C1=$("${G[@]}" rev-parse HEAD)
printf 'version = "0.2.0"\n' > server/build.gradle.kts
"${G[@]}" commit -q -am v020
C2=$("${G[@]}" rev-parse HEAD)
"${G[@]}" -c user.signingkey="$WORK/stranger_key" commit -q -S --allow-empty -m by-stranger
C_STRANGER=$("${G[@]}" rev-parse HEAD)
"${G[@]}" -c user.signingkey="$WORK/owner_key" commit -q -S --allow-empty -m by-owner
C_OWNER=$("${G[@]}" rev-parse HEAD)
# Etiquetas: firmada por el dueno sobre C2, firmada por el dueno sobre otro commit, ligera, y de un extrano.
"${G[@]}" -c user.signingkey="$WORK/owner_key" tag -s -m release core-v0.2.0 "$C2"
"${G[@]}" -c user.signingkey="$WORK/owner_key" tag -s -m wrong core-v0.3.0 "$C1"
"${G[@]}" tag core-v0.4.0 "$C2"
"${G[@]}" -c user.signingkey="$WORK/stranger_key" tag -s -m stranger core-v0.5.0 "$C2"
# Rama aparte: su commit no esta en master.
"${G[@]}" checkout -q -b other "$C1"
"${G[@]}" commit -q --allow-empty -m side
C_SIDE=$("${G[@]}" rev-parse HEAD)
"${G[@]}" push -q "$WORK/origin.git" master other --tags
cd "$WORK" || exit 2
git clone -q origin.git mirror

# --- commit fijado ---
expect_output 'el commit aprobado de la rama se deja en el espejo' "$C1" mirror_checkout_commit_in "$WORK/mirror" master "$C1"
[[ $(git -C "$WORK/mirror" rev-parse HEAD) == "$C1" ]] && pass 'HEAD queda en ese commit (no en la punta)' || fail 'HEAD queda en ese commit'
grep -Fxq 'version = "0.1.0"' "$WORK/mirror/server/build.gradle.kts" && pass 'el arbol es el del commit aprobado' || fail 'el arbol es el del commit aprobado'
expect_fail 'sha corto' mirror_checkout_commit_in "$WORK/mirror" master "${C1:0:12}"
expect_fail 'sha en mayusculas' mirror_checkout_commit_in "$WORK/mirror" master "${C1^^}"
expect_fail 'texto con comandos' mirror_checkout_commit_in "$WORK/mirror" master "\$(touch $WORK/pwned)"
[[ ! -e $WORK/pwned ]] && pass 'nada se ejecuto' || fail 'nada se ejecuto'
expect_fail 'commit vacio' mirror_checkout_commit_in "$WORK/mirror" master ''
expect_fail 'commit de otra rama' mirror_checkout_commit_in "$WORK/mirror" master "$C_SIDE"
expect_fail 'commit inexistente' mirror_checkout_commit_in "$WORK/mirror" master "0123456789abcdef0123456789abcdef01234567"
expect_fail 'rama invalida' mirror_checkout_commit_in "$WORK/mirror" 'a..b' "$C1"
expect_fail 'rama con opcion' mirror_checkout_commit_in "$WORK/mirror" '--upload-pack=x' "$C1"
expect_output 'el commit de la otra rama vale en esa rama' "$C_SIDE" mirror_checkout_commit_in "$WORK/mirror" other "$C_SIDE"

# --- firma ---
SIGNERS_DIR=$WORK/keys
install -d -m 755 "$SIGNERS_DIR"
SIGNERS=$SIGNERS_DIR/allowed_signers
expect_output 'sin llave instalada: unsigned' unsigned verify_signature_in "$WORK/mirror" "$SIGNERS" "$C2" 0.2.0
printf 'owner@example.com %s\n' "$(cat "$WORK/owner_key.pub")" > "$SIGNERS"
chmod 644 "$SIGNERS"
expect_fail 'con llave: commit sin firma ni etiqueta' verify_signature_in "$WORK/mirror" "$SIGNERS" "$C1" 0.1.0
expect_fail 'con llave: commit firmado por otra llave' verify_signature_in "$WORK/mirror" "$SIGNERS" "$C_STRANGER" 0.9.0
expect_output 'con llave: commit firmado por el dueno' verified verify_signature_in "$WORK/mirror" "$SIGNERS" "$C_OWNER" 0.9.0
expect_output 'con llave: etiqueta firmada core-v<version> sobre el commit' verified verify_signature_in "$WORK/mirror" "$SIGNERS" "$C2" 0.2.0
expect_fail 'con llave: etiqueta firmada que apunta a otro commit' verify_signature_in "$WORK/mirror" "$SIGNERS" "$C2" 0.3.0
expect_fail 'con llave: etiqueta ligera' verify_signature_in "$WORK/mirror" "$SIGNERS" "$C2" 0.4.0
expect_fail 'con llave: etiqueta firmada por otra llave' verify_signature_in "$WORK/mirror" "$SIGNERS" "$C2" 0.5.0
expect_fail 'version invalida' verify_signature_in "$WORK/mirror" "$SIGNERS" "$C2" '0.2.0;id'
expect_fail 'commit invalido' verify_signature_in "$WORK/mirror" "$SIGNERS" "${C2:0:7}" 0.2.0
chmod 664 "$SIGNERS"
expect_fail 'llave que otros pueden escribir: no se actualiza' verify_signature_in "$WORK/mirror" "$SIGNERS" "$C_OWNER" 0.9.0
chmod 644 "$SIGNERS"
chmod 777 "$SIGNERS_DIR"
expect_fail 'carpeta de la llave que otros pueden escribir' verify_signature_in "$WORK/mirror" "$SIGNERS" "$C_OWNER" 0.9.0
chmod 755 "$SIGNERS_DIR"
ln -s "$SIGNERS" "$WORK/link_signers"
expect_fail 'llave que es un enlace' verify_signature_in "$WORK/mirror" "$WORK/link_signers" "$C_OWNER" 0.9.0
# Una configuracion global con otro programa de firma no cambia el resultado.
printf '[gpg "ssh"]\n\tprogram = /bin/true\n[gpg]\n\tprogram = /bin/true\n' > "$WORK/evil.gitconfig"
GIT_CONFIG_GLOBAL=$WORK/evil.gitconfig expect_fail 'un programa de firma de la configuracion global no cuenta' \
    verify_signature_in "$WORK/mirror" "$SIGNERS" "$C1" 0.1.0

# --- solo hacia adelante (M3): el instalado debe ser antecesor del pedido y la version pedida, mayor ---
expect_ok 'de 0.1.0 a 0.2.0 posterior' mirror_forward_ok_in "$WORK/mirror" "$C1" "$C2" 0.2.0
expect_fail 'volver a un commit anterior' mirror_forward_ok_in "$WORK/mirror" "$C2" "$C1" 0.1.0
expect_fail 'commit posterior con la misma version' mirror_forward_ok_in "$WORK/mirror" "$C2" "$C_OWNER" 0.2.0
expect_fail 'version pedida igual a la instalada' mirror_forward_ok_in "$WORK/mirror" "$C1" "$C_SIDE" 0.1.0
expect_fail 'version pedida menor' mirror_forward_ok_in "$WORK/mirror" "$C1" "$C2" 0.0.9
expect_fail 'version 0.10.0 frente a 0.9.x se compara como version' mirror_forward_ok_in "$WORK/mirror" "$C2" "$C_OWNER" 0.1.10
expect_ok '0.10.0 es mayor que 0.2.0' mirror_forward_ok_in "$WORK/mirror" "$C2" "$C_OWNER" 0.10.0
expect_fail 'sin commit instalado conocido' mirror_forward_ok_in "$WORK/mirror" '' "$C2" 0.2.0
expect_fail 'commit instalado que el espejo no tiene' mirror_forward_ok_in "$WORK/mirror" 0123456789abcdef0123456789abcdef01234567 "$C2" 0.2.0
expect_fail 'commit pedido invalido' mirror_forward_ok_in "$WORK/mirror" "$C1" "${C2:0:7}" 0.2.0
expect_output 'version de un commit' 0.2.0 mirror_version_in "$WORK/mirror" "$C2"

printf 'failures=%s\n' "$FAILURES"
(( FAILURES == 0 ))
