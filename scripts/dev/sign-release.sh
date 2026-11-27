#!/usr/bin/env bash
# Firma una version de BMS en el computador del dueno. Las llaves privadas nunca salen de ese computador
# ni se guardan en el repositorio; este script no genera llaves, no las imprime y no sube nada solo.
# Procedimiento completo: SIGNING.md.
#
# Uso:
#   scripts/dev/sign-release.sh tag <version> [<commit>]
#       Crea la etiqueta anotada core-v<version> firmada con tu llave SSH sobre <commit> (por defecto HEAD),
#       despues de comprobar que server/build.gradle.kts de ese commit declara esa version. La verifica con la
#       llave publica y muestra el comando para subirla. El ayudante del servidor la acepta como firma del commit.
#   scripts/dev/sign-release.sh packages <llave-privada-ed25519> [<carpeta>...]
#       Escribe extension.sig.json en cada carpeta (por defecto: todos los modulos opcionales de modules/ y
#       todas las extensiones de extensions/) con scripts/extensions/sign.sh. Despues hay que hacer commit.
#   scripts/dev/sign-release.sh allowed-signers [<correo>]
#       Imprime la linea para /usr/local/lib/bms/allowed_signers del servidor con tu llave SSH PUBLICA.
#   scripts/dev/sign-release.sh verify <version> [<commit>]
#       Comprueba, como el servidor, que el commit o la etiqueta core-v<version> tengan tu firma.
#
# La llave SSH se toma de BMS_SSH_SIGNING_KEY (ruta de la llave publica .pub, o de la privada si usas un
# agente) o de "git config user.signingkey".
set -Eeuo pipefail

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

readonly VERSION_PATTERN='^[0-9]+\.[0-9]+\.[0-9]+$'
ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || die 'Ejecutalo dentro del repositorio de BMS.'
readonly ROOT
cd "$ROOT"

