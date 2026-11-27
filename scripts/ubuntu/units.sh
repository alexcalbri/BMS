#!/usr/bin/env bash
# systemd hardening of BMS (SELF_HOSTING.md section 16), sourced by install.sh and update.sh. The units keep their
# own files; these are drop-ins (<unit>.d/hardening.conf) so update.sh can write, compare and, on a failed update,
# take them back.

# bms.service: the Ktor server (user bms, Java). [syscall_filter] true adds SystemCallFilter=@system-service, with
# EPERM instead of killing the JVM on a refused call; it is opt-in (BMS_SYSCALL_FILTER=true in server.env) until
# it has been tried on the server. MemoryMax leaves room for the module compiler the server starts (-Xmx1g).
bms_service_hardening() {
    local syscall_filter=${1:-false}
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
MemoryMax=80%
TasksMax=4096
UNIT
    if [[ $syscall_filter == true ]]; then
        cat <<'UNIT'
SystemCallFilter=@system-service
SystemCallErrorNumber=EPERM
UNIT
    fi
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
