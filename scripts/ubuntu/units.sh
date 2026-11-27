#!/usr/bin/env bash
# systemd hardening of BMS (SELF_HOSTING.md section 16), sourced by install.sh and update.sh. The units keep their
# own files; these are drop-ins (<unit>.d/hardening.conf) so update.sh can write, compare and, on a failed update,
# take them back.

# Sourced: the checks here never rely on the caller's locale.
# [[ =~ ]] with LC_ALL=C for this one test, whatever the caller's locale: [a-z], [0-9a-f] or [!-~] mean ASCII.
bms_ascii_match() { local LC_ALL=C; [[ $1 =~ $2 ]]; }

# bms.service: the Ktor server (user bms, Java). [syscall_filter] true adds SystemCallFilter=@system-service, with
# EPERM instead of killing the JVM on a refused call; it is opt-in (BMS_SYSCALL_FILTER=true in server.env) until
# it has been tried on the server. [memory_max] (for example 80% or 3G) adds MemoryMax; empty leaves it out, which is
# the default below 8 GiB of RAM, where a cap would kill the server (with its module compiler, -Xmx1g) before the
# kernel would (bms_default_memory_max).
bms_service_hardening() {
    local syscall_filter=${1:-false} memory_max=${2:-}
    cat <<'UNIT'
# Managed by BMS (scripts/ubuntu/units.sh); update.sh rewrites this file.
[Service]
ProtectProc=invisible
CapabilityBoundingSet=
AmbientCapabilities=
PrivateDevices=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectKernelLogs=true
ProtectControlGroups=true
ProtectClock=true
ProtectHostname=true
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6 AF_NETLINK
RestrictNamespaces=true
RestrictRealtime=true
RestrictSUIDSGID=true
LockPersonality=true
RemoveIPC=true
KeyringMode=private
SystemCallArchitectures=native
TasksMax=4096
UNIT
    if bms_ascii_match "$memory_max" '^([1-9][0-9]?%|[1-9][0-9]*[KMGT]?)$'; then
        printf 'MemoryMax=%s\n' "$memory_max"
    fi
    if [[ $syscall_filter == true ]]; then
        cat <<'UNIT'
SystemCallFilter=@system-service
SystemCallErrorNumber=EPERM
UNIT
    fi
}

# MemoryMax for bms.service: BMS_MEMORY_MAX from server.env ("none" for no cap); otherwise 80% with at least 8 GiB of
# RAM and no cap below.
bms_default_memory_max() {
    local configured=${1:-} ram_kb
    if [[ $configured == none ]]; then
        return 0
    elif [[ -n $configured ]]; then
        printf '%s\n' "$configured"
        return 0
    fi
    ram_kb=$(awk '/^MemTotal:/ { print $2 }' /proc/meminfo)
    (( ${ram_kb:-0} >= 8 * 1024 * 1024 )) && printf '80%%\n'
    return 0
}

# Root helpers (domain publisher, certificate renewal, backups): they run nginx -t, systemctl reload, certbot,
# runuser and psql as root, so file system and capability limits would break them; only what they never need is
# taken away.
bms_helper_hardening() {
    cat <<'UNIT'
# Managed by BMS (scripts/ubuntu/units.sh).
[Service]
NoNewPrivileges=true
PrivateTmp=true
PrivateDevices=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectKernelLogs=true
ProtectControlGroups=true
ProtectClock=true
ProtectHostname=true
RestrictNamespaces=true
RestrictRealtime=true
RestrictSUIDSGID=true
LockPersonality=true
SystemCallArchitectures=native
UNIT
}

# Writes <content on stdin> as the drop-in hardening.conf of [unit] when it differs; prints "changed" if it did.
bms_write_dropin() {
    local unit=$1 dir file content
    dir=/etc/systemd/system/$unit.d
    file=$dir/hardening.conf
    content=$(cat)
    if [[ -f $file && $(cat "$file") == "$content" ]]; then
        return 0
    fi
    install -d -m 755 -o root -g root "$dir"
    printf '%s\n' "$content" > "$file.tmp"
    chmod 644 "$file.tmp"
    mv -f -- "$file.tmp" "$file"
    printf 'changed\n'
}