signing_key() {
    local key=${BMS_SSH_SIGNING_KEY:-}
    [[ -n $key ]] || key=$(git config --get user.signingkey || true)
    [[ -n $key ]] || die 'Define BMS_SSH_SIGNING_KEY o git config user.signingkey con tu llave SSH (por ejemplo ~/.ssh/id_ed25519.pub).'
    key=${key/#\~/$HOME}
    [[ -f $key ]] || die "No existe la llave SSH $key."
    printf '%s\n' "$key"
}

public_key() {
    local key
    key=$(signing_key)
    if [[ $key == *.pub ]]; then
        cat -- "$key"
    elif [[ -f $key.pub ]]; then
        cat -- "$key.pub"
    else
        die "No encuentro la llave publica de $key (se espera $key.pub)."
    fi
}

principal() {
    local email=${1:-}
    [[ -n $email ]] || email=$(git config --get user.email || true)
    [[ $email =~ ^[^[:space:]]+$ ]] || die 'Indica tu correo (o configura git user.email).'
    printf '%s\n' "$email"
}

# Archivo allowed_signers temporal con tu llave publica, para comprobar como lo hara el servidor.
with_allowed_signers() {
    local tmp
    tmp=$(mktemp)
    printf '%s %s\n' "$(principal)" "$(public_key)" > "$tmp"
    "$@" "$tmp"
    local status=$?
    rm -f -- "$tmp"
    return "$status"
}

commit_of() {
    local commit=${1:-HEAD}
    git rev-parse --verify --quiet "$commit^{commit}" || die "No existe el commit $commit."
}

check_version() {
    local version=$1 commit=$2
    [[ $version =~ $VERSION_PATTERN ]] || die 'La version debe ser X.Y.Z.'
    git show "$commit:server/build.gradle.kts" | grep -Fxq "version = \"$version\"" ||
        die "server/build.gradle.kts de $commit no declara la version $version."
}

verify_with() {
    local version=$1 commit=$2 signers=$3 tag=core-v$1
    local -a git_verify=(git -c gpg.format=ssh -c gpg.ssh.program=ssh-keygen -c gpg.ssh.allowedSignersFile="$signers")
    if git cat-file commit "$commit" | grep -q -- '-----BEGIN SSH SIGNATURE-----' && "${git_verify[@]}" verify-commit "$commit"; then
        printf 'Commit %s firmado: valido.\n' "$commit"
        return 0
    fi
    if [[ $(git cat-file -t "refs/tags/$tag" 2>/dev/null) == tag && $(git rev-parse "refs/tags/$tag^{commit}") == "$commit" ]] &&
        "${git_verify[@]}" verify-tag "refs/tags/$tag"; then
        printf 'Etiqueta %s firmada sobre %s: valida.\n' "$tag" "$commit"
        return 0
    fi
    printf 'Ni el commit %s ni la etiqueta %s tienen una firma valida de tu llave.\n' "$commit" "$tag" >&2
    return 1
}

case ${1:-} in
    tag)
        [[ $# -ge 2 && $# -le 3 ]] || die 'Uso: sign-release.sh tag <version> [<commit>]'
        version=$2
        commit=$(commit_of "${3:-HEAD}")
        check_version "$version" "$commit"
        tag=core-v$version
        ! git rev-parse --verify --quiet "refs/tags/$tag" >/dev/null || die "Ya existe la etiqueta $tag; no se reemplaza."
        git -c gpg.format=ssh -c user.signingkey="$(signing_key)" tag -s -m "BMS Core $version" "$tag" "$commit"
        with_allowed_signers verify_with "$version" "$commit"
        printf '\nSube la etiqueta con:\n  git push origin %s\n' "$tag"
        printf 'Despues pide la actualizacion desde la app: debe mostrar la version %s y el commit %s.\n' "$version" "$commit"
        ;;
    verify)
        [[ $# -ge 2 && $# -le 3 ]] || die 'Uso: sign-release.sh verify <version> [<commit>]'
        commit=$(commit_of "${3:-HEAD}")
        check_version "$2" "$commit"
        with_allowed_signers verify_with "$2" "$commit"
        ;;
    allowed-signers)
        [[ $# -le 2 ]] || die 'Uso: sign-release.sh allowed-signers [<correo>]'
        printf '%s %s\n' "$(principal "${2:-}")" "$(public_key)"
        ;;
    packages)
        [[ $# -ge 2 ]] || die 'Uso: sign-release.sh packages <llave-privada-ed25519> [<carpeta>...]'
        key=$2
        shift 2
        [[ -f $key ]] || die "No existe la llave privada $key."
        key_dir=$(cd "$(dirname -- "$key")" && pwd)
        # La llave de paquetes no puede vivir dentro de un repositorio git (como en scripts/extensions/keygen.sh).
        if git -C "$key_dir" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
            die "La llave privada no puede estar dentro de un repositorio git ($key_dir)."
        fi
        folders=("$@")
        if (( ${#folders[@]} == 0 )); then
            for manifest in modules/*/module.json; do
                # Los modulos base vienen dentro del servidor y no se firman como paquete.
                grep -Eq '"bundled"[[:space:]]*:[[:space:]]*true' "$manifest" || folders+=("$(dirname -- "$manifest")")
            done
            for manifest in extensions/*/extension.json; do
                folders+=("$(dirname -- "$manifest")")
            done
        fi
        (( ${#folders[@]} > 0 )) || die 'No hay carpetas que firmar.'
        for folder in "${folders[@]}"; do
            [[ -f $folder/module.json || -f $folder/extension.json ]] || die "$folder no es un modulo ni una extension."
            if [[ -f $folder/module.json ]]; then
                # sign.sh exige extension.json; el hash y la firma son los mismos para un modulo.
                hash=$(bash scripts/extensions/sign.sh --hash "$folder")
                [[ $hash =~ ^[0-9a-f]{64}$ ]] || die "No se pudo calcular el hash de $folder."
                work=$(mktemp -d)
                # Los 32 bytes del hash, a partir de su hex (igual que sign.sh).
                # shellcheck disable=SC2059
                printf "$(printf '%s' "$hash" | sed 's/../\\x&/g')" > "$work/hash.bin"
                openssl pkeyutl -sign -inkey "$key" -rawin -in "$work/hash.bin" -out "$work/signature.bin"
                signature=$(openssl base64 -A -in "$work/signature.bin")
                rm -rf -- "$work"
                printf '{\n  "packageSha256": "%s",\n  "signature": { "keyId": "%s", "value": "%s" }\n}\n' \
                    "$hash" bms-2026 "$signature" > "$folder/extension.sig.json"
                printf 'Firmado %s (sha256 %s)\n' "$folder" "$hash" >&2
            else
                bash scripts/extensions/sign.sh "$folder" "$key"
            fi
        done
        printf '\nRevisa los extension.sig.json, haz commit, firma la version (tag) y sube ambos.\n'
        ;;
    *)
        sed -n '2,21p' "$0" | sed 's/^# \{0,1\}//'
        exit 2
        ;;
esac
