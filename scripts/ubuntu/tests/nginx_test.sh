#!/usr/bin/env bash
# shellcheck disable=SC2016 # bash -c and eval bodies are expanded later on purpose
# The Nginx configuration nginx-site.sh generates: accepted by nginx -t (HTTP and HTTPS), body limits only opened on
# the upload routes, headers, catch-all, reading the parameters of sites older installers wrote, and the ports
# another default_server already takes. Optional live check (BMS_WEB_DIST=<dir with index.html and webApp.js> and
# google-chrome): serves the real web app with the generated CSP and fails on any CSP violation.
set -uo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/lib.sh"
# shellcheck source=../nginx-site.sh
source "$SCRIPTS/nginx-site.sh"

WORK=$(mktemp -d)
NGINX_PID=''
cleanup() {
    [[ -z $NGINX_PID ]] || kill "$NGINX_PID" 2>/dev/null
    rm -rf -- "$WORK"
}
trap cleanup EXIT

https_site=$(bms_nginx_site https bms.example.com bms-bms.example.com /opt/bms/web /var/www/bms-acme 8080 no)
http3_site=$(bms_nginx_site https bms.example.com bms-bms.example.com /opt/bms/web /var/www/bms-acme 8080 yes)
check 'QUIC y Alt-Svc solo con HTTP/3' bash -c 'grep -q "listen 443 quic" <<< "$1" && grep -q Alt-Svc <<< "$1" && ! grep -qE "quic|Alt-Svc" <<< "$2"' _ "$http3_site" "$https_site"
http_site=$(bms_nginx_site http bms.example.com - /opt/bms/web /var/www/bms-acme 8080)

# Body limits: 2m by default; only the upload routes open it.
equals 'tope por defecto en cada server' 2 "$(grep -c 'client_max_body_size 2m;' <<< "$https_site")"
equals 'sin tope solo en los adjuntos' 1 "$(grep -c 'client_max_body_size 0;' <<< "$https_site")"
check 'los adjuntos van sin copia en disco' grep -q 'proxy_request_buffering off;' <<< "$https_site"
check 'la ruta de adjuntos es la del codigo' grep -qF 'location ~ "^/modules/[a-z][a-z0-9_]{0,62}/ui/files$" {' <<< "$https_site"
check 'X-Forwarded-Host en cada proxy' bash -c '[[ $(grep -c "proxy_pass " <<< "$1") == $(grep -c "X-Forwarded-Host" <<< "$1") ]]' _ "$https_site"
check 'HSTS por HTTPS' grep -q Strict-Transport-Security <<< "$https_site"
check 'sin HSTS ni Alt-Svc por HTTP' bash -c '! grep -qE "Strict-Transport-Security|Alt-Svc" <<< "$1"' _ "$http_site"
check 'CSP con wasm' grep -q "script-src 'self' 'wasm-unsafe-eval'" <<< "$https_site"
check 'limites por direccion' grep -q 'limit_req zone=bms_auth' <<< "$https_site"

# nginx -t on the generated files (paths moved into the work directory).
if command -v nginx >/dev/null 2>&1; then
    mkdir -p "$WORK"/{conf.d,sites,bms.d,le/bms-bms.example.com,web,acme,tmp}
    openssl req -x509 -newkey rsa:2048 -nodes -subj /CN=bms.example.com -days 1 \
        -keyout "$WORK/le/bms-bms.example.com/privkey.pem" -out "$WORK/le/bms-bms.example.com/fullchain.pem" >/dev/null 2>&1
    BMS_NGINX_HTTP2_DIRECTIVE=no
    bms_nginx_has_http2_directive && BMS_NGINX_HTTP2_DIRECTIVE=yes
    https_site=$(bms_nginx_site https bms.example.com bms-bms.example.com /opt/bms/web /var/www/bms-acme 8080 no)
    # This container has no IPv6: the [::] listeners are left out of the nginx -t copy.
    relocate() { sed -e '/listen \[::\]/d' -e "s#/etc/letsencrypt/live#$WORK/le#g" -e "s#/etc/nginx/bms.d#$WORK/bms.d#g" -e "s#/opt/bms/web#$WORK/web#g" -e "s#/var/www/bms-acme#$WORK/acme#g"; }
    bms_nginx_limits > "$WORK/conf.d/bms-limits.conf"
    printf '%s\n' "$https_site" | relocate > "$WORK/sites/bms"
    bms_nginx_catchall '80 443' | relocate > "$WORK/sites/bms-default"
    cat > "$WORK/nginx.conf" <<NGINX
