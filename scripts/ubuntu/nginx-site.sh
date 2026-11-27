#!/usr/bin/env bash
# Nginx configuration of BMS, generated in one place for install.sh and update.sh (sourced, never run alone).
# SELF_HOSTING.md section 13 explains every part. The site is regenerated on every update: local additions go
# in /etc/nginx/bms.d/*.conf (included inside the HTTPS, or HTTP, server block), never in the site file itself.
#
# Request bodies: 2 MB everywhere (Nginx refuses more before the backend sees it; the Core's own default,
# MAX_REQUEST_BODY_BYTES, is 4 MB behind it), except the exact routes found in the code that carry more. Each one here
# is at least the Core's limit for it (RequestBodyLimits.kt: UploadRoutes and LargerBodyPaths):
#   POST /modules/<module>/ui/files        module attachments (CRM, Compras, Tareas, Propietarios, Mantenimiento...):
#                                          no Nginx limit, streamed (proxy_request_buffering off); each module and
#                                          the Core quota decide the limit.
#   POST /modules/crm/ui/import            CRM import from pasted CSV text, JSON (25 MB, LargerBodyPaths).
#   POST /companies/me/backups/import      company profile backup ZIP, multipart (512 MB, BackupImportLimits), streamed.
#   POST /server/extensions/upload         extension package ZIP, multipart (50 MB, ExtensionPackageService.MaxZipBytes).
# JSON routes that stay under 2 MB on purpose: module screen actions, the MCP endpoint of Agentes (1 MB), OAuth (64 KB).

# shellcheck disable=SC2034 # used by the scripts that source this file
readonly BMS_NGINX_LIMITS_FILE=/etc/nginx/conf.d/bms-limits.conf
readonly BMS_NGINX_CATCHALL_SITE=/etc/nginx/sites-available/bms-default
readonly BMS_NGINX_LOCAL_DIR=/etc/nginx/bms.d
readonly BMS_NGINX_DEFAULT_BODY=2m

# Request and connection limits per client address (http context: conf.d is included inside http {}).
# Generous on purpose: an office behind one address must not notice them; the backend has its own sign-in limits.
bms_nginx_limits() {
    cat <<'NGINX'
# Managed by BMS (scripts/ubuntu/nginx-site.sh); update.sh rewrites this file.
limit_req_zone $binary_remote_addr zone=bms_req:10m rate=50r/s;
limit_req_zone $binary_remote_addr zone=bms_auth:10m rate=10r/s;
limit_conn_zone $binary_remote_addr zone=bms_conn:10m;
NGINX
}

# Content-Security-Policy of the web app (Compose for Web: Kotlin/JS plus the Skia WebAssembly module, which needs
# 'wasm-unsafe-eval'). Images and requests may go to other HTTPS hosts (company logos, a server URL the user set);
# module web screens run in a same-origin iframe. Checked with the production bundle in Chrome (SELF_HOSTING.md 13).
bms_nginx_csp() {
    local scheme=$1 connect="'self' https: wss:" images="'self' data: blob: https:"
    if [[ $scheme == http ]]; then
        connect+=" http: ws:"
        images+=" http:"
    fi
    printf "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src %s; font-src 'self' data:; connect-src %s; media-src 'self' blob: data:; worker-src 'self' blob:; frame-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'self'; form-action 'self'" "$images" "$connect"
}

# Security headers. add_header in a location replaces the server's, so every location that adds one repeats them.
# HSTS and Alt-Svc only over HTTPS (over plain HTTP they are wrong: the browser ignores or misuses them).
bms_nginx_headers() {
    local scheme=$1 indent=$2 frame=${3:-DENY} csp
    if [[ $frame == SAMEORIGIN ]]; then
        csp="frame-ancestors 'self'"
    else
        csp=$(bms_nginx_csp "$scheme")
    fi
    printf '%sadd_header X-Content-Type-Options "nosniff" always;\n' "$indent"
    printf '%sadd_header X-Frame-Options "%s" always;\n' "$indent" "$frame"
    printf '%sadd_header Referrer-Policy "strict-origin-when-cross-origin" always;\n' "$indent"
    printf '%sadd_header Content-Security-Policy "%s" always;\n' "$indent" "$csp"
    if [[ $scheme == https ]]; then
        printf '%sadd_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;\n' "$indent"
        [[ ${BMS_NGINX_HTTP3:-no} != yes ]] || printf '%sadd_header Alt-Svc '"'"'h3=":443"; ma=86400'"'"' always;\n' "$indent"
    fi
}

# Proxy to the backend; X-Forwarded-Host in every block (the backend builds public links from it).
# shellcheck disable=SC2016 # Nginx variables, written literally
bms_nginx_proxy() {
    local port=$1 indent=$2
    printf '%sproxy_pass http://127.0.0.1:%s;\n' "$indent" "$port"
    printf '%sproxy_http_version 1.1;\n' "$indent"
    printf '%sproxy_set_header Host $host;\n' "$indent"
    printf '%sproxy_set_header X-Real-IP $remote_addr;\n' "$indent"
    printf '%sproxy_set_header X-Forwarded-For $remote_addr;\n' "$indent"
    printf '%sproxy_set_header X-Forwarded-Proto $scheme;\n' "$indent"
    printf '%sproxy_set_header X-Forwarded-Host $host;\n' "$indent"
}

