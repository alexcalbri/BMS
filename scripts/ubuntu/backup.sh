#!/usr/bin/env bash
# Backups of BMS (SELF_HOSTING.md section 14), as root:
#   bms-backup                 daily (bms-backup.timer): pg_dump of every BMS database, PostgreSQL roles, the company
#                              files, /etc/bms and the Nginx site into /var/lib/bms/db-backups/<UTC time> (root only),
#                              keeping BACKUP_KEEP (default 7) copies, then the optional off-box copy.
#   backup.sh --install        installs this script as /usr/local/sbin/bms-backup with its timer (install.sh, update.sh).
#   sourced                    bms_dump_databases <dir> <control db>: the same database dump, used by update.sh before
#                              it migrates anything.
#
# Optional settings in /etc/bms/backup.conf (root, mode 600; KEY=value lines, read, never executed):
#   BACKUP_KEEP=7                       copies kept here
#   BACKUP_RCLONE_REMOTE=remote:bms     off-box copy with rclone (configured by root: rclone config)
#   BACKUP_RSYNC_TARGET=user@host:/dir  or with rsync over ssh
#   BACKUP_RSYNC_SSH_KEY=/root/.ssh/bms-backup
#   BACKUP_OFFSITE_INCLUDE_SECRETS=false  true also sends etc-bms-secret.tar.gz (BMS_MASTER_KEY and passwords) and
#                                         db/roles.sql (the PostgreSQL password verifiers)
# The off-box copy never carries the master key unless told to: keep it apart (SELF_HOSTING.md, master key escrow).

# Sourced or run: the checks here never rely on the caller's locale (bms_ascii_match, LC_ALL=C on sort and sed).
# [[ =~ ]] with LC_ALL=C for this one test, whatever the caller's locale: [a-z], [0-9a-f] or [!-~] mean ASCII.
bms_ascii_match() { local LC_ALL=C; [[ $1 =~ $2 ]]; }

readonly BMS_BACKUP_ROOT=/var/lib/bms/db-backups
readonly BMS_BACKUP_CONF=/etc/bms/backup.conf
readonly BMS_BACKUP_SERVER_ENV=/etc/bms/server.env
readonly BMS_BACKUP_LOG=/var/log/bms-backup.log

# Databases to dump: the control database and every database named bms_* (company databases, even unregistered).
bms_backup_databases() {
    local control_db=$1
    runuser -u postgres -- psql -X -At -d postgres -v control="$control_db" <<'SQL'
SELECT datname FROM pg_catalog.pg_database
WHERE NOT datistemplate AND (datname = :'control' OR datname ~ '^bms_[a-z0-9_]{1,59}$')
ORDER BY datname = :'control' DESC, datname;
SQL
}

# Bytes the databases take now (an upper bound of their dumps).
bms_backup_size() {
    local control_db=$1
    runuser -u postgres -- psql -X -At -d postgres -v control="$control_db" <<'SQL'
SELECT COALESCE(sum(pg_catalog.pg_database_size(oid)), 0) FROM pg_catalog.pg_database
WHERE NOT datistemplate AND (datname = :'control' OR datname ~ '^bms_[a-z0-9_]{1,59}$');
SQL
}

# True when the file system of [dir] keeps at least 2 GiB free after writing [bytes] (a full disk would stop
# PostgreSQL, which usually lives on the same disk).
bms_backup_space_ok() {
    local dir=$1 bytes=$2 free
    free=$(df -P -B1 -- "$dir" | awk 'NR == 2 { print $4 }')
    bms_ascii_match "$free" '^[0-9]+$' && bms_ascii_match "$bytes" '^[0-9]+$' || return 1
    (( free >= bytes + 2147483648 ))
}

