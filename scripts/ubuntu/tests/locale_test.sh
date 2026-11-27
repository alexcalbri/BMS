#!/usr/bin/env bash
# shellcheck disable=SC2016 # code run later by bash -c
# The scripts' checks under several locales (the live update aborted because, with LANG=en_US.UTF-8, [!-~] did not
# match a generated 64-hex password): update.sh's validators and --self-test, install.sh's, the libraries' (sourced
# into a shell that keeps the given locale: they must not rely on the caller's), the migration order and the Nginx
# name parsing, under C, C.UTF-8, POSIX, no locale at all, and every UTF-8 locale with its own collation this machine
# has (en_US.UTF-8 first; generate it with: localedef -i en_US -f UTF-8 en_US.UTF-8). The lint rule is checked to
# catch an unprotected range.
set -uo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"

WORK=$(mktemp -d)
trap 'rm -rf -- "$WORK"' EXIT

available() { locale -a 2>/dev/null | tr '[:upper:]' '[:lower:]' | sed 's/utf-8/utf8/' | grep -qx "$(tr '[:upper:]' '[:lower:]' <<< "$1" | sed 's/utf-8/utf8/')"; }
LOCALES=(C C.UTF-8 POSIX '')
for candidate in en_US.UTF-8 es_ES.UTF-8 tr_TR.UTF-8 de_DE.UTF-8; do
    if available "$candidate"; then LOCALES+=("$candidate"); fi
done
if ! available en_US.UTF-8; then
    printf '  (aviso: en_US.UTF-8 no esta generado aqui; el fallo del servidor no se reproduce: localedef -i en_US -f UTF-8 en_US.UTF-8)\n'
fi

# Runs bash code with exactly [locale] (empty: no LANG or LC_* at all, as a systemd unit without locale).
in_locale() {
    local name=$1
    shift
    if [[ -z $name ]]; then
        env -i PATH="$PATH" HOME="$WORK" bash "$@"
    else
        env -i PATH="$PATH" HOME="$WORK" LANG="$name" LC_ALL="$name" bash "$@"
    fi
}

# The failure itself: the old check, without protection, under en_US.UTF-8.
if available en_US.UTF-8; then
    equals 'reproducido: sin proteccion, en_US.UTF-8 rechaza una contrasena de 64 hex' no \
        "$(in_locale en_US.UTF-8 -c '[[ 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef =~ ^[!-~]{16,}$ ]] && echo si || echo no')"
fi

sed -n '/^# BEGIN validators/,/^# END validators/p' "$SCRIPTS/update.sh" > "$WORK/update-validators.sh"
sed -n '/^# BEGIN validators/,/^# END validators/p' "$SCRIPTS/install.sh" > "$WORK/install-validators.sh"
check 'update.sh tiene su bloque de validadores' test -s "$WORK/update-validators.sh"
check 'install.sh tiene su bloque de validadores' test -s "$WORK/install-validators.sh"

# A site as install.sh writes it, with names a locale could let through.
cat > "$WORK/site" <<'SITE'
server {
    listen 443 ssl;
    server_name bms.example.com ñandú.example.com WWW.example.com www.example.com;
    ssl_certificate /etc/letsencrypt/live/bms.example.com/fullchain.pem;
    location / {
        proxy_pass http://127.0.0.1:8080;
    }
}
SITE
# A migrations tree whose names sort differently by locale.
mkdir -p "$WORK/migrations"
for file in V001__z.sql V002__b.sql V010__A.sql V010__a.sql V010__é.sql V1__corto.sql notes.sql; do : > "$WORK/migrations/$file"; done

cat > "$WORK/checks.sh" <<'CHECKS'
# Sourced into a shell that keeps the locale under test; prints "name=answer" lines.
source "$WORK/update-validators.sh"
source "$WORK/install-validators.sh"
source "$SCRIPTS/db-roles.sh"
source "$SCRIPTS/nginx-site.sh"
source "$SCRIPTS/units.sh"
source "$SCRIPTS/mirror.sh"
answer() { local name=$1; shift; if "$@"; then echo "$name=si"; else echo "$name=no"; fi; }
password=$(openssl rand -hex 32)
answer password_generated valid_provisioner_password "$password"
answer password_printable valid_provisioner_password 'Abc!def#ghi%jkl&mno~'
answer password_accent valid_provisioner_password 'contraseña-larga-123456'
answer password_colon valid_provisioner_password 'con:dos-puntos-123456'
answer password_space valid_provisioner_password 'con espacio 1234567890'
answer role_ok bms_valid_role bms_provisioner
answer role_accent bms_valid_role josé
answer role_upper bms_valid_role Bms
answer role_update_accent valid_role_name josé
answer identifier_accent valid_identifier ñandu
answer sha_ok valid_sha 0123456789abcdef0123456789abcdef01234567
answer sha_upper valid_sha 0123456789ABCDEF0123456789abcdef01234567
answer ref_ok mirror_valid_ref master
answer ref_accent mirror_valid_ref ramá
answer git_ref_accent valid_git_ref ramá
answer domain_ok valid_domain api.empresa.com
answer domain_accent valid_domain ápi.empresa.com
answer domain_upper valid_domain API.empresa.com
answer domain_ip valid_domain 10.0.0.1
answer build_user_ok valid_build_user bms-build
answer memory_ok bms_ascii_match 3G '^([1-9][0-9]?%|[1-9][0-9]*[KMGT]?)$'
echo "host_port=$(db_host_port jdbc:postgresql://127.0.0.1:5432/bms_control)"
echo "host_port_accent=$(db_host_port jdbc:postgresql://séver:5432/bms_control || echo rechazado)"
echo "names=$(bms_nginx_site_names "$WORK/site")"
echo "params=$(bms_nginx_site_params "$WORK/site")"
echo "migrations=$(sorted_migrations "$WORK/migrations" 2 | sed 's#.*/##' | paste -sd ' ')"
echo "control=$(sorted_migrations "$SCRIPTS/../../database/tenant/migrations" 6 | sed 's#.*/##' | paste -sd ' ')"
CHECKS

