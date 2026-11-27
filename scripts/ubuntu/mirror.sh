#!/usr/bin/env bash
# Espejo git de root para BMS. Lo cargan con "source" los scripts que corren como root
# (core-update.sh y update.sh); no se ejecuta solo.
#
# El espejo (/var/lib/bms/mirror) es un clon que solo escribe root. Todo lo que root ejecuta o
# lee al actualizar sale de aqui, nunca de /opt/bms/source (lo puede escribir el usuario bms).
#
# Firma (SIGNING.md): si existe el archivo de root $MIRROR_ALLOWED_SIGNERS (formato allowed_signers
# de ssh-keygen), un commit solo se acepta con una firma SSH valida de una de esas llaves, en el
# propio commit o en la etiqueta anotada core-v<version> que apunta a el. Sin ese archivo no se exige
# firma (como hasta ahora) y el resultado es "unsigned".

readonly REPO_URL=https://github.com/alexcalbri/BMS.git
readonly MIRROR_STATE_DIR=/var/lib/bms
readonly MIRROR_DIR=$MIRROR_STATE_DIR/mirror
readonly MIRROR_REF_PATTERN='^[A-Za-z0-9][A-Za-z0-9._/-]{0,100}$'
readonly MIRROR_COMMIT_PATTERN='^[0-9a-f]{40}$'
readonly MIRROR_VERSION_PATTERN='^[0-9]+\.[0-9]+\.[0-9]+$'
readonly MIRROR_ALLOWED_SIGNERS=/usr/local/lib/bms/allowed_signers

# Git dentro del espejo, sin hooks, sin fsmonitor y sin pedir credenciales.
mirror_git() {
    mirror_git_in "$MIRROR_DIR" "$@"
}

# Igual que mirror_git, en el repositorio <dir> (las pruebas usan un repositorio temporal).
# Ignora la configuracion global y del sistema: ningun programa de firma ajeno decide la verificacion.
mirror_git_in() {
    local dir=$1
    shift
    GIT_TERMINAL_PROMPT=0 GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null \
        git -c core.hooksPath=/dev/null -c core.fsmonitor=false -C "$dir" "$@"
}

mirror_valid_ref() {
    local ref=${1:-}
    [[ $ref =~ $MIRROR_REF_PATTERN && $ref != *..* && $ref != *.lock ]]
}

# Clona el espejo si falta y comprueba que su remoto sea el repositorio oficial.
mirror_prepare() {
    install -d -m 700 -o root -g root "$MIRROR_STATE_DIR" || return 1
    if [[ ! -d $MIRROR_DIR/.git ]]; then
        rm -rf --one-file-system -- "$MIRROR_DIR" || return 1
        GIT_TERMINAL_PROMPT=0 GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null \
            git -c core.hooksPath=/dev/null -c core.fsmonitor=false \
            clone --quiet "$REPO_URL" "$MIRROR_DIR" || return 1
    fi
    chmod 700 "$MIRROR_DIR" || return 1
    [[ $(mirror_git remote get-url origin) == "$REPO_URL" ]] || return 1
}

# Deja el espejo exactamente en <rama, etiqueta o sha de 40 hex> de origin e imprime el sha resultante.
# Cada paso usa || return para que set -e no se suspenda cuando se llama dentro de un "if".
sync_mirror() {
    local ref=${1:-}
    if ! mirror_valid_ref "$ref"; then
        printf 'Referencia Git invalida para el espejo.\n' >&2
        return 1
    fi
    mirror_prepare || return 1
    mirror_git fetch --quiet --no-tags origin "$ref" || return 1
    mirror_git checkout --quiet --force --detach FETCH_HEAD || return 1
    mirror_git clean -ffdxq || return 1
    mirror_git rev-parse HEAD
}