pid $WORK/nginx.pid;
error_log $WORK/error.log;
events {}
http {
    include /etc/nginx/mime.types;
    access_log off;
    client_body_temp_path $WORK/tmp/body;
    proxy_temp_path $WORK/tmp/proxy;
    fastcgi_temp_path $WORK/tmp/fastcgi;
    uwsgi_temp_path $WORK/tmp/uwsgi;
    scgi_temp_path $WORK/tmp/scgi;
    include $WORK/conf.d/*.conf;
    include $WORK/sites/*;
}
NGINX
    check 'nginx -t (HTTPS, limites, servidor por defecto)' nginx -t -q -c "$WORK/nginx.conf" -e "$WORK/error.log"
    printf '%s\n' "$http_site" | relocate > "$WORK/sites/bms"
    bms_nginx_catchall '80' | relocate > "$WORK/sites/bms-default"
    check 'nginx -t (HTTP)' nginx -t -q -c "$WORK/nginx.conf" -e "$WORK/error.log"
    [[ $FAILURES == 0 ]] || nginx -t -c "$WORK/nginx.conf" -e "$WORK/error.log"
else
    printf '  (omitido nginx -t: nginx no esta instalado)\n'
fi

# Parameters of sites written by older installers (install.sh before this change).
cat > "$WORK/old-https" <<'NGINX'
server {
    listen 80;
    listen [::]:80;
    server_name crm.example.org;
    location / { return 301 https://crm.example.org$request_uri; }
}
server {
    listen 443 ssl;
    listen [::]:443 ssl;
    server_name crm.example.org;
    ssl_certificate /etc/letsencrypt/live/bms-crm.example.org/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/bms-crm.example.org/privkey.pem;
    location /modules {
        client_max_body_size 0;
        proxy_pass http://127.0.0.1:8080;
    }
}
NGINX
equals 'sitio HTTPS anterior' 'https crm.example.org bms-crm.example.org' "$(bms_nginx_site_params "$WORK/old-https")"
printf 'server {\n    listen 80;\n    server_name demo.example.org;\n    location /auth/ { proxy_pass http://127.0.0.1:8080; }\n}\n' > "$WORK/old-http"
equals 'sitio HTTP anterior' 'http demo.example.org -' "$(bms_nginx_site_params "$WORK/old-http")"
printf 'server {\n    listen 80;\n    server_name otro.example.org;\n    root /srv/www;\n}\n' > "$WORK/foreign"
check 'un sitio ajeno no se toma como de BMS' eval '! bms_nginx_site_params "$WORK/foreign"'
equals 'parametros del sitio generado' 'https bms.example.com bms-bms.example.com' "$(printf '%s\n' "$https_site" > "$WORK/gen"; bms_nginx_site_params "$WORK/gen")"

# Ports another default_server takes (nginx -T output).
taken=$(printf '%s\n' '# configuration file /etc/nginx/sites-enabled/default:' 'server {' '    listen 80 default_server;' '    listen [::]:80 default_server;' '}' \
    '# configuration file /etc/nginx/sites-available/bms-default:' '    listen 443 ssl default_server;' | bms_nginx_taken_default_ports)
equals 'el default de Ubuntu ocupa el 80; el nuestro no cuenta' '80 ' "$taken"

# Live check of the CSP with the real web bundle: the app starts (Compose replaces the loading spinner with its
# container, loads its WebAssembly and asks the backend for /version) with no CSP violation; a control run with
# 'wasm-unsafe-eval' removed must report one, so the check can see violations at all.
live_run() {
    local port=$1 dir=$2
    rm -rf -- "$WORK/chrome$port"
    timeout 90 google-chrome --headless=new --no-sandbox --disable-gpu --enable-logging=stderr --v=0 \
        --virtual-time-budget=30000 --user-data-dir="$WORK/chrome$port" --dump-dom "http://bms.example.com:$port/" \
        --host-resolver-rules='MAP bms.example.com 127.0.0.1' > "$dir.dom" 2> "$dir.log"
}
if [[ -n ${BMS_WEB_DIST:-} && -f ${BMS_WEB_DIST}/index.html ]] && command -v nginx >/dev/null 2>&1 && command -v google-chrome >/dev/null 2>&1; then
    rm -f "$WORK/sites/bms-default"
    live_site() {
        printf '%s\n' "$http_site" | sed -e "s#/opt/bms/web#$BMS_WEB_DIST#g" -e "s#/etc/nginx/bms.d#$WORK/bms.d#g" \
            -e "s/listen 80;/listen 127.0.0.1:$1;/" -e '/listen \[::\]:80;/d'
    }
    live_site 18431 > "$WORK/sites/bms"
    live_site 18432 | sed "s/ 'wasm-unsafe-eval'//" > "$WORK/sites/control"
    { (( EUID == 0 )) && printf 'user root;\n'; sed 's#access_log off;#access_log '"$WORK"'/access.log; types { text/javascript mjs; }#' "$WORK/nginx.conf"; } > "$WORK/live.conf"
    nginx -c "$WORK/live.conf" -e "$WORK/error.log" -g 'daemon off;' & NGINX_PID=$!
    sleep 1
    check 'la cabecera CSP se envia' bash -c "curl -sI -H 'Host: bms.example.com' http://127.0.0.1:18431/ | grep -qi \"content-security-policy: default-src 'self'\""
    live_run 18431 "$WORK/app"
    check 'la app arranco (Compose reemplazo la pantalla de carga)' bash -c '! grep -q "<svg" "$1" && grep -q "position: relative" "$1"' _ "$WORK/app.dom"
    check 'la app cargo su WebAssembly y pidio /version' bash -c 'grep -q "\.wasm HTTP/1.1\" 200" "$1" && grep -q "GET /version " "$1"' _ "$WORK/access.log"
    check 'sin violaciones de CSP en la consola' bash -c '! grep -qi "Content Security Policy\|Refused to" "$1"' _ "$WORK/app.log"
    grep -i "Content Security Policy\|Refused to" "$WORK/app.log" | head -5 >&2
    live_run 18432 "$WORK/control"
    check 'control: sin wasm-unsafe-eval la violacion se detecta' grep -qi "Content Security Policy\|Refused to\|WebAssembly" "$WORK/control.log"
else
    printf '  (omitida la prueba en Chrome: define BMS_WEB_DIST con la webapp compilada)\n'
fi
finish