expected_control=$(cd "$REPO/database/tenant/migrations" && printf '%s\n' V*.sql | LC_ALL=C sort | awk '{ if (substr($0, 2, 3) + 0 >= 6) print }' | paste -sd ' ')
for name in "${LOCALES[@]}"; do
    label=${name:-sin locale}
    output=$(in_locale "$name" -c 'WORK=$1 SCRIPTS=$2; source "$WORK/checks.sh"' _ "$WORK" "$SCRIPTS" 2>&1)
    value() { sed -n "s/^$1=//p" <<< "$output"; }
    for key in password_generated password_printable role_ok sha_ok ref_ok domain_ok build_user_ok memory_ok; do
        equals "$label: $key aceptado" si "$(value "$key")"
    done
    for key in password_accent password_colon password_space role_accent role_upper role_update_accent identifier_accent \
        sha_upper ref_accent git_ref_accent domain_accent domain_upper domain_ip; do
        equals "$label: $key rechazado" no "$(value "$key")"
    done
    equals "$label: servidor y puerto de DB_URL" '127.0.0.1 5432' "$(value host_port)"
    equals "$label: DB_URL con servidor no ASCII" rechazado "$(value host_port_accent)"
    equals "$label: nombres del sitio de Nginx (solo ASCII en minusculas)" 'bms.example.com www.example.com' "$(value names)"
    equals "$label: parametros del sitio de Nginx" 'https bms.example.com bms.example.com' "$(value params)"
    equals "$label: orden de migraciones (orden de bytes)" 'V002__b.sql V010__A.sql V010__a.sql V010__é.sql' "$(value migrations)"
    equals "$label: migraciones tenant desde V006 del arbol" "$expected_control" "$(value control)"
    # The entry point itself: update.sh --self-test fixes its locale and passes whatever the caller's.
    if in_locale "$name" "$SCRIPTS/update.sh" --self-test > "$WORK/self-test" 2>&1; then
        pass "$label: update.sh --self-test"
    else
        fail "$label: update.sh --self-test ($(grep -v '^PASS' "$WORK/self-test" | head -n 3 | paste -sd ';' -))"
    fi
    # And it really runs with a UTF-8 (or C) locale of its own, never the caller's.
    equals "$label: update.sh fija LC_ALL" C.UTF-8 "$(in_locale "$name" -c 'source <(sed -n "/^# Locale (scripts/,/^unset LANGUAGE/p" "$1"); echo "$LC_ALL"' _ "$SCRIPTS/update.sh")"
done

# The validators' self-test also holds when C.UTF-8 cannot be set (the fallback is plain C).
equals 'sin C.UTF-8 se usa C' C "$(env -i PATH="$PATH" bash -c 'locale() { echo ANSI_X3.4-1968; }; export -f locale; source <(sed -n "/^# Locale (scripts/,/^unset LANGUAGE/p" "$1"); echo "$LC_ALL"' _ "$SCRIPTS/update.sh")"

# The lint rule catches an unprotected range (a copy of the tree with one old check put back).
mkdir -p "$WORK/lint"
cp "$SCRIPTS"/*.sh "$WORK/lint/"
printf '%s\n' 'bad_check() { [[ $1 =~ ^[!-~]{16,}$ ]]; }' >> "$WORK/lint/db-roles.sh"
printf '%s\n' 'listing() { find . -name "V[0-9]*" | sort; }' >> "$WORK/lint/mirror.sh"
lint=$(python3 "$SCRIPTS/tests/locale_lint.py" "$WORK/lint" 2>&1)
check 'la regla de locale detecta un =~ sin proteccion' grep -q 'db-roles.sh:.*fuera de bms_ascii_match' <<< "$lint"
check 'la regla de locale detecta sort sin LC_ALL=C en una biblioteca' grep -q 'mirror.sh:.*sort sin LC_ALL=C' <<< "$lint"
finish