# Deja el espejo exactamente en <commit> (40 hex), que debe estar en la historia de <rama> de origin.
# Nunca usa la punta de la rama: si alguien publica otro commit despues de aprobar, no se instala.
sync_mirror_commit() {
    local ref=${1:-} commit=${2:-}
    mirror_prepare || return 1
    mirror_checkout_commit_in "$MIRROR_DIR" "$ref" "$commit"
}

# Logica de sync_mirror_commit sobre el repositorio <dir> (con remoto origin ya configurado).
mirror_checkout_commit_in() {
    local dir=$1 ref=${2:-} commit=${3:-} tip head
    if ! mirror_valid_ref "$ref"; then
        printf 'Referencia Git invalida para el espejo.\n' >&2
        return 1
    fi
    if [[ ! $commit =~ $MIRROR_COMMIT_PATTERN ]]; then
        printf 'El commit debe ser un sha completo de 40 caracteres hexadecimales.\n' >&2
        return 1
    fi
    mirror_git_in "$dir" fetch --quiet --no-tags origin "$ref" || return 1
    tip=$(mirror_git_in "$dir" rev-parse --verify --quiet 'FETCH_HEAD^{commit}') || return 1
    if ! mirror_git_in "$dir" cat-file -e "$commit^{commit}" 2>/dev/null ||
        ! mirror_git_in "$dir" merge-base --is-ancestor "$commit" "$tip"; then
        printf 'El commit %s no pertenece a la rama %s de %s.\n' "$commit" "$ref" "$REPO_URL" >&2
        return 1
    fi
    mirror_git_in "$dir" checkout --quiet --force --detach "$commit" || return 1
    mirror_git_in "$dir" clean -ffdxq || return 1
    head=$(mirror_git_in "$dir" rev-parse HEAD) || return 1
    if [[ $head != "$commit" ]]; then
        printf 'El espejo no quedo en el commit pedido.\n' >&2
        return 1
    fi
    printf '%s\n' "$head"
}

