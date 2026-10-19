# Throwaway spike proxy. Placeholders @ROOT@ and @CSP_HEADER@ are filled by scripts/up.sh.
worker_processes 1;
pid @ROOT@/.run/nginx.pid;
error_log @ROOT@/.run/nginx-error.log warn;
events { worker_connections 256; }
http {
  include /etc/nginx/mime.types;
  default_type application/octet-stream;
  access_log @ROOT@/.run/nginx-access.log;
  client_body_temp_path @ROOT@/.run/tmp/body;
  proxy_temp_path @ROOT@/.run/tmp/proxy;
  fastcgi_temp_path @ROOT@/.run/tmp/fcgi;
  uwsgi_temp_path @ROOT@/.run/tmp/uwsgi;
  scgi_temp_path @ROOT@/.run/tmp/scgi;
  server_tokens off;
  merge_slashes off;

  server {
    listen 8443 ssl;
    listen 8081;   # plain http on localhost: needed because service workers are refused under the self-signed certificate
    server_name localhost;
    ssl_certificate @ROOT@/.certs/localhost.crt;
    ssl_certificate_key @ROOT@/.certs/localhost.key;
    ssl_protocols TLSv1.2 TLSv1.3;

    # Security headers for shell and modules (Keycloak keeps its own headers).
    set $csp "@CSP@";

    # The HTML document comes from shell-server (nonce CSP + import map with integrity); assets are static.
    location = / { proxy_pass http://127.0.0.1:5400; }
    location = /index.html { proxy_pass http://127.0.0.1:5400; }
    location / {
      add_header X-Content-Type-Options nosniff always;
      root @ROOT@/shell/dist;
      try_files $uri @shell;   # History API fallback (UI-5, Q19)
    }
    location @shell { proxy_pass http://127.0.0.1:5400; }

    # Integrity fixtures (WP1.5): same strict CSP, and a copy without CSP to observe raw browser SRI behaviour.
    location /fixtures/ {
      add_header @CSP_HEADER@ $csp always;
      add_header X-Content-Type-Options nosniff always;
      alias @ROOT@/fixtures/;
    }
    location /fixtures-open/ {
      add_header X-Content-Type-Options nosniff always;
      alias @ROOT@/fixtures/;
    }

    location /registry/ {
      add_header X-Content-Type-Options nosniff always;
      add_header Cache-Control "no-store" always;
      alias @ROOT@/.run/registry/;
    }

    location /modules/a/remote/ {
      add_header @CSP_HEADER@ $csp always;
      add_header X-Content-Type-Options nosniff always;
      alias @ROOT@/.run/store/a/current/;
    }
    location /modules/b/remote/ {
      add_header @CSP_HEADER@ $csp always;
      add_header X-Content-Type-Options nosniff always;
      alias @ROOT@/.run/store/b/current/;
    }
    location /modules/evil/remote/ {
      add_header @CSP_HEADER@ $csp always;
      add_header X-Content-Type-Options nosniff always;
      alias @ROOT@/.run/store/evil/current/;
    }
    location /modules/mismatch/remote/ {
      add_header @CSP_HEADER@ $csp always;
      add_header X-Content-Type-Options nosniff always;
      alias @ROOT@/.run/store/mismatch/current/;
    }

    # Hostile module container behind the proxy (WP1.11): headers modules must not control are stripped.
    location /modules/hostile/ {
      proxy_pass http://127.0.0.1:5201;
      proxy_hide_header Set-Cookie;
      proxy_hide_header Content-Security-Policy;
      proxy_hide_header Content-Security-Policy-Report-Only;
      proxy_hide_header Access-Control-Allow-Origin;
      proxy_hide_header Access-Control-Allow-Credentials;
      proxy_hide_header Service-Worker-Allowed;
      add_header X-Content-Type-Options nosniff always;
    }

    location /api/stub/ {
      proxy_pass http://127.0.0.1:5300/;
      # Identity must come from validated tokens, never from client-supplied headers (8.5).
      proxy_set_header X-User "";
      proxy_set_header X-Remote-User "";
      proxy_set_header X-Forwarded-User "";
      proxy_set_header X-Auth-User "";
      proxy_set_header X-Forwarded-For $remote_addr;
      proxy_set_header X-Forwarded-Proto https;
      proxy_set_header X-Forwarded-Host $host;
    }

    location /auth/ {
      proxy_pass http://127.0.0.1:8080;
      proxy_set_header Host $host:8443;
      proxy_set_header X-Forwarded-For $remote_addr;
      proxy_set_header X-Forwarded-Proto https;
      proxy_set_header X-Forwarded-Host $host:8443;
      proxy_set_header X-Forwarded-Port 8443;
      proxy_buffer_size 16k;
      proxy_buffers 8 16k;
    }
  }
}
