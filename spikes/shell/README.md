# Spike: shell, Module Federation, window manager (PRD-0002)

Disposable code. Never merged into `main`. Citable state: branch `spike/shell`, head `b5b9baa` (a local tag `spike-shell-v1` exists, but pushing it was refused). Findings: `docs/spikes/2026-shell-spike-report.md` on branch `claude/spike-report` (on `main` after merge).

## Run (native; this environment has no Docker daemon, so Compose was not used)
```
(cd shell && npm ci && npm run build)
for d in remote-a remote-b remote-mismatch remote-evil; do (cd $d && npm ci && npm run build); done
(cd stub && npm ci); (cd tests && npm ci)
scripts/keycloak.sh start        # downloads nothing; expects keycloak/dist/keycloak-26.8.1 (Maven Central zip)
scripts/up.sh                    # register remotes, shell-server, hostile upstream, stub, nginx on https://localhost:8443
cd tests && WM_ENGINE=inhouse npx playwright test
scripts/down.sh && scripts/keycloak.sh stop
```
Test passwords are generated into `.run/secrets` (ignored). Playwright 1.56.1 matches the preinstalled Chromium build; Firefox is not available here.

## Prerequisites outside npm (not covered by the lockfiles)
nginx >= 1.24 with `/etc/nginx/mime.types`, OpenSSL, Java 21, Keycloak 26.8.1 (`https://repo1.maven.org/maven2/org/keycloak/keycloak-quarkus-dist/26.8.1/keycloak-quarkus-dist-26.8.1.zip`, unpack to `keycloak/dist/`), Playwright 1.56.1 with its Chromium build. After every rebuild of a remote run `node scripts/register.mjs` (or `scripts/up.sh`) again. Remove `.run/` and `tests/.auth/` for a clean start. Keycloak runs in `start-dev` mode and nginx without HTTP/2 or compression: see "Limits on validity" in the report before relying on any result.

## Layout
`shell/` host (Vite 8, React 19, Bootstrap 5.3, oidc-client-ts, window manager in `src/wm`), `remote-a|b|mismatch|evil/` federated modules, `sdk/` shared singleton, `registry/` manifests, `proxy/` nginx template, `stub/` token-validating API, `keycloak/` realm template, `tests/` Playwright suites, `results/` raw measurements and logs.