# Verdadero si <archivo> es un archivo normal (no enlace) de este usuario (root en el servidor) que nadie
# mas puede escribir, dentro de un directorio con las mismas condiciones.
mirror_trusted_file() {
    local file=$1 dir mode item
    [[ -f $file && ! -L $file ]] || return 1
    dir=$(dirname -- "$file")
    [[ -d $dir && ! -L $dir ]] || return 1
    for item in "$file" "$dir"; do
        [[ $(stat -c %u -- "$item") == "$EUID" ]] || return 1
        mode=$(stat -c %a -- "$item")
        (( (8#$mode & 8#022) == 0 )) || return 1
    done
}

# Comprueba la firma del commit con la llave de root. Imprime "verified" o "unsigned".
verify_mirror_signature() {
    verify_signature_in "$MIRROR_DIR" "$MIRROR_ALLOWED_SIGNERS" "$@"
}

# Logica de verify_mirror_signature sobre <dir> y el archivo allowed_signers <signers>.
# Sin <signers> instalado imprime "unsigned" y no falla. Con el instalado falla si ni el commit ni la
# etiqueta anotada core-<version> (que debe apuntar a ese commit) tienen una firma SSH valida de esas llaves.
verify_signature_in() {
    local dir=$1 signers=$2 commit=${3:-} version=${4:-} tag tagged
    if [[ ! $commit =~ $MIRROR_COMMIT_PATTERN || ! $version =~ $MIRROR_VERSION_PATTERN ]]; then
        printf 'Commit o version invalidos para verificar la firma.\n' >&2
        return 1
    fi
    if [[ ! -e $signers && ! -L $signers ]]; then
        printf 'unsigned\n'
        return 0
    fi
    if ! mirror_trusted_file "$signers"; then
        printf 'La llave de firma %s existe pero no es un archivo solo de root; no se actualiza.\n' "$signers" >&2
        return 1
    fi
    command -v ssh-keygen >/dev/null 2>&1 || {
        printf 'Falta ssh-keygen (paquete openssh-client) para verificar la firma.\n' >&2
        return 1
    }
    # Solo firmas SSH: gpg y x509 quedan desactivados para que un llavero de root no cuente.
    local -a verify=(-c gpg.ssh.allowedSignersFile="$signers" -c gpg.ssh.program=ssh-keygen
        -c gpg.program=/bin/false -c gpg.x509.program=/bin/false -c gpg.minTrustLevel=fully)
    if mirror_git_in "$dir" cat-file commit "$commit" 2>/dev/null | grep -q -- '-----BEGIN SSH SIGNATURE-----' &&
        mirror_git_in "$dir" "${verify[@]}" verify-commit "$commit" >&2; then
        printf 'verified\n'
        return 0
    fi
    tag=core-v$version
    if mirror_git_in "$dir" fetch --quiet --no-tags --force origin "refs/tags/$tag:refs/tags/$tag" 2>/dev/null &&
        [[ $(mirror_git_in "$dir" cat-file -t "refs/tags/$tag" 2>/dev/null) == tag ]]; then
        tagged=$(mirror_git_in "$dir" rev-parse --verify --quiet "refs/tags/$tag^{commit}") || tagged=''
        if [[ $tagged == "$commit" ]] &&
            mirror_git_in "$dir" cat-file tag "refs/tags/$tag" | grep -q -- '-----BEGIN SSH SIGNATURE-----' &&
            mirror_git_in "$dir" "${verify[@]}" verify-tag "refs/tags/$tag" >&2; then
            printf 'verified\n'
            return 0
        fi
    fi
    printf 'El commit %s no tiene una firma valida de la llave instalada en %s (ni en el commit ni en la etiqueta %s).\n' \
        "$commit" "$signers" "$tag" >&2
    return 1
}

# Version declared by <commit> in <dir> (server/build.gradle.kts), or nothing.
mirror_version_in() {
    local dir=$1 commit=$2
    mirror_git_in "$dir" show "$commit:server/build.gradle.kts" 2>/dev/null |
        sed -nE 's/^version = "([0-9]+\.[0-9]+\.[0-9]+)"$/\1/p' | head -n 1
}

# Only forward (M3 of SECURITY_FIXES.md): a request may install <target> only when the deployed commit <deployed> is
# an ancestor of it and <target_version> is strictly higher than the deployed one; otherwise a forged request row
# could bring back an old commit and run its old update.sh as root. Prints the reason and fails when refused.
mirror_forward_ok_in() {
    local dir=$1 deployed=${2:-} target=${3:-} target_version=${4:-} deployed_version highest
    if [[ ! $deployed =~ $MIRROR_COMMIT_PATTERN ]]; then
        printf 'No se sabe que commit esta instalado (falta %s/deployed-commit): ejecuta update.sh una vez por SSH.\n' "$MIRROR_STATE_DIR" >&2
        return 1
    fi
    if [[ ! $target =~ $MIRROR_COMMIT_PATTERN || ! $target_version =~ $MIRROR_VERSION_PATTERN ]]; then
        printf 'Commit o version invalidos.\n' >&2
        return 1
    fi
    if ! mirror_git_in "$dir" cat-file -e "$deployed^{commit}" 2>/dev/null ||
        ! mirror_git_in "$dir" merge-base --is-ancestor "$deployed" "$target"; then
        printf 'El commit %s no es posterior al instalado (%s): no se vuelve atras desde la app.\n' "$target" "$deployed" >&2
        return 1
    fi
    deployed_version=$(mirror_version_in "$dir" "$deployed")
    if [[ ! $deployed_version =~ $MIRROR_VERSION_PATTERN ]]; then
        printf 'No se pudo leer la version del commit instalado %s.\n' "$deployed" >&2
        return 1
    fi
    highest=$(printf '%s\n%s\n' "$deployed_version" "$target_version" | sort -V | tail -n 1)
    if [[ $target_version == "$deployed_version" || $highest != "$target_version" ]]; then
        printf 'La version %s no es mayor que la instalada (%s).\n' "$target_version" "$deployed_version" >&2
        return 1
    fi
}