# Dumps every BMS database (custom format) and the roles into [dir] (created root-only). Fails on the first error.
bms_dump_databases() {
    local dir=$1 control_db=$2 database
    install -d -m 700 -o root -g root "$dir" || return 1
    (
        umask 077
        runuser -u postgres -- pg_dumpall --roles-only > "$dir/roles.sql" || exit 1
        while IFS= read -r database; do
            [[ -n $database ]] || continue
            runuser -u postgres -- pg_dump -Fc -d "$database" > "$dir/$database.dump" || exit 1
        done < <(bms_backup_databases "$control_db")
        sha256sum -- "$dir"/*.dump "$dir/roles.sql" > "$dir/SHA256SUMS" || exit 1
    )
}

# KEY=value from backup.conf (no shell evaluation).
bms_backup_setting() {
    [[ -f $BMS_BACKUP_CONF ]] || return 0
    awk -F= -v key="$1" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "$BMS_BACKUP_CONF"
}

bms_backup_log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" | tee -a "$BMS_BACKUP_LOG" >&2; }

# server.env without its secrets (values of keys naming a password, key, token or secret).
bms_redacted_env() {
    LC_ALL=C sed -E 's/^([A-Za-z0-9_]*(PASSWORD|KEY|TOKEN|SECRET)[A-Za-z0-9_]*)=.*/\1=<redactado>/' "${1:-$BMS_BACKUP_SERVER_ENV}"
}

bms_backup_offsite() {
    local dir=$1 remote target key include_secrets name
    remote=$(bms_backup_setting BACKUP_RCLONE_REMOTE)
    target=$(bms_backup_setting BACKUP_RSYNC_TARGET)
    include_secrets=$(bms_backup_setting BACKUP_OFFSITE_INCLUDE_SECRETS)
    name=${dir##*/}
    local exclude=(--exclude 'etc-bms-secret.tar.gz' --exclude 'roles.sql')
    [[ $include_secrets == true ]] && exclude=()
    if [[ -n $remote ]]; then
        if ! command -v rclone >/dev/null 2>&1; then
            bms_backup_log 'AVISO: BACKUP_RCLONE_REMOTE configurado pero rclone no esta instalado.'
        elif rclone copy "${exclude[@]}" -- "$dir" "$remote/$name" >> "$BMS_BACKUP_LOG" 2>&1; then
            bms_backup_log "Copia fuera del servidor (rclone) completa: $remote/$name"
        else
            bms_backup_log 'ERROR: fallo la copia fuera del servidor con rclone.'
            return 1
        fi
    fi
    if [[ -n $target ]]; then
        key=$(bms_backup_setting BACKUP_RSYNC_SSH_KEY)
        local ssh=(ssh -o BatchMode=yes -o StrictHostKeyChecking=yes)
        [[ -n $key ]] && ssh+=(-i "$key")
        if ! command -v rsync >/dev/null 2>&1; then
            bms_backup_log 'AVISO: BACKUP_RSYNC_TARGET configurado pero rsync no esta instalado.'
        elif rsync -a "${exclude[@]}" -e "${ssh[*]}" -- "$dir" "$target/" >> "$BMS_BACKUP_LOG" 2>&1; then
            bms_backup_log "Copia fuera del servidor (rsync) completa: $target/$name"
        else
            bms_backup_log 'ERROR: fallo la copia fuera del servidor con rsync.'
            return 1
        fi
    fi
}

bms_backup_main() {
    local control_db db_url keep stamp dir status=0 item
    [[ $EUID -eq 0 ]] || { printf 'Debe ejecutarse como root.\n' >&2; return 1; }
    [[ -r $BMS_BACKUP_SERVER_ENV ]] || { printf 'No se encontro %s.\n' "$BMS_BACKUP_SERVER_ENV" >&2; return 1; }
    db_url=$(awk -F= '$1 == "DB_URL" { sub(/^[^=]*=/, ""); print; exit }' "$BMS_BACKUP_SERVER_ENV")
    control_db=${db_url##*/}
    control_db=${control_db%%\?*}
    bms_ascii_match "$control_db" '^[a-z][a-z0-9_]{0,62}$' || { printf 'DB_URL invalida en server.env.\n' >&2; return 1; }
    # L3: not while an install or update runs (its own dump comes first); wait for it, at most an hour.
    exec 7>>/run/bms-maintenance.lock
    if ! flock -w 3600 7; then
        bms_backup_log 'ERROR: una instalacion o actualizacion sigue en curso despues de una hora; respaldo omitido.'
        return 1
    fi
    keep=$(bms_backup_setting BACKUP_KEEP)
    bms_ascii_match "$keep" '^[1-9][0-9]{0,2}$' || keep=7
    umask 077
    install -d -m 700 -o root -g root "$BMS_BACKUP_ROOT"
    touch "$BMS_BACKUP_LOG" && chmod 640 "$BMS_BACKUP_LOG"
    stamp=$(date -u +%Y%m%dT%H%M%SZ)
    dir=$BMS_BACKUP_ROOT/$stamp
    local needed
    needed=$(bms_backup_size "$control_db") || needed=x
    if [[ -d /opt/bms/company-files ]]; then
        needed=$(( ${needed//x/0} + $(du -sb /opt/bms/company-files 2>/dev/null | awk '{print $1}' || echo 0) ))
    fi
    if ! bms_backup_space_ok "$BMS_BACKUP_ROOT" "$needed"; then
        bms_backup_log "ERROR: no hay espacio libre suficiente en $BMS_BACKUP_ROOT para el respaldo (se necesitan unos $needed bytes y 2 GiB de margen); no se hizo."
        return 1
    fi
    bms_backup_log "Respaldo $dir"
    if ! bms_dump_databases "$dir/db" "$control_db"; then
        bms_backup_log 'ERROR: fallo el volcado de las bases; se borra este respaldo incompleto.'
        rm -rf --one-file-system -- "$dir"
        return 1
    fi
    # Company files, configuration (full: root only) and its redacted copy, and the Nginx sites.
    if [[ -d /opt/bms/company-files ]]; then
        tar -C /opt/bms -czf "$dir/company-files.tar.gz" company-files || { bms_backup_log 'ERROR: fallo el respaldo de company-files.'; status=1; }
    fi
    tar -C / -czf "$dir/etc-bms-secret.tar.gz" etc/bms || { bms_backup_log 'ERROR: fallo el respaldo de /etc/bms.'; status=1; }
    bms_redacted_env > "$dir/server.env.redactado" || status=1
    tar -C / -czf "$dir/etc-bms.tar.gz" --exclude 'etc/bms/server.env*' --exclude 'etc/bms/backup.conf' etc/bms 2>/dev/null || true
    local nginx_files=()
    for item in etc/nginx/sites-available/bms etc/nginx/sites-available/bms-default etc/nginx/conf.d/bms-limits.conf etc/nginx/bms.d; do
        [[ -e /$item ]] && nginx_files+=("$item")
    done
    if (( ${#nginx_files[@]} > 0 )); then
        tar -C / -czf "$dir/nginx.tar.gz" "${nginx_files[@]}" || status=1
    fi
    (cd "$dir" && sha256sum -- *.tar.gz server.env.redactado > SHA256SUMS) || status=1
    chmod -R go-rwx "$dir"
    # Retention: the newest [keep] copies stay.
    while IFS= read -r item; do
        rm -rf --one-file-system -- "$item"
    done < <(find "$BMS_BACKUP_ROOT" -mindepth 1 -maxdepth 1 -type d -name '20*' -printf '%f\n' | LC_ALL=C sort -r | tail -n "+$((keep + 1))" | sed "s#^#$BMS_BACKUP_ROOT/#")
    bms_backup_offsite "$dir" || status=1
    bms_backup_log "Respaldo terminado (estado $status): $dir"
    return "$status"
}

bms_backup_install() {
    local self=$1 units_lib
    install -o root -g root -m 755 "$self" /usr/local/sbin/bms-backup
    install -d -m 700 -o root -g root /var/lib/bms "$BMS_BACKUP_ROOT"
    cat > /etc/systemd/system/bms-backup.service <<'UNIT'
[Unit]
Description=Back up the BMS databases, company files and configuration
After=postgresql.service
Wants=postgresql.service
[Service]
Type=oneshot
ExecStart=/usr/local/sbin/bms-backup
Nice=10
IOSchedulingClass=idle
UMask=0077
ProtectHome=read-only
UNIT
    cat > /etc/systemd/system/bms-backup.timer <<'UNIT'
[Unit]
Description=Daily backup of BMS
[Timer]
OnCalendar=*-*-* 02:37:00
RandomizedDelaySec=1800
Persistent=true
[Install]
WantedBy=timers.target
UNIT
    chmod 644 /etc/systemd/system/bms-backup.{service,timer}
    units_lib=$(dirname -- "$self")/units.sh
    if [[ -f $units_lib ]]; then
        # shellcheck source=units.sh
        source "$units_lib"
        bms_helper_hardening | bms_write_dropin bms-backup.service >/dev/null
    fi
    systemctl daemon-reload
    systemctl enable --now bms-backup.timer
    printf 'Respaldo diario instalado (bms-backup.timer, %s).\n' "$BMS_BACKUP_ROOT"
}

if [[ ${BASH_SOURCE[0]} == "$0" ]]; then
    set -Eeuo pipefail
    umask 077
    # Locale (scripts/ubuntu/tests/locale_test.sh): with the server's LANG (en_US.UTF-8 and others) bracket ranges such as
    # [!-~] or [a-z], sort orders and case conversions do not mean plain ASCII. Everything below runs as in C for ASCII while
    # text stays UTF-8 (plain C when C.UTF-8 is missing); the exact ASCII checks also set LC_ALL=C themselves (bms_ascii_match).
    if [[ $(LC_ALL=C.UTF-8 locale charmap 2>/dev/null) == UTF-8 ]]; then export LC_ALL=C.UTF-8; else export LC_ALL=C; fi
    unset LANGUAGE
    if [[ ${1:-} == --install ]]; then
        [[ $EUID -eq 0 ]] || { printf 'Debe ejecutarse como root.\n' >&2; exit 1; }
        bms_backup_install "$(realpath -e -- "${BASH_SOURCE[0]}")"
        exit 0
    fi
    bms_backup_main
fi
