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
    server_name localhost;
    ssl_certificate @ROOT@/.certs/localhost.crt;
    ssl_certificate_key @ROOT@/.certs/localhost.key;
    ssl_protocols TLSv1.2 TLSv1.3;

    # Security headers for shell and modules (Keycloak keeps its own headers).
    set $csp "@CSP@";

    location / {
      add_header @CSP_HEADER@ $csp always;
      add_header X-Content-Type-Options nosniff always;
      add_header Referrer-Policy no-referrer always;
      add_header Strict-Transport-Security "max-age=31536000" always;
      root @ROOT@/shell/dist;
      try_files $uri /index.html;   # History API fallback (UI-5, Q19)
    }

    location /registry/ {
      add_header X-Content-Type-Options nosniff always;
      add_header Cache-Control "no-store" always;
      alias @ROOT@/registry/;
    }

    location /modules/a/remote/ {
      add_header @CSP_HEADER@ $csp always;
      add_header X-Content-Type-Options nosniff always;
      alias @ROOT@/remote-a/dist/;
    }
    location /modules/b/remote/ {
      add_header @CSP_HEADER@ $csp always;
      add_header X-Content-Type-Options nosniff always;
      alias @ROOT@/remote-b/dist/;
    }
    location /modules/mismatch/remote/ {
      add_header @CSP_HEADER@ $csp always;
      add_header X-Content-Type-Options nosniff always;
      alias @ROOT@/remote-mismatch/dist/;
    }

    location /api/stub/ {
      proxy_pass http://127.0.0.1:5300/;
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
