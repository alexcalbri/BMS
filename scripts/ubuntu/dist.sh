#!/usr/bin/env bash
# Sourced by install.sh and update.sh (from the root-owned tree): copies a distribution that an unprivileged user
# built, or a downloaded archive, into a root directory without root ever following that user's links, reading
# inside a directory that user controls, or extracting a stream it controls. Needs ROOT_STATE_DIR (a root-only
# directory for temporary files) and die().

# Fails unless the tar archive [file] (root-owned; extra tar options such as -z after it) holds only regular files and
# directories with relative paths that never leave the extraction directory.
bms_check_archive() {
    local archive=$1 entry
    shift
    while IFS= read -r entry; do
        # Primer caracter del modo: - archivo, d directorio; todo lo demas (enlaces, dispositivos...) se rechaza.
        [[ ${entry:0:1} == - || ${entry:0:1} == d ]] || return 1
    done < <(tar "$@" -tvf "$archive") || return 1
    while IFS= read -r entry; do
        [[ $entry != /* && $entry != '..' && $entry != ../* && $entry != */../* && $entry != */.. ]] || return 1
    done < <(tar "$@" -tf "$archive") || return 1
}

# Extracts the checked archive [file] into the root-owned directory [dest] (extra tar options after them), then
# checks again that only files and directories came out.
bms_extract_checked() {
    local archive=$1 dest=$2
    shift 2
    bms_check_archive "$archive" "$@" || return 1
    tar "$@" -C "$dest" -xf "$archive" --no-same-owner --no-same-permissions --no-overwrite-dir || return 1
    [[ -z $(find "$dest" ! -type f ! -type d -print -quit) ]]
}

# copy_dist_as_bms <source dir> <root-owned destination dir> [user that reads the source, default bms]
# The caller makes sure no process of that user is left running (update.sh: stop_build_user_processes).
copy_dist_as_bms() {
    local src=$1 dest=$2 reader=${3:-bms}
    local archive
    # Root no extrae directamente un flujo que controla el usuario de compilacion: se guarda en un archivo de root,
    # se valida el listado y solo entonces se extrae.
    archive=$(mktemp -p "$ROOT_STATE_DIR" dist.XXXXXX.tar) || die 'No se pudo crear el archivo temporal de la distribucion.'
    chmod 600 "$archive"
    if ! runuser -u "$reader" -- tar -C "$src" -cf - . > "$archive"; then
        rm -f -- "$archive"
        die "No se pudo empaquetar la distribucion de $src."
    fi
    if ! bms_extract_checked "$archive" "$dest"; then
        rm -f -- "$archive"
        die "La distribucion de $src contiene enlaces, archivos especiales o rutas no permitidas."
    fi
    rm -f -- "$archive"
}

# M5: the SQL the server applies to companies (database/ and modules/<module>/migrations/ of the release commit), from
# the root-owned mirror [repo] at [commit] into the root-owned [dest] (the installed distribution's migrations/,
# MIGRATIONS_ROOT). The service user cannot change it, unlike /opt/bms/source.
bms_release_migrations() {
    local repo=$1 commit=$2 dest=$3 archive
    archive=$(mktemp -p "$ROOT_STATE_DIR" migrations.XXXXXX.tar) || return 1
    chmod 600 "$archive"
    if ! GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null git -c core.hooksPath=/dev/null -c core.fsmonitor=false -C "$repo" \
        archive --format=tar "$commit" -- database ':(glob)modules/*/migrations/**' > "$archive"; then
        rm -f -- "$archive"
        return 1
    fi
    bms_extract_checked "$archive" "$dest" || { rm -f -- "$archive"; return 1; }
    rm -f -- "$archive"
    [[ -f $dest/database/tenant/migrations/V011__create_activity_log.sql ]]
}