# A location proxied to the backend. Extra directives (one per line) go before the proxy lines.
bms_nginx_location() {
    local match=$1 port=$2 extra=${3:-}
    printf '    location %s {\n' "$match"
    if [[ -n $extra ]]; then
        printf '%s\n' "$extra" | sed 's/^/        /'
    fi
    bms_nginx_proxy "$port" '        '
    printf '    }\n'
}

# The body of the application server block (everything after listen/server_name/TLS).
bms_nginx_app_body() {
    local scheme=$1 web_root=$2 port=$3
    cat <<NGINX
    root $web_root;
    index index.html;
    server_tokens off;
    client_max_body_size $BMS_NGINX_DEFAULT_BODY;
    limit_req zone=bms_req burst=400 nodelay;
    limit_conn bms_conn 100;
    limit_req_status 429;
    limit_conn_status 429;
NGINX
    bms_nginx_headers "$scheme" '    '
    printf '    include %s/*.conf;\n' "$BMS_NGINX_LOCAL_DIR"
    bms_nginx_location /auth/ "$port" $'limit_req zone=bms_req burst=400 nodelay;\nlimit_req zone=bms_auth burst=40 nodelay;'
    bms_nginx_location /companies "$port"
    # Company profile backup (ZIP, at most 512 MB in BackupImportLimits): streamed, the backend reads and checks it.
    bms_nginx_location '= /companies/me/backups/import' "$port" "$(printf 'client_max_body_size 520m;\nproxy_request_buffering off;\nclient_body_timeout 300s;\nproxy_send_timeout 3600s;\nproxy_read_timeout 3600s;')"
    bms_nginx_location /server "$port"
    # Extension package ZIP (at most 50 MB in ExtensionPackageService), multipart.
    bms_nginx_location '= /server/extensions/upload' "$port" "$(printf 'client_max_body_size 60m;\nproxy_request_buffering off;')"
    printf '    # Version del Core y de los modulos (aviso de actualizaciones de la app).\n'
    bms_nginx_location '= /version' "$port"
    printf '    # Centro de notificaciones de la app.\n'
    bms_nginx_location /notifications "$port"
    printf '    # OAuth discovery for AI agents (claude.ai custom connectors); the module answers 404 when not installed.\n'
    bms_nginx_location /.well-known/oauth- "$port"
    # Module screens may be shown in a same-origin iframe of the web app (EmbeddedWebView).
    bms_nginx_location /modules "$port" "$(bms_nginx_headers "$scheme" '' SAMEORIGIN)"
    printf '    # Adjuntos de los modulos (POST /modules/<modulo>/ui/files, con sesion): sin tope en Nginx y sin copia en disco;\n'
    printf '    # el modulo y la cuota de la empresa deciden el limite.\n'
    bms_nginx_location '~ "^/modules/[a-z][a-z0-9_]{0,62}/ui/files$"' "$port" "$(
        printf 'client_max_body_size 0;\nproxy_request_buffering off;\nclient_body_timeout 300s;\nproxy_send_timeout 3600s;\nproxy_read_timeout 3600s;\n'
        bms_nginx_headers "$scheme" '' SAMEORIGIN)"
    printf '    # Importacion del CRM desde texto CSV pegado (25 MB, igual que el Core).\n'
    bms_nginx_location '= /modules/crm/ui/import' "$port" "$(printf 'client_max_body_size 25m;\n'; bms_nginx_headers "$scheme" '' SAMEORIGIN)"
    cat <<'NGINX'
    # No publicar los source maps: exponen la estructura del codigo fuente.
    location ~ \.map$ {
        return 404;
    }
    location / {
        try_files $uri $uri/ /index.html;
        # Se repiten aqui porque un add_header en el location anula la herencia del server.
NGINX
    bms_nginx_headers "$scheme" '        '
    cat <<'NGINX'
        add_header Cache-Control "no-cache" always;
    }
}
NGINX
}

# True when this Nginx has HTTP/3 (QUIC); Ubuntu 24.04's 1.24 does not.
bms_nginx_has_http3() { nginx -V 2>&1 | grep -q -- '--with-http_v3_module'; }

# True when this Nginx is 1.25.1 or later ("http2 on;"; older ones take "listen ... http2").
bms_nginx_has_http2_directive() {
    local version
    version=$(nginx -v 2>&1 | sed -nE 's#.*nginx/([0-9]+\.[0-9]+\.[0-9]+).*#\1#p')
    [[ -n $version ]] || return 0
    printf '%s\n1.25.1\n' "$version" | sort -V -C -r 2>/dev/null || [[ $version == 1.25.1 ]]
}

