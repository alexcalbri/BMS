#!/usr/bin/env bash
# Sourced by install.sh and update.sh (from the root-owned tree): copies a distribution that an unprivileged user
# built into a root directory without root ever following that user's links or extracting a stream it controls.
# Needs ROOT_STATE_DIR (a root-only directory for the temporary archive) and die().

# copy_dist_as_bms <source dir> <root-owned destination dir> [user that reads the source, default bms]
copy_dist_as_bms() {
    local src=$1 dest=$2 reader=${3:-bms}
    local archive entry
    # Root no extrae directamente un flujo que controla el usuario de compilacion: se guarda en un archivo de root,
    # se valida el listado y solo entonces se extrae.
    archive=$(mktemp -p "$ROOT_STATE_DIR" dist.XXXXXX.tar) || die 'No se pudo crear el archivo temporal de la distribucion.'
    chmod 600 "$archive"
    if ! runuser -u "$reader" -- tar -C "$src" -cf - . > "$archive"; then
        rm -f -- "$archive"
        die "No se pudo empaquetar la distribucion de $src."
    fi
    while IFS= read -r entry; do
        # Primer caracter del modo: - archivo, d directorio; todo lo demas se rechaza.
        if [[ ${entry:0:1} != - && ${entry:0:1} != d ]]; then
            rm -f -- "$archive"
            die "La distribucion de $src contiene enlaces o archivos especiales."
        fi
    done < <(tar -tvf "$archive")
    while IFS= read -r entry; do
        if [[ $entry == /* || $entry == '..' || $entry == ../* || $entry == */../* || $entry == */.. ]]; then
            rm -f -- "$archive"
            die "La distribucion de $src contiene rutas no permitidas."
        fi
    done < <(tar -tf "$archive")
    tar -C "$dest" -xf "$archive" --no-same-owner --no-same-permissions --no-overwrite-dir || {
        rm -f -- "$archive"
        die "No se pudo extraer la distribucion de $src."
    }
    rm -f -- "$archive"
    if [[ -n $(find "$dest" ! -type f ! -type d -print -quit) ]]; then
        die "La distribucion de $src contiene enlaces o archivos especiales."
    fi
}
