#!/usr/bin/env bash
# Optional host hardening (SELF_HOSTING.md section 16), never run unless asked: sudo bash harden-host.sh
# (or install.sh / update.sh --harden-host). Installs and enables:
#   ufw                  only SSH (every port sshd listens on, and the port of the current SSH session), 80/tcp,
#                        443/tcp and 443/udp (HTTP/3) inbound; the SSH rules are added BEFORE ufw is enabled.
#   fail2ban             with Ubuntu's default sshd jail.
#   unattended-upgrades  security updates applied automatically.
# Asks before each step; --yes answers yes (for a terminal-less run, which still never enables ufw without the SSH
# port allowed).
set -Eeuo pipefail
umask 022
# Locale (scripts/ubuntu/tests/locale_test.sh): with the server's LANG (en_US.UTF-8 and others) bracket ranges such as
# [!-~] or [a-z], sort orders and case conversions do not mean plain ASCII. Everything below runs as in C for ASCII while
# text stays UTF-8 (plain C when C.UTF-8 is missing); the exact ASCII checks also set LC_ALL=C themselves (bms_ascii_match).
if [[ $(LC_ALL=C.UTF-8 locale charmap 2>/dev/null) == UTF-8 ]]; then export LC_ALL=C.UTF-8; else export LC_ALL=C; fi
unset LANGUAGE

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
[[ $EUID -eq 0 ]] || die 'Ejecuta con sudo bash harden-host.sh.'

ASSUME_YES=false
[[ ${1:-} == --yes ]] && ASSUME_YES=true

confirm() {
    local answer
    [[ $ASSUME_YES == true ]] && return 0
    [[ -r /dev/tty ]] || return 1
    read -r -p "$1 Escribe si: " answer </dev/tty
    [[ $answer == si ]]
}

install_packages() {
    DEBIAN_FRONTEND=noninteractive apt-get install -y "$@"
}

# Ports sshd listens on (sshd -T), plus the local port of the SSH session running this script.
ssh_ports() {
    {
        if command -v sshd >/dev/null 2>&1; then
            sshd -T 2>/dev/null | awk '$1 == "port" { print $2 }'
        fi
        if [[ -n ${SSH_CONNECTION:-} ]]; then
            awk '{ print $4 }' <<< "$SSH_CONNECTION"
        fi
    } | grep -E '^[0-9]{1,5}$' | sort -un
}

harden_firewall() {
    local ports port
    ports=$(ssh_ports)
    if [[ -z $ports ]]; then
        printf 'AVISO: no se pudo saber el puerto de SSH; ufw no se activa (podrias quedar fuera del servidor).\n' >&2
        return 0
    fi
    confirm "Se activara ufw permitiendo SSH ($(tr '\n' ' ' <<< "$ports")), 80 y 443. Continuar?" || return 0
    command -v ufw >/dev/null 2>&1 || install_packages ufw
    while IFS= read -r port; do
        ufw allow "$port/tcp" comment 'SSH'
    done <<< "$ports"
    ufw allow 80/tcp comment 'BMS HTTP'
    ufw allow 443/tcp comment 'BMS HTTPS'
    ufw allow 443/udp comment 'BMS HTTP/3'
    ufw default deny incoming
    ufw default allow outgoing
    # Only now, with SSH allowed.
    ufw --force enable
    ufw status verbose
}

harden_fail2ban() {
    confirm 'Se instalara fail2ban (bloqueo de intentos de acceso por SSH). Continuar?' || return 0
    install_packages fail2ban
    systemctl enable --now fail2ban
}

harden_upgrades() {
    confirm 'Se activaran las actualizaciones de seguridad automaticas (unattended-upgrades). Continuar?' || return 0
    install_packages unattended-upgrades
    cat > /etc/apt/apt.conf.d/20auto-upgrades <<'APT'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
APT
    systemctl enable --now unattended-upgrades
}

apt-get update
harden_firewall
harden_fail2ban
harden_upgrades
printf 'Endurecimiento del servidor terminado.\n'