# The whole BMS site. https needs cert_name (/etc/letsencrypt/live/<cert_name>/); [http3] yes adds QUIC listeners
# and the Alt-Svc header (only when this Nginx has HTTP/3).
bms_nginx_site() {
    local scheme=$1 domain=$2 cert_name=$3 web_root=$4 acme_root=$5 port=$6 http3=${7:-no}
    BMS_NGINX_HTTP3=$http3
    printf '# Managed by BMS (scripts/ubuntu/nginx-site.sh): update.sh rewrites this file.\n'
    printf '# Local additions: %s/*.conf (inside the server block).\n' "$BMS_NGINX_LOCAL_DIR"
    if [[ $scheme == http ]]; then
        cat <<NGINX
server {
    listen 80;
    listen [::]:80;
    server_name $domain;
NGINX
        bms_nginx_app_body http "$web_root" "$port"
        return
    fi
    cat <<NGINX
server {
    listen 80;
    listen [::]:80;
    server_name $domain;
    server_tokens off;
    client_max_body_size $BMS_NGINX_DEFAULT_BODY;
    location /.well-known/acme-challenge/ { root $acme_root; }
    location / { return 301 https://$domain\$request_uri; }
}
server {
NGINX
    if [[ ${BMS_NGINX_HTTP2_DIRECTIVE:-yes} == yes ]]; then
        printf '    listen 443 ssl;\n    listen [::]:443 ssl;\n    http2 on;\n'
    else
        printf '    listen 443 ssl http2;\n    listen [::]:443 ssl http2;\n'
    fi
    if [[ $http3 == yes ]]; then
        printf '    listen 443 quic reuseport;\n    listen [::]:443 quic reuseport;\n    http3 on;\n'
    fi
    cat <<NGINX
    server_name $domain;
    ssl_certificate /etc/letsencrypt/live/$cert_name/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/$cert_name/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
NGINX
    bms_nginx_app_body https "$web_root" "$port"
}

# Catch-all for names this server does not serve: closes the connection (444) and, over TLS, refuses the handshake
# so no certificate is shown for an unknown name. [ports]: "80", "443" or "80 443" (only those without another
# default_server).
bms_nginx_catchall() {
    local ports=$1
    printf '# Managed by BMS (scripts/ubuntu/nginx-site.sh): unknown host names get no answer.\n'
    if [[ " $ports " == *' 80 '* ]]; then
        cat <<'NGINX'
server {
    listen 80 default_server;
    listen [::]:80 default_server;
    server_name _;
    server_tokens off;
    return 444;
}
NGINX
    fi
    if [[ " $ports " == *' 443 '* ]]; then
        cat <<'NGINX'
server {
    listen 443 ssl default_server;
    listen [::]:443 ssl default_server;
    server_name _;
    server_tokens off;
    ssl_reject_handshake on;
}
NGINX
    fi
}

# Ports (80, 443) that some enabled Nginx configuration other than the BMS catch-all already marks default_server.
# Reads `nginx -T` output on stdin.
bms_nginx_taken_default_ports() {
    awk -v ours="$BMS_NGINX_CATCHALL_SITE" -v ours_enabled="/etc/nginx/sites-enabled/bms-default" '
        /^# configuration file / { file = $4; sub(/:$/, "", file); skip = (file == ours || file == ours_enabled); next }
        skip { next }
        {
            line = $0
            sub(/#.*/, "", line)
            if (line !~ /^[[:space:]]*listen[[:space:]]/) next
            if (line !~ /[[:space:]](default_server|default)[[:space:];]/) next
            if (line ~ /(^|[^0-9])443([^0-9]|$)/) print 443
            else if (line ~ /(^|[^0-9])80([^0-9]|$)/ || line !~ /[0-9]/) print 80
        }' | sort -u | tr '\n' ' '
}

# Reads the parameters of an existing BMS site (written by any installer version): prints
# "<scheme> <domain> <cert_name or ->". Fails when the file is not a BMS site.
bms_nginx_site_params() {
    local file=$1 domain cert scheme=http
    grep -q 'proxy_pass http://127.0.0.1:8080' "$file" || return 1
    domain=$(awk '$1 == "server_name" { gsub(/;/, "", $2); print $2; exit }' "$file")
    [[ $domain =~ ^[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?$ && $domain == *.* ]] || return 1
    cert=-
    if grep -Eq '^[[:space:]]*listen[[:space:]]+(\[::\]:)?443[[:space:]]' "$file"; then
        scheme=https
        cert=$(sed -nE 's#^[[:space:]]*ssl_certificate[[:space:]]+/etc/letsencrypt/live/([^/]+)/fullchain\.pem;.*#\1#p' "$file" | head -n 1)
        [[ $cert =~ ^[A-Za-z0-9._-]+$ ]] || return 1
    fi
    printf '%s %s %s\n' "$scheme" "$domain" "$cert"
}
